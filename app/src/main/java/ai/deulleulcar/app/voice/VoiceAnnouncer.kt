package ai.deulleulcar.app.voice

import ai.deulleulcar.core.voice.Utterance
import ai.deulleulcar.core.voice.VoiceTurnScheduler
import android.util.Log
import java.time.Clock
import java.time.Instant

/**
 * 앱이 말을 거는 단일 창구. [VoiceTurnScheduler]로 타이밍을 고른 뒤 [SpeechOutput]에 넘긴다.
 * TBT 거리는 NaviPort가 [onTurnDistance]로 넣어 준다(AppContainer에서 연결).
 */
class VoiceAnnouncer(
    private val output: SpeechOutput,
    private val scheduler: VoiceTurnScheduler = VoiceTurnScheduler(),
    private val clock: Clock = Clock.systemUTC(),
) {
    @Synchronized
    fun announce(utterance: Utterance) {
        val now = clock.instant()
        val ready = scheduler.submit(utterance, now)
        if (ready.isEmpty()) Log.i(TAG, "회전 지점 근처(${scheduler.nextTurnDistanceM}m) → 보류: ${utterance.text}")
        speakAll(ready)
    }

    /** 다음 회전까지 거리(m). 안내가 끝나면 null. */
    @Synchronized
    fun onTurnDistance(distanceM: Double?) {
        speakAll(scheduler.onTurnDistance(distanceM, now()))
    }

    @Synchronized
    fun clear() {
        scheduler.clear()
        output.stopAll()
    }

    private fun now(): Instant = clock.instant()

    private fun speakAll(list: List<Utterance>) = list.forEach { output.speak(it.text, it.priority) }

    companion object {
        const val TAG = "DC/Voice"
    }
}
