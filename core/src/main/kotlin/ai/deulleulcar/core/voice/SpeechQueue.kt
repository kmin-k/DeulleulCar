package ai.deulleulcar.core.voice

/**
 * TTS 큐 정책(엔진과 무관). 앱의 SpeechOutput 구현이 쓴다. 스레드 안전하지 않음 → 한 스레드에서만 호출한다.
 *
 * - 긴급 문장은 큐의 긴급 문장들 뒤, 일반 문장들 앞에 들어간다.
 * - 일반 문장을 말하는 중에 긴급 문장이 오면 [enqueue]가 true를 돌려준다 → 엔진을 멈추고 [onCurrentFinished]를 부르면
 *   끊긴 문장은 긴급 문장 바로 뒤에 다시 들어간다(처음부터 다시 말함).
 */
class SpeechQueue<T>(private val priorityOf: (T) -> SpeechPriority) {
    private val queue = ArrayDeque<T>()
    private var interrupting = false

    var current: T? = null
        private set

    val isIdle: Boolean get() = current == null
    val pending: List<T> get() = queue.toList()

    /** @return 지금 말하는 문장을 끊어야 하면 true. */
    fun enqueue(item: T): Boolean {
        if (priorityOf(item) != SpeechPriority.URGENT) {
            queue.addLast(item)
            return false
        }
        insertAfterUrgent(item)
        val cur = current ?: return false
        if (priorityOf(cur) == SpeechPriority.URGENT || interrupting) return false
        interrupting = true
        return true
    }

    /** 말할 다음 문장을 꺼내 [current]로 둔다. 말하는 중이거나 큐가 비었으면 null. */
    fun next(): T? {
        if (current != null) return null
        return queue.removeFirstOrNull()?.also { current = it }
    }

    /**
     * 현재 문장이 끝났다(완료·오류·중지 모두).
     * @return 완료 처리할 문장. 긴급 문장 때문에 끊겨서 다시 큐에 넣었으면 null.
     */
    fun onCurrentFinished(): T? {
        val cur = current ?: return null
        current = null
        if (interrupting) {
            interrupting = false
            insertAfterUrgent(cur)
            return null
        }
        return cur
    }

    /** 모두 버린다. 취소 처리할 문장들(말하는 중이던 것 포함)을 돌려준다. */
    fun clear(): List<T> {
        val all = listOfNotNull(current) + queue
        queue.clear()
        current = null
        interrupting = false
        return all
    }

    private fun insertAfterUrgent(item: T) {
        val firstNormal = queue.indexOfFirst { priorityOf(it) != SpeechPriority.URGENT }
        if (firstNormal < 0) queue.addLast(item) else queue.add(firstNormal, item)
    }
}
