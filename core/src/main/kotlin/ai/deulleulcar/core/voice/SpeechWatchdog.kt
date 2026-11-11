package ai.deulleulcar.core.voice

/**
 * TTS 완료 콜백(onDone)이 안 오는 엔진을 위한 감시 타이머 로직(타이머 자체는 앱이 돌린다). 한 스레드에서만 호출한다.
 *
 * 흐름: [begin]으로 발화 시작 등록(세대 토큰 발급) → 앱이 [estimateMs] 뒤에 [onTimeout]을 부른다.
 * 진짜 끝 이벤트는 [onRealFinish]로 처리한다. 둘 중 먼저 온 쪽만 true를 돌려주고, 늦게 온 쪽은 무시된다(이중 진행 방지).
 */
class SpeechWatchdog(
    private val baseMs: Long = DEFAULT_BASE_MS,
    private val perCharMs: Long = DEFAULT_PER_CHAR_MS,
    private val minMs: Long = DEFAULT_MIN_MS,
    private val maxMs: Long = DEFAULT_MAX_MS,
) {
    private var generation = 0
    private var active = false

    /** 발화 시작(또는 onStart로 재시작)을 등록한다. @return 이 발화의 세대 토큰. */
    fun begin(): Int {
        generation++
        active = true
        return generation
    }

    /** 문장 길이로 추정한 발화 시간(ms). */
    fun estimateMs(text: String): Long = (baseMs + perCharMs * text.length).coerceIn(minMs, maxMs)

    /** 감시 타이머 만료. @return 현재 발화의 토큰이고 아직 안 끝났으면 true(= 끝난 것으로 보고 큐를 진행). */
    fun onTimeout(token: Int): Boolean {
        if (!active || token != generation) return false
        active = false
        generation++
        return true
    }

    /** 진짜 onDone/onStop/onError. @return 진행 중인 발화였으면 true, 감시 타이머가 이미 처리했으면 false(무시). */
    fun onRealFinish(): Boolean {
        if (!active) return false
        active = false
        generation++
        return true
    }

    /** 큐를 비우는 등으로 감시를 중단한다. 이미 예약된 타이머는 토큰 불일치로 무효가 된다. */
    fun cancel() {
        active = false
        generation++
    }

    companion object {
        const val DEFAULT_BASE_MS = 1_500L
        const val DEFAULT_PER_CHAR_MS = 180L
        const val DEFAULT_MIN_MS = 3_000L
        const val DEFAULT_MAX_MS = 20_000L
    }
}
