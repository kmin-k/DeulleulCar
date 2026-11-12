package ai.deulleulcar.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechWatchdogTest {
    private data class S(val text: String, val p: SpeechPriority = SpeechPriority.NORMAL)

    @Test
    fun `추정 시간은 길이에 비례하고 6초에서 30초로 제한된다`() {
        val w = SpeechWatchdog()
        assertEquals(6_000L, w.estimateMs("네"))
        assertEquals(4_000L + 300L * 10, w.estimateMs("가".repeat(10)))
        assertEquals(30_000L, w.estimateMs("가".repeat(500)))
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

    @Test
    fun `감시 타이머가 끝낸 A의 늦은 끝 신호는 다음 발화 B를 끝내지 않는다`() {
        val w = SpeechWatchdog()
        val a = w.begin()
        assertTrue(w.onTimeout(a))
        val b = w.begin()
        assertFalse(w.onRealFinish()) // A의 늦은 onDone
        assertTrue(w.onTimeout(b)) // B 감시는 그대로 유효
    }

    @Test
    fun `B 시작 전에 온 늦은 끝 신호도 A의 것이고 B 자신의 끝 신호는 처리된다`() {
        val w = SpeechWatchdog()
        val a = w.begin()
        assertTrue(w.onTimeout(a))
        assertFalse(w.onRealFinish()) // B 시작 전 A의 늦은 신호
        w.begin()
        assertTrue(w.onRealFinish()) // B 자신의 신호
        assertEquals(0, w.orphanedFinishes)
    }

    @Test
    fun `감시 타이머가 두 번 연속 끝냈으면 늦은 신호도 두 번 삼킨다`() {
        val w = SpeechWatchdog()
        assertTrue(w.onTimeout(w.begin()))
        assertTrue(w.onTimeout(w.begin()))
        assertFalse(w.onRealFinish())
        assertFalse(w.onRealFinish())
        w.begin()
        assertTrue(w.onRealFinish())
    }
}
