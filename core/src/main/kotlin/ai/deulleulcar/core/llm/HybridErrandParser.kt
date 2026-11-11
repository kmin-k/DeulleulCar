package ai.deulleulcar.core.llm

import ai.deulleulcar.core.parser.ErrandParseException
import ai.deulleulcar.core.parser.ErrandParser
import ai.deulleulcar.core.parser.ParsedRequest
import ai.deulleulcar.core.parser.RuleBasedErrandParser
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeSource

/** 결과가 어느 경로에서 나왔는지. */
enum class ParseSource { LLM, RULE }

/** LLM 대신 규칙 기반을 쓴 이유. */
enum class FallbackReason { NO_API_KEY, TIMEOUT, NETWORK, HTTP_ERROR, BLOCKED, INVALID_RESPONSE, EMPTY_RESULT }

data class ParseOutcome(
    val request: ParsedRequest,
    val source: ParseSource,
    /** [ParseSource.RULE]일 때 그 이유. LLM 성공이면 null. */
    val fallbackReason: FallbackReason? = null,
)

/**
 * LLM 우선, 실패하면 규칙 기반으로 자동 전환하는 볼일 파서.
 * 키가 없으면 네트워크 호출 없이 바로 규칙 기반을 쓴다. 코루틴 취소는 삼키지 않는다.
 *
 * @param llm null이면 항상 규칙 기반
 * 503(과부하)·429(한도) 응답은 [retryBackoffMillis] 쉬고 한 번만 다시 시도한다.
 * 단, 전체 상한까지 남은 시간이 백오프 + [minAttemptMillis]보다 적으면 재시도 없이 fallback.
 *
 * @param overallTimeoutMillis HTTP 타임아웃과 별개로 전체 대기 상한 (재시도 포함)
 */
class HybridErrandParser(
    private val llm: LlmErrandParser?,
    private val fallback: ErrandParser = RuleBasedErrandParser(),
    private val overallTimeoutMillis: Long = 7_000,
    private val retryBackoffMillis: Long = 700,
    private val minAttemptMillis: Long = 1_500,
) {
    constructor(client: LlmClient) : this(LlmErrandParser(client))

    suspend fun parse(utterance: String): ParseOutcome {
        if (llm == null || !llm.isConfigured) return rule(utterance, FallbackReason.NO_API_KEY)
        if (utterance.isBlank()) return rule(utterance, FallbackReason.EMPTY_RESULT)
        val started = TimeSource.Monotonic.markNow()
        val reason = try {
            return ParseOutcome(
                withTimeout(overallTimeoutMillis) { parseWithRetry(llm, utterance, started) },
                ParseSource.LLM,
            )
        } catch (e: TimeoutCancellationException) {
            FallbackReason.TIMEOUT
        } catch (e: LlmException) {
            when (e) {
                is LlmException.MissingApiKey -> FallbackReason.NO_API_KEY
                is LlmException.Timeout -> FallbackReason.TIMEOUT
                is LlmException.Network -> FallbackReason.NETWORK
                is LlmException.Http -> FallbackReason.HTTP_ERROR
                is LlmException.Blocked -> FallbackReason.BLOCKED
                is LlmException.MalformedResponse -> FallbackReason.INVALID_RESPONSE
            }
        } catch (e: ErrandParseException) {
            if (e.noErrands) FallbackReason.EMPTY_RESULT else FallbackReason.INVALID_RESPONSE
        }
        return rule(utterance, reason)
    }

    private suspend fun parseWithRetry(
        llm: LlmErrandParser,
        utterance: String,
        started: TimeSource.Monotonic.ValueTimeMark,
    ): ParsedRequest = try {
        llm.parse(utterance)
    } catch (e: LlmException.Http) {
        val remaining = overallTimeoutMillis - started.elapsedNow().inWholeMilliseconds
        if (e.code !in RETRYABLE_CODES || remaining < retryBackoffMillis + minAttemptMillis) throw e
        delay(retryBackoffMillis)
        llm.parse(utterance)
    }

    private fun rule(utterance: String, reason: FallbackReason) =
        ParseOutcome(fallback.parse(utterance), ParseSource.RULE, reason)

    private companion object {
        val RETRYABLE_CODES = setOf(429, 503)
    }
}
