package ai.deulleulcar.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechQueueTest {
    private data class S(val text: String, val p: SpeechPriority = SpeechPriority.NORMAL)

    private fun queue() = SpeechQueue<S> { it.p }
    private fun urgent(t: String) = S(t, SpeechPriority.URGENT)

    @Test
    fun `일반 문장은 들어온 순서대로 하나씩 나온다`() {
        val q = queue()
        q.enqueue(S("a"))
        q.enqueue(S("b"))
        assertEquals("a", q.next()?.text)
        assertNull(q.next(), "말하는 중에는 다음 문장을 꺼내지 않는다")
        assertEquals("a", q.onCurrentFinished()?.text)
        assertEquals("b", q.next()?.text)
    }

    @Test
    fun `긴급 문장은 대기 중인 일반 문장보다 앞에 선다`() {
        val q = queue()
        q.enqueue(S("a"))
        q.enqueue(S("b"))
        q.enqueue(urgent("u1"))
        q.enqueue(urgent("u2"))
        assertEquals(listOf("u1", "u2", "a", "b"), q.pending.map { it.text })
    }

    @Test
    fun `일반 문장을 말하는 중에 긴급 문장이 오면 끊고, 끊긴 문장은 긴급 뒤에 다시 말한다`() {
        val q = queue()
        q.enqueue(S("normal"))
        q.next()
        assertTrue(q.enqueue(urgent("urgent")))
        assertNull(q.onCurrentFinished(), "끊긴 문장은 완료 처리하지 않는다")
        assertEquals("urgent", q.next()?.text)
        assertEquals("urgent", q.onCurrentFinished()?.text)
        assertEquals("normal", q.next()?.text)
    }

    @Test
    fun `긴급 문장을 말하는 중에는 다른 긴급 문장이 와도 끊지 않는다`() {
        val q = queue()
        q.enqueue(urgent("u1"))
        q.next()
        assertFalse(q.enqueue(urgent("u2")))
        assertEquals("u1", q.onCurrentFinished()?.text)
        assertEquals("u2", q.next()?.text)
    }

    @Test
    fun `끊는 중에 긴급 문장이 또 와도 한 번만 끊는다`() {
        val q = queue()
        q.enqueue(S("n"))
        q.next()
        assertTrue(q.enqueue(urgent("u1")))
        assertFalse(q.enqueue(urgent("u2")))
        q.onCurrentFinished()
        assertEquals(listOf("u1", "u2", "n"), q.pending.map { it.text })
    }

    @Test
    fun `clear는 말하는 중인 문장과 큐를 모두 돌려준다`() {
        val q = queue()
        q.enqueue(S("a"))
        q.enqueue(S("b"))
        q.next()
        assertEquals(listOf("a", "b"), q.clear().map { it.text })
        assertTrue(q.isIdle)
        assertNull(q.next())
    }
}
