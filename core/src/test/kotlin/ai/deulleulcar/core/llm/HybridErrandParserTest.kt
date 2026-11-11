package ai.deulleulcar.core.llm

import ai.deulleulcar.core.model.Errand
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.parser.DestinationHint
import ai.deulleulcar.core.parser.RuleBasedErrandParser
import ai.deulleulcar.core.parser.TimeHint
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HybridErrandParserTest {

    private lateinit var server: MockWebServer
    private val utterance = "퇴근길에 약국이랑 마트 들렀다 집에 가자"
    private val ruleResult = RuleBasedErrandParser().parse(utterance)

    @BeforeEach
    fun setUp() {
        server = startServer()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun hybrid(key: String? = FAKE_KEY, callTimeoutMillis: Long = 6_000) = HybridErrandParser(
        GeminiClient({ key }, baseUrl = server.url("/").toString(), callTimeoutMillis = callTimeoutMillis),
    )

    @Test
    fun `정상 응답은 LLM 결과`() = runBlocking {
        server.enqueue(geminiResponse(PHARMACY_MART_HOME))
        val out = hybrid().parse(utterance)
        assertEquals(ParseSource.LLM, out.source)
        assertNull(out.fallbackReason)
        assertEquals(
            listOf(Errand("약국", PlaceCategory.PHARMACY), Errand("마트", PlaceCategory.MART)),
            out.request.errands,
        )
        assertEquals(DestinationHint.Home, out.request.destination)
        assertEquals(TimeHint.COMMUTE_HOME, out.request.timeHint)
    }

    @Test
    fun `상호와 이름 있는 목적지`() = runBlocking {
        server.enqueue(
            geminiResponse(
                """{"errands":[{"keyword":"햇살약국","category":"PHARMACY","placeName":"햇살약국"}],
                   "destination":{"type":"NAMED","name":"체육관"},"timeHint":"ON_THE_WAY"}""",
            ),
        )
        val out = hybrid().parse("체육관 가는 길에 햇살약국 들러")
        assertEquals(ParseSource.LLM, out.source)
        assertEquals(listOf(Errand("햇살약국", PlaceCategory.PHARMACY, "햇살약국")), out.request.errands)
        assertEquals(DestinationHint.Named("체육관"), out.request.destination)
        assertEquals(TimeHint.ON_THE_WAY, out.request.timeHint)
    }

    @Test
    fun `키가 없으면 네트워크 호출 없이 규칙 기반`() = runBlocking {
        val out = hybrid(key = null).parse(utterance)
        assertEquals(ParseSource.RULE, out.source)
        assertEquals(FallbackReason.NO_API_KEY, out.fallbackReason)
        assertEquals(ruleResult, out.request)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `LLM 파서가 없으면 규칙 기반`() = runBlocking {
        val out = HybridErrandParser(llm = null).parse(utterance)
        assertEquals(ParseSource.RULE, out.source)
        assertEquals(ruleResult, out.request)
    }

    @Test
    fun `잘못된 JSON이면 fallback`() = runBlocking {
        server.enqueue(geminiResponse("이건 JSON이 아니에요"))
        val out = hybrid().parse(utterance)
        assertEquals(ParseSource.RULE, out.source)
        assertEquals(FallbackReason.INVALID_RESPONSE, out.fallbackReason)
        assertEquals(ruleResult, out.request)
    }

    @Test
    fun `스키마와 다른 JSON이면 fallback`() = runBlocking {
        server.enqueue(geminiResponse("""{"errands":"약국"}"""))
        val out = hybrid().parse(utterance)
        assertEquals(FallbackReason.INVALID_RESPONSE, out.fallbackReason)
    }

    @Test
    fun `알 수 없는 업종은 OTHER로 낮춘다`() = runBlocking {
        server.enqueue(
            geminiResponse(
                """{"errands":[{"keyword":"꽃집","category":"FLORIST","placeName":null},
                               {"keyword":"약국","category":"pharmacy","placeName":""}],
                   "destination":{"type":"SPACESHIP","name":null},"timeHint":"LATER"}""",
            ),
        )
        val out = hybrid().parse("꽃집이랑 약국 들러")
        assertEquals(ParseSource.LLM, out.source)
        assertEquals(
            listOf(Errand("꽃집", PlaceCategory.OTHER), Errand("약국", PlaceCategory.PHARMACY)),
            out.request.errands,
        )
        assertNull(out.request.destination)
        assertNull(out.request.timeHint)
    }

    @Test
    fun `빈 결과면 fallback`() = runBlocking {
        server.enqueue(geminiResponse("""{"errands":[],"destination":{"type":"NONE","name":null},"timeHint":"NONE"}"""))
        val out = hybrid().parse(utterance)
        assertEquals(ParseSource.RULE, out.source)
        assertEquals(FallbackReason.EMPTY_RESULT, out.fallbackReason)
        assertEquals(ruleResult, out.request)
    }

    @Test
    fun `중복 볼일은 한 번만`() = runBlocking {
        server.enqueue(
            geminiResponse(
                """{"errands":[{"keyword":"약국","category":"PHARMACY","placeName":null},
                               {"keyword":"약방","category":"PHARMACY","placeName":null}],
                   "destination":{"type":"NONE","name":null},"timeHint":"NONE"}""",
            ),
        )
        assertEquals(1, hybrid().parse("약국 약방 들러").request.errands.size)
    }

    @Test
    fun `500 에러면 fallback`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"internal"}}"""))
        val out = hybrid().parse(utterance)
        assertEquals(ParseSource.RULE, out.source)
        assertEquals(FallbackReason.HTTP_ERROR, out.fallbackReason)
        assertEquals(ruleResult, out.request)
    }

    @Test
    fun `HTTP 타임아웃이면 fallback`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val out = hybrid(callTimeoutMillis = 300).parse(utterance)
        assertEquals(ParseSource.RULE, out.source)
        assertEquals(FallbackReason.TIMEOUT, out.fallbackReason)
        assertEquals(ruleResult, out.request)
    }

    @Test
    fun `전체 대기 상한을 넘으면 fallback`() = runBlocking {
        val slow = object : LlmClient {
            override val providerName = "slow"
            override val isConfigured = true
            override suspend fun generateJson(request: JsonGenerationRequest): String {
                delay(5_000)
                return PHARMACY_MART_HOME
            }
        }
        val out = HybridErrandParser(LlmErrandParser(slow), overallTimeoutMillis = 100).parse(utterance)
        assertEquals(ParseSource.RULE, out.source)
        assertEquals(FallbackReason.TIMEOUT, out.fallbackReason)
    }

    @Test
    fun `연결 실패면 fallback`() = runBlocking {
        val out = HybridErrandParser(
            GeminiClient({ FAKE_KEY }, baseUrl = UNREACHABLE_URL, connectTimeoutMillis = 500),
        ).parse(utterance)
        assertEquals(ParseSource.RULE, out.source)
        assertEquals(FallbackReason.NETWORK, out.fallbackReason)
    }

    @Test
    fun `프롬프트 업종 목록은 enum에서 만든다`() {
        PlaceCategory.entries.forEach {
            assertTrue(ErrandPrompt.systemInstruction.contains(it.name), "${it.name} 없음")
        }
        assertEquals(PlaceCategory.entries.map { it.name }, ErrandPrompt.categoryNames)
    }
}
