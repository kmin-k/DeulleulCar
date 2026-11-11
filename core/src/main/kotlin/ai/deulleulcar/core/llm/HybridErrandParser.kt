package ai.deulleulcar.core.llm

import ai.deulleulcar.core.parser.ErrandParseException
import ai.deulleulcar.core.parser.ErrandParser
import ai.deulleulcar.core.parser.ParsedRequest
import ai.deulleulcar.core.parser.RuleBasedErrandParser
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

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
 * @param overallTimeoutMillis HTTP 타임아웃과 별개로 전체 대기 상한
 */
class HybridErrandParser(
    private val llm: LlmErrandParser?,
    private val fallback: ErrandParser = RuleBasedErrandParser(),
    private val overallTimeoutMillis: Long = 7_000,
) {
    constructor(client: LlmClient) : this(LlmErrandParser(client))

    suspend fun parse(utterance: String): ParseOutcome {
        if (llm == null || !llm.isConfigured) return rule(utterance, FallbackReason.NO_API_KEY)
        if (utterance.isBlank()) return rule(utterance, FallbackReason.EMPTY_RESULT)
        val reason = try {
            return ParseOutcome(withTimeout(overallTimeoutMillis) { llm.parse(utterance) }, ParseSource.LLM)
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

    private fun rule(utterance: String, reason: FallbackReason) =
        ParseOutcome(fallback.parse(utterance), ParseSource.RULE, reason)
}
