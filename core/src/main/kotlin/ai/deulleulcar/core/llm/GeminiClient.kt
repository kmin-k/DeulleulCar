package ai.deulleulcar.core.llm

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gemini API `models.generateContent` REST 클라이언트.
 *
 * 구조화 출력: `generationConfig.responseMimeType = "application/json"` + `generationConfig.responseJsonSchema`.
 * API 키는 `x-goog-api-key` 헤더로만 보낸다 (URL·로그·예외 메시지에 넣지 않음).
 *
 * @param apiKeyProvider 호출 시점의 키. 설정 화면에서 입력하므로 람다로 받는다. null/빈 값이면 호출하지 않는다.
 * @param model 모델 ID (기본 `gemini-3.5-flash-lite`)
 * @param baseUrl 테스트에서 MockWebServer 주소로 바꾼다
 */
class GeminiClient(
    private val apiKeyProvider: () -> String?,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
    connectTimeoutMillis: Long = 3_000,
    callTimeoutMillis: Long = 6_000,
    httpClient: OkHttpClient? = null,
) : LlmClient {

    private val client: OkHttpClient = (httpClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override val providerName: String = "gemini"

    override val isConfigured: Boolean get() = !apiKeyProvider().isNullOrBlank()

    override suspend fun generateJson(request: JsonGenerationRequest): String {
        val key = apiKeyProvider()?.trim()
        if (key.isNullOrEmpty()) throw LlmException.MissingApiKey()

        val httpRequest = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(buildBody(request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val (code, body) = execute(httpRequest)
        if (code !in 200..299) throw LlmException.Http(code, errorMessage(body))
        return extractText(body)
    }

    private fun buildBody(request: JsonGenerationRequest): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", request.systemInstruction) } }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", request.userText) } }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", request.schema)
        }
    }

    /** OkHttp 비동기 호출을 코루틴으로. 취소되면 HTTP 호출도 취소한다. */
    private suspend fun execute(request: Request): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(mapIoException(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to (it.body?.string() ?: "") }
                } catch (e: IOException) {
                    if (cont.isActive) cont.resumeWithException(mapIoException(e))
                    return
                }
                if (cont.isActive) cont.resume(result)
            }
        })
    }

    private fun mapIoException(e: IOException): LlmException =
        if (e is SocketTimeoutException || (e is InterruptedIOException && e.message == "timeout")) {
            LlmException.Timeout(e)
        } else {
            LlmException.Network(e)
        }

    /** 오류 응답의 `error.message` (최대 200자). 본문이 JSON이 아니면 상태만. */
    private fun errorMessage(body: String): String {
        val message = runCatching {
            (json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)
                ?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()
        return (message ?: "응답 본문 없음").take(200)
    }

    /** candidates[0].content.parts[].text (thought 파트 제외)를 이어 붙인다. */
    private fun extractText(body: String): String {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: SerializationException) {
            throw LlmException.MalformedResponse("응답이 JSON이 아님", e)
        } catch (e: IllegalArgumentException) {
            throw LlmException.MalformedResponse("응답이 JSON 객체가 아님", e)
        }
        val candidates = root["candidates"] as? JsonArray
        if (candidates.isNullOrEmpty()) {
            val blockReason = (root["promptFeedback"] as? JsonObject)?.string("blockReason")
            if (blockReason != null) throw LlmException.Blocked(blockReason)
            throw LlmException.MalformedResponse("candidates 없음")
        }
        val candidate = candidates.first() as? JsonObject
            ?: throw LlmException.MalformedResponse("candidate 형식 오류")
        val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val text = parts.mapNotNull { it as? JsonObject }
            .filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
            .mapNotNull { it.string("text") }
            .joinToString("")
            .trim()
        if (text.isEmpty()) {
            val finish = candidate.string("finishReason") ?: "UNKNOWN"
            if (finish == "SAFETY" || finish == "PROHIBITED_CONTENT" || finish == "BLOCKLIST") {
                throw LlmException.Blocked(finish)
            }
            throw LlmException.MalformedResponse("빈 응답 (finishReason=$finish)")
        }
        return text
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** 작은 JSON Schema 작성 도우미. */
internal object Schema {
    fun obj(vararg props: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { props.forEach { add(it.first) } }
    }

    fun string(description: String? = null): JsonObject = buildJsonObject {
        put("type", "string")
        description?.let { put("description", it) }
    }

    fun nullableString(description: String? = null): JsonObject = buildJsonObject {
        putJsonArray("type") { add("string"); add("null") }
        description?.let { put("description", it) }
    }

    fun enum(values: List<String>, description: String? = null): JsonObject = buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(it) } }
        description?.let { put("description", it) }
    }

    fun array(items: JsonObject, maxItems: Int? = null): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", items)
        maxItems?.let { put("maxItems", it) }
    }
}
