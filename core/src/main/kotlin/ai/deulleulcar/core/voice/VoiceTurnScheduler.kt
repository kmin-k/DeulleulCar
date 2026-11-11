package ai.deulleulcar.core.voice

import java.time.Duration
import java.time.Instant

enum class SpeechPriority {
    /** 긴급 경고. 회전 직전이어도 바로 말한다(짧은 문장으로). */
    URGENT,

    /** 그 밖의 안내·제안. 회전 지점이 가까우면 미룬다. */
    NORMAL,
}

/**
 * 말할 문장 하나.
 * @property shortText 긴급 문장을 회전 직전에 말할 때 쓰는 짧은 버전. 없으면 [text]를 그대로 쓴다.
 * @property expiresAfter 이만큼 미뤄지면 버린다(때를 놓친 제안을 늦게 말하지 않도록). null이면 버리지 않는다.
 */
data class Utterance(
    val text: String,
    val priority: SpeechPriority = SpeechPriority.NORMAL,
    val shortText: String? = null,
    val expiresAfter: Duration? = DEFAULT_EXPIRY,
) {
    companion object {
        val DEFAULT_EXPIRY: Duration = Duration.ofMinutes(2)
    }
}

/**
 * TBT(다음 회전까지 거리) 기준으로 말할 타이밍을 고른다 (docs/ux/ux-flow.md 원칙 4).
 *
 * - 다음 회전이 [holdWithinM] 이내면 [SpeechPriority.NORMAL] 문장은 큐에 보류하고, 회전 구간을 벗어나면 순서대로 내보낸다.
 * - [SpeechPriority.URGENT]는 보류하지 않는다. 회전 구간 안이면 [Utterance.shortText]를 쓴다.
 * - 보류 중 [Utterance.expiresAfter]가 지난 문장은 버린다.
 *
 * 순수 로직이다: 반환된 목록을 실제 TTS(SpeechOutput)에 넣는 건 앱이 한다. 스레드 안전하지 않음.
 */
class VoiceTurnScheduler(private val holdWithinM: Double = DEFAULT_HOLD_WITHIN_M) {
    private data class Held(val utterance: Utterance, val since: Instant)

    private val held = ArrayDeque<Held>()

    /** 마지막으로 받은 다음 회전까지 거리(m). null이면 안내 중이 아니거나 모름 → 보류하지 않는다. */
    var nextTurnDistanceM: Double? = null
        private set

    val heldCount: Int get() = held.size

    private val inTurnZone: Boolean
        get() = nextTurnDistanceM?.let { it in 0.0..holdWithinM } ?: false

    /** 문장을 넣는다. 지금 말해야 할 문장(0~n개)을 돌려준다. */
    fun submit(utterance: Utterance, now: Instant): List<Utterance> {
        if (utterance.priority == SpeechPriority.URGENT) {
            val spoken = if (inTurnZone && utterance.shortText != null) utterance.copy(text = utterance.shortText) else utterance
            return listOf(spoken)
        }
        if (inTurnZone) {
            held.addLast(Held(utterance, now))
            return emptyList()
        }
        return drain(now) + utterance
    }

    /** TBT 갱신. 회전 구간을 벗어나면 보류했던 문장을 돌려준다. */
    fun onTurnDistance(distanceM: Double?, now: Instant): List<Utterance> {
        nextTurnDistanceM = distanceM
        return if (inTurnZone) {
            dropExpired(now)
            emptyList()
        } else {
            drain(now)
        }
    }

    /** 안내 종료 등으로 보류 문장을 모두 버린다. */
    fun clear() {
        held.clear()
    }

    private fun drain(now: Instant): List<Utterance> {
        dropExpired(now)
        val out = held.map { it.utterance }
        held.clear()
        return out
    }

    private fun dropExpired(now: Instant) {
        held.removeAll { h -> h.utterance.expiresAfter?.let { Duration.between(h.since, now) > it } ?: false }
    }

    companion object {
        const val DEFAULT_HOLD_WITHIN_M = 300.0
    }
}
