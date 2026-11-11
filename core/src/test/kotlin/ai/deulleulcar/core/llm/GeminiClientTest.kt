package ai.deulleulcar.core.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiClientTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = startServer()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun client(key: String? = FAKE_KEY, callTimeoutMillis: Long = 6_000) = GeminiClient(
        apiKeyProvider = { key },
        baseUrl = server.url("/").toString(),
        callTimeoutMillis = callTimeoutMillis,
    )

    private val request = JsonGenerationRequest("system", "퇴근길에 약국 들러", ErrandPrompt.responseSchema)

    @Test
    fun `요청 형식 - 경로, 헤더 키, 구조화 출력 설정, 발화만 전송`() = runBlocking {
        server.enqueue(geminiResponse("{\"ok\":true}"))
        val text = client().generateJson(request)
        assertEquals("{\"ok\":true}", text)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1beta/models/${GeminiClient.DEFAULT_MODEL}:generateContent", recorded.path)
        assertEquals(FAKE_KEY, recorded.getHeader("x-goog-api-key"))
        assertNull(recorded.requestUrl?.queryParameter("key"), "키는 URL에 넣지 않는다")

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val config = body["generationConfig"]!!.jsonObject
        assertEquals(JsonPrimitive("application/json"), config["responseMimeType"])
        assertTrue(config.containsKey("responseJsonSchema"))
        val userText = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]
        assertEquals(JsonPrimitive("퇴근길에 약국 들러"), userText)
        assertTrue(body.containsKey("systemInstruction"))
    }

    @Test
    fun `모델 ID는 생성자로 바꿀 수 있다`() = runBlocking {
        server.enqueue(geminiResponse("{}"))
        GeminiClient({ FAKE_KEY }, model = "other-model", baseUrl = server.url("/").toString()).generateJson(request)
        assertEquals("/v1beta/models/other-model:generateContent", server.takeRequest().path)
    }

    @Test
    fun `thought 파트는 빼고 텍스트만 이어 붙인다`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"생각","thought":true},{"text":"{\"a\":"},{"text":"1}"}]},"finishReason":"STOP"}]}""",
            ),
        )
        assertEquals("{\"a\":1}", client().generateJson(request))
    }

    @Test
    fun `키가 없으면 네트워크 호출 없이 MissingApiKey`() = runBlocking {
        assertFalse(client(key = "  ").isConfigured)
        assertThrows<LlmException.MissingApiKey> { runBlocking { client(key = null).generateJson(request) } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `500 에러는 Http 예외, 오류 메시지 포함`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":500,"message":"internal"}}"""))
        val e = assertThrows<LlmException.Http> { runBlocking { client().generateJson(request) } }
        assertEquals(500, e.code)
        assertTrue(e.message!!.contains("internal"))
        assertFalse(e.message!!.contains(FAKE_KEY))
    }

    @Test
    fun `응답이 없으면 Timeout`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertThrows<LlmException.Timeout> { runBlocking { client(callTimeoutMillis = 300).generateJson(request) } }
        Unit
    }

    @Test
    fun `차단된 프롬프트는 Blocked`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        val e = assertThrows<LlmException.Blocked> { runBlocking { client().generateJson(request) } }
        assertEquals("SAFETY", e.reason)
    }

    @Test
    fun `본문이 JSON이 아니거나 텍스트가 없으면 MalformedResponse`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        assertThrows<LlmException.MalformedResponse> { runBlocking { client().generateJson(request) } }
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}"""))
        assertThrows<LlmException.MalformedResponse> { runBlocking { client().generateJson(request) } }
        Unit
    }

    @Test
    fun `연결 실패는 Network`() = runBlocking {
        val c = GeminiClient({ FAKE_KEY }, baseUrl = UNREACHABLE_URL, connectTimeoutMillis = 500)
        assertThrows<LlmException.Network> { runBlocking { c.generateJson(request) } }
        Unit
    }
}
