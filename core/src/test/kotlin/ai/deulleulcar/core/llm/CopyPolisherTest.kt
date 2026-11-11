package ai.deulleulcar.core.llm

import ai.deulleulcar.core.cartask.CarTaskCopy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CopyPolisherTest {

    private val original = CarTaskCopy(
        what = "주행 가능 거리가 25km 정도 남았어요.",
        risk = "당장 위험하진 않지만",
        deadline = "오늘 안에 주유하는 게 좋아요.",
        question = "가까운 주유소, 경로상 +3분. 들를까요?",
    )

    private fun fake(response: String?, configured: Boolean = true) = object : LlmClient {
        override val providerName = "fake"
        override val isConfigured = configured
        var calls = 0
        override suspend fun generateJson(request: JsonGenerationRequest): String {
            calls++
            return response ?: throw LlmException.Http(500, "boom")
        }
    }

    private fun json(what: String, risk: String, deadline: String, question: String) =
        """{"what":"$what","risk":"$risk","deadline":"$deadline","question":"$question"}"""

    @Test
    fun `규칙을 지킨 다듬은 문구는 채택`() = runBlocking {
        val r = json("주행 가능 거리가 25km쯤 남았어요.", "지금 위험하진 않아요.", "오늘 중에 주유하시면 돼요.", "가까운 주유소가 경로상 +3분이에요. 들를까요?")
        val out = CopyPolisher(fake(r)).polish(original)
        assertEquals("오늘 중에 주유하시면 돼요.", out.deadline)
    }

    @Test
    fun `숫자가 바뀌면 원문`() = runBlocking {
        val r = json("주행 가능 거리가 30km쯤 남았어요.", "지금 위험하진 않아요.", "오늘 중에 주유하시면 돼요.", "경로상 +3분이에요. 들를까요?")
        assertEquals(original, CopyPolisher(fake(r)).polish(original))
    }

    @Test
    fun `질문이 들를까요로 끝나지 않으면 원문`() = runBlocking {
        val r = json("주행 가능 거리가 25km쯤 남았어요.", "지금 위험하진 않아요.", "오늘 중에 주유하세요.", "+3분 걸려요. 가실래요?")
        assertEquals(original, CopyPolisher(fake(r)).polish(original))
    }

    @Test
    fun `호출 실패, 잘못된 JSON, 키 없음이면 원문`() = runBlocking {
        assertEquals(original, CopyPolisher(fake(null)).polish(original))
        assertEquals(original, CopyPolisher(fake("not json")).polish(original))
        val noKey = fake("{}", configured = false)
        assertEquals(original, CopyPolisher(noKey).polish(original))
        assertEquals(0, noKey.calls)
    }
}
