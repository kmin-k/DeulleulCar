package ai.deulleulcar.app.debug

import ai.deulleulcar.app.appContainer
import ai.deulleulcar.core.voice.SpeechPriority
import ai.deulleulcar.core.voice.Utterance
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 음성·내비·위치 점검 명령. 예:
 * ```
 * adb shell am start -n ai.deulleulcar.app/.debug.ProbeActivity --es cmd tts_say --es text "안녕하세요"
 * ```
 */
internal object ProbeCommands {
    private const val TAG = ProbeActivity.TAG

    fun handle(context: Context, cmd: String, intent: Intent, collect: (suspend () -> Unit) -> Unit) {
        val c = context.appContainer
        when (cmd) {
            // ---- TTS / STT ----
            "tts_start" -> {
                c.speechOutput.start()
                collect { c.speechOutput.state.collect { Log.i(TAG, "tts state: $it") } }
            }
            "tts_say" -> {
                val text = intent.getStringExtra("text") ?: "들를Car 음성 안내 점검입니다."
                c.speechOutput.speak(text) { ok -> Log.i(TAG, "tts done($ok): $text") }
            }
            "tts_urgent" -> {
                c.speechOutput.speak("첫 번째 일반 안내 문장입니다. 조금 길게 말하는 중이에요.") { Log.i(TAG, "tts done normal($it)") }
                c.speechOutput.speak("연료가 거의 없어요.", SpeechPriority.URGENT) { Log.i(TAG, "tts done urgent($it)") }
            }
            "announce" -> c.announcer.announce(Utterance(intent.getStringExtra("text") ?: "안내 문장"))
            "turn" -> c.announcer.onTurnDistance(intent.getStringExtra("m")?.toDoubleOrNull())
            "stt_start" -> {
                c.speechInput.start()
                collect { c.speechInput.state.collect { Log.i(TAG, "stt state: $it") } }
                collect { c.speechInput.events.collect { Log.i(TAG, "stt event: $it") } }
            }
            "stt_listen" -> c.speechInput.listen(intent.getStringExtra("timeout")?.toLongOrNull() ?: 8_000L)
            "stt_stop" -> c.speechInput.stopListening()

            else -> Log.w(TAG, "unknown cmd: $cmd")
        }
    }
}
