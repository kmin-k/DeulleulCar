package ai.deulleulcar.core.llm

import kotlinx.serialization.json.JsonObject

/**
 * 구조화(JSON) 응답을 돌려주는 LLM 요청 하나.
 * @property systemInstruction 역할·규칙 설명
 * @property userText 사용자 입력. 발화 텍스트만 담고 위치·주소·개인정보는 넣지 않는다.
 * @property schema 응답이 따라야 할 JSON Schema (type, properties, required, enum, items, ["string","null"] 정도만 사용)
 */
data class JsonGenerationRequest(
    val systemInstruction: String,
    val userText: String,
    val schema: JsonObject,
)

/**
 * LLM 공급자 추상화. 지금은 [GeminiClient] 하나, 나중에 Claude 구현을 같은 인터페이스로 추가한다.
 * 실패는 모두 [LlmException]으로 던진다 (코루틴 취소는 그대로 전파).
 */
interface LlmClient {
    /** 로그·디버그 표시용 공급자 이름 (예: "gemini"). */
    val providerName: String

    /** API 키 등 호출에 필요한 설정이 있는지. false면 네트워크 호출 없이 [LlmException.MissingApiKey]. */
    val isConfigured: Boolean

    /** [request]를 보내고 모델이 만든 JSON 텍스트를 그대로 돌려준다. 내용 검증은 호출자가 한다. */
    suspend fun generateJson(request: JsonGenerationRequest): String
}

/** LLM 호출 실패. fallback 사유 구분에 쓴다. 메시지에 API 키를 넣지 않는다. */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingApiKey : LlmException("API 키가 설정되지 않았습니다")
    class Timeout(cause: Throwable? = null) : LlmException("LLM 응답 시간 초과", cause)
    class Network(cause: Throwable) : LlmException("LLM 네트워크 오류: ${cause.javaClass.simpleName}", cause)
    class Http(val code: Int, detail: String) : LlmException("LLM HTTP $code: $detail")
    class Blocked(val reason: String) : LlmException("LLM이 응답을 거부했습니다: $reason")
    class MalformedResponse(detail: String, cause: Throwable? = null) : LlmException("LLM 응답 형식 오류: $detail", cause)
}
