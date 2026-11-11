package ai.deulleulcar.core.llm

import ai.deulleulcar.core.cartask.CarTaskCopy
import ai.deulleulcar.core.cartask.CopyTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [CopyTemplates] 문구를 LLM으로 자연스럽게 다듬는다. 실패하거나 규칙을 어기면 원래 문구를 그대로 돌려준다.
 *
 * 지켜야 할 것: 3단 구조(무엇 → 위험 → 언제까지) + 질문, 질문은 "들를까요?"로 끝남,
 * 숫자(거리·분)는 그대로, 각 부분이 지나치게 길어지지 않음.
 */
class CopyPolisher(
    private val client: LlmClient,
    private val timeoutMillis: Long = 7_000,
) {

    suspend fun polish(copy: CarTaskCopy): CarTaskCopy {
        if (!client.isConfigured) return copy
        return try {
            val raw = withTimeout(timeoutMillis) {
                client.generateJson(JsonGenerationRequest(SYSTEM, userText(copy), schema))
            }
            val dto = json.decodeFromString(Dto.serializer(), raw.trim())
            val polished = CarTaskCopy(dto.what.trim(), dto.risk.trim(), dto.deadline.trim(), dto.question.trim())
            if (isAcceptable(copy, polished)) polished else copy
        } catch (e: CancellationException) {
            // withTimeout 초과는 원문으로, 바깥 취소는 전파
            if (e is kotlinx.coroutines.TimeoutCancellationException) copy else throw e
        } catch (e: Exception) {
            copy
        }
    }

    internal fun isAcceptable(original: CarTaskCopy, polished: CarTaskCopy): Boolean {
        val pairs = listOf(
            original.what to polished.what,
            original.risk to polished.risk,
            original.deadline to polished.deadline,
            original.question to polished.question,
        )
        if (pairs.any { (_, p) -> p.isBlank() }) return false
        if (pairs.any { (o, p) -> p.length > o.length * 2 + 20 }) return false
        if (!polished.question.endsWith(QUESTION_END)) return false
        if (polished.what.contains(QUESTION_END) || polished.risk.contains(QUESTION_END) ||
            polished.deadline.contains(QUESTION_END)
        ) {
            return false
        }
        return numbers(original.spoken) == numbers(polished.spoken)
    }

    private fun numbers(s: String): List<String> = Regex("\\d+").findAll(s).map { it.value }.toList().sorted()

    private fun userText(copy: CarTaskCopy) = buildString {
        appendLine("what: ${copy.what}")
        appendLine("risk: ${copy.risk}")
        appendLine("deadline: ${copy.deadline}")
        append("question: ${copy.question}")
    }

    @Serializable
    private data class Dto(val what: String, val risk: String, val deadline: String, val question: String)

    private companion object {
        const val QUESTION_END = "들를까요?"
        val json = Json { ignoreUnknownKeys = true }
        val schema = Schema.obj(
            "what" to Schema.string(),
            "risk" to Schema.string(),
            "deadline" to Schema.string(),
            "question" to Schema.string(),
        )
        val SYSTEM = """
            너는 차량용 음성 안내 문구를 다듬는 편집자다. 운전자가 한 번 듣고 이해할 수 있게 짧고 쉬운 존댓말로 고친다.
            입력은 what(무엇이 문제인지), risk(지금 위험한지), deadline(언제까지 하면 되는지), question(들를지 묻는 질문) 네 부분이다.
            - 네 부분의 역할과 순서를 바꾸지 않는다. 내용을 더하거나 빼지 않는다.
            - 숫자·단위·장소 이름은 그대로 둔다.
            - question은 반드시 "들를까요?"로 끝내고, 다른 부분에는 질문을 넣지 않는다.
            - 겁주는 표현, 전문 용어, 이모지는 쓰지 않는다.
            지정된 JSON 스키마로만 답한다.
        """.trimIndent()
    }
}
