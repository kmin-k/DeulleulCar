package ai.deulleulcar.core.voice

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceTurnSchedulerTest {
    private val t0 = Instant.parse("2026-11-11T09:00:00Z")
    private fun at(sec: Long) = t0.plusSeconds(sec)

    @Test
    fun `회전 지점이 멀면 일반 문장을 바로 말한다`() {
        val s = VoiceTurnScheduler()
        s.onTurnDistance(800.0, t0)
        val out = s.submit(Utterance("약국 도착했어요"), t0)
        assertEquals(listOf("약국 도착했어요"), out.map { it.text })
    }

    @Test
    fun `안내 중이 아니면(TBT 없음) 바로 말한다`() {
        val s = VoiceTurnScheduler()
        assertEquals(1, s.submit(Utterance("안녕하세요"), t0).size)
    }

    @Test
    fun `다음 회전이 300m 이내면 일반 문장을 보류하고 벗어나면 순서대로 내보낸다`() {
        val s = VoiceTurnScheduler()
        s.onTurnDistance(250.0, t0)
        assertTrue(s.submit(Utterance("첫째"), t0).isEmpty())
        assertTrue(s.submit(Utterance("둘째"), at(1)).isEmpty())
        assertEquals(2, s.heldCount)
        assertTrue(s.onTurnDistance(120.0, at(5)).isEmpty())
        val released = s.onTurnDistance(900.0, at(10))
        assertEquals(listOf("첫째", "둘째"), released.map { it.text })
        assertEquals(0, s.heldCount)
    }

    @Test
    fun `정확히 300m는 보류 구간이다`() {
        val s = VoiceTurnScheduler()
        s.onTurnDistance(300.0, t0)
        assertTrue(s.submit(Utterance("x"), t0).isEmpty())
    }

    @Test
    fun `긴급 문장은 회전 직전이어도 짧은 문장으로 즉시 말한다`() {
        val s = VoiceTurnScheduler()
        s.onTurnDistance(100.0, t0)
        val out = s.submit(
            Utterance("연료가 25km 정도 남았어요. 2km 앞 주유소에 들를까요?", SpeechPriority.URGENT, shortText = "연료가 거의 없어요"),
            t0,
        )
        assertEquals(listOf("연료가 거의 없어요"), out.map { it.text })
    }

    @Test
    fun `긴급 문장은 회전 구간 밖이면 원문을 쓴다`() {
        val s = VoiceTurnScheduler()
        s.onTurnDistance(1000.0, t0)
        val out = s.submit(Utterance("긴 원문", SpeechPriority.URGENT, shortText = "짧게"), t0)
        assertEquals("긴 원문", out.single().text)
    }

    @Test
    fun `오래 보류된 문장은 버린다`() {
        val s = VoiceTurnScheduler()
        s.onTurnDistance(200.0, t0)
        s.submit(Utterance("곧 버려질 문장", expiresAfter = Duration.ofSeconds(30)), t0)
        s.submit(Utterance("남는 문장", expiresAfter = null), t0)
        val released = s.onTurnDistance(null, at(60))
        assertEquals(listOf("남는 문장"), released.map { it.text })
    }

    @Test
    fun `보류 문장이 있을 때 회전 구간을 벗어나 새 문장이 오면 보류분 뒤에 붙는다`() {
        val s = VoiceTurnScheduler()
        s.onTurnDistance(200.0, t0)
        s.submit(Utterance("먼저"), t0)
        // TBT 갱신 없이 거리 정보만 바뀐 경우를 흉내: 회전 통과 후 첫 갱신 전에 새 문장
        s.onTurnDistance(500.0, at(3)).let { assertEquals(listOf("먼저"), it.map { u -> u.text }) }
        assertEquals(listOf("나중"), s.submit(Utterance("나중"), at(4)).map { it.text })
    }

    @Test
    fun `clear는 보류 문장을 모두 버린다`() {
        val s = VoiceTurnScheduler()
        s.onTurnDistance(10.0, t0)
        s.submit(Utterance("a"), t0)
        s.clear()
        assertTrue(s.onTurnDistance(null, at(1)).isEmpty())
    }
}
