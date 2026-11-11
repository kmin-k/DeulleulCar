package ai.deulleulcar.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechWatchdogTest {
    private data class S(val text: String, val p: SpeechPriority = SpeechPriority.NORMAL)

    @Test
    fun `추정 시간은 길이에 비례하고 3초에서 20초로 제한된다`() {
        val w = SpeechWatchdog()
        assertEquals(3_000L, w.estimateMs("네"))
        assertEquals(1_500L + 180L * 10, w.estimateMs("가".repeat(10)))
        assertEquals(20_000L, w.estimateMs("가".repeat(500)))
    }

    @Test
    fun `타이머가 만료되면 큐가 다음 문장으로 넘어간다`() {
        val q = SpeechQueue<S> { it.p }
        val w = SpeechWatchdog()
        q.enqueue(S("a")); q.enqueue(S("b"))
        q.next()
        val token = w.begin()
        assertTrue(w.onTimeout(token))
        assertEquals("a", q.onCurrentFinished()?.text)
        assertEquals("b", q.next()?.text)
    }

    @Test
    fun `진짜 onDone이 오면 타이머는 무효가 된다`() {
        val w = SpeechWatchdog()
        val token = w.begin()
        assertTrue(w.onRealFinish())
        assertFalse(w.onTimeout(token))
    }

    @Test
    fun `타이머 뒤에 늦게 온 onDone은 무시된다`() {
        val w = SpeechWatchdog()
        val token = w.begin()
        assertTrue(w.onTimeout(token))
        assertFalse(w.onRealFinish())
        assertFalse(w.onTimeout(token))
    }

    @Test
    fun `이전 발화의 오래된 토큰은 다음 발화에 영향을 주지 않는다`() {
        val w = SpeechWatchdog()
        val old = w.begin()
        w.onRealFinish()
        val fresh = w.begin()
        assertFalse(w.onTimeout(old))
        assertTrue(w.onTimeout(fresh))
    }

    @Test
    fun `긴급 끼어들기 후에도 감시가 이어진다`() {
        val q = SpeechQueue<S> { it.p }
        val w = SpeechWatchdog()
        q.enqueue(S("n")); q.next(); val t1 = w.begin()
        assertTrue(q.enqueue(S("u", SpeechPriority.URGENT)))
        // onStop 도착
        assertTrue(w.onRealFinish())
        assertEquals(null, q.onCurrentFinished())
        assertFalse(w.onTimeout(t1))
        assertEquals("u", q.next()?.text)
        val t2 = w.begin()
        assertTrue(w.onTimeout(t2))
        assertEquals("u", q.onCurrentFinished()?.text)
        assertEquals("n", q.next()?.text)
    }
}
