package ai.deulleulcar.app.debug

import ai.deulleulcar.app.appContainer
import ai.deulleulcar.app.navi.NaviPortEvent
import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.navi.RollingWaypointMachine
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
    private var machine: RollingWaypointMachine? = null

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

            // ---- 위치 ----
            "loc_start" -> {
                c.location.start()
                collect { c.location.state.collect { Log.i(TAG, "loc state: $it") } }
                collect { c.location.location.collect { Log.i(TAG, "loc: $it") } }
                collect { c.location.proximity.collect { Log.i(TAG, "proximity: $it") } }
            }
            "loc_alert" -> {
                val here = c.location.location.value?.point ?: return Unit.also { Log.w(TAG, "위치 모름") }
                c.location.addProximityAlert("probe-here", here, 100f)
            }

            // ---- 내비 ----
            "navi_start" -> {
                c.navi.start()
                collect { c.navi.state.collect { Log.i(TAG, "navi state: $it") } }
                collect {
                    c.navi.events.collect { e ->
                        Log.i(TAG, "navi event: $e")
                        val m = machine
                        if (e is NaviPortEvent.Machine && m != null) {
                            val cmds = m.handle(e.event)
                            Log.i(TAG, "machine → $cmds / state=${m.state}")
                            c.navi.execute(cmds)
                        }
                    }
                }
                collect { c.navi.nextTurn.collect { Log.i(TAG, "next turn: $it") } }
            }
            "navi_route" -> {
                // 현재 위치 기준 북동쪽으로 정류지 n개 + 목적지. 롤링 경유지 상태 머신으로 요청한다.
                val n = intent.getStringExtra("n")?.toIntOrNull() ?: 3
                val base = c.location.location.value?.point ?: GeoPoint(37.5006, 127.0364)
                val stops = (1..n).map { i ->
                    Stop(
                        Place("probe-$i", "점검정류지$i", base.lat + 0.004 * i, base.lon + 0.004 * i, PlaceCategory.OTHER),
                        StopSource.HUMAN,
                    )
                }
                val dest = GeoPoint(base.lat + 0.004 * (n + 1), base.lon + 0.004 * (n + 1), "점검목적지")
                val m = RollingWaypointMachine(dest, stops)
                machine = m
                c.navi.execute(m.handle(ai.deulleulcar.core.navi.NaviEvent.Start))
            }
            "navi_wpinfo" -> c.navi.requestWaypointInfo()
            "navi_cancel" -> c.navi.cancelRoute()
            "navi_state" -> Log.i(TAG, "machine state: ${machine?.state}")
            else -> Log.w(TAG, "unknown cmd: $cmd")
        }
    }
}
