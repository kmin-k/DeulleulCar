package ai.deulleulcar.app.debug

import ai.deulleulcar.app.AppContainer
import ai.deulleulcar.app.appContainer
import ai.deulleulcar.app.navi.NaviPortEvent
import ai.deulleulcar.app.sdk.RuntimePermissions
import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.navi.NaviEvent
import ai.deulleulcar.core.navi.RollingWaypointMachine
import ai.deulleulcar.core.voice.SpeechPriority
import ai.deulleulcar.core.voice.Utterance
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 점검 명령. 각 명령은 필요한 어댑터를 먼저 start한다(start는 여러 번 불러도 한 번만 동작).
 *
 * - 차량: `vehicle_start`, `vehicle_snapshot`, `vehicle_stop`
 * - TTS: `tts_start`, `tts_say`(--es text), `tts_urgent`, `announce`(--es text), `turn`(--es m 거리)
 * - STT: `stt_start`, `stt_listen`(--es timeout ms), `stt_stop`
 * - 위치: `loc_start`, `loc_alert`
 * - 내비: `navi_start`, `navi_route`(--es n 정류지 수), `navi_wpinfo`, `navi_cancel`, `navi_state`
 */
internal object ProbeCommands {
    private const val TAG = ProbeActivity.TAG
    private var machine: RollingWaypointMachine? = null

    fun handle(context: Context, cmd: String, intent: Intent, collect: (key: String, block: suspend () -> Unit) -> Unit) {
        val c = context.appContainer
        when (cmd) {
            "perms" -> RuntimePermissions.ALL.forEach { p -> Log.i(TAG, "perm $p granted=${context.checkSelfPermission(p) == 0}") }

            // ---- 차량 ----
            "vehicle_start" -> vehicle(c, collect)
            "vehicle_snapshot" -> Log.i(TAG, "snapshot: ${c.vehicle.snapshot.value}")
            "vehicle_stop" -> c.vehicle.stop()

            // ---- TTS ----
            "tts_start" -> tts(c, collect)
            "tts_say" -> {
                tts(c, collect)
                val text = intent.getStringExtra("text") ?: "들를Car 음성 안내 점검입니다."
                c.speechOutput.speak(text) { ok -> Log.i(TAG, "tts done($ok): $text") }
            }
            "tts_urgent" -> {
                tts(c, collect)
                c.speechOutput.speak("첫 번째 일반 안내 문장입니다. 조금 길게 말하는 중이에요.") { Log.i(TAG, "tts done normal($it)") }
                c.speechOutput.speak("연료가 거의 없어요.", SpeechPriority.URGENT) { Log.i(TAG, "tts done urgent($it)") }
            }
            "announce" -> {
                tts(c, collect)
                c.announcer.announce(Utterance(intent.getStringExtra("text") ?: "안내 문장"))
            }
            "turn" -> c.announcer.onTurnDistance(intent.getStringExtra("m")?.toDoubleOrNull())

            // ---- STT ----
            "stt_start" -> stt(c, collect)
            "stt_listen" -> {
                stt(c, collect)
                c.speechInput.listen(intent.getStringExtra("timeout")?.toLongOrNull() ?: 8_000L)
            }
            "stt_stop" -> c.speechInput.stopListening()

            // ---- 위치 ----
            "loc_start" -> location(c, collect)
            "loc_alert" -> {
                location(c, collect)
                val here = c.location.location.value?.point
                if (here == null) Log.w(TAG, "위치 모름 → loc_start 후 다시") else c.location.addProximityAlert("probe-here", here, 100f)
            }

            // ---- 내비 ----
            "navi_start" -> navi(c, collect)
            "navi_route" -> {
                navi(c, collect)
                location(c, collect)
                // 현재 위치 기준 북동쪽으로 정류지 n개 + 목적지. 롤링 경유지 상태 머신으로 요청한다.
                val n = intent.getStringExtra("n")?.toIntOrNull() ?: 3
                val base = c.location.location.value?.point ?: GeoPoint(37.5006, 127.0364)
                val step = intent.getStringExtra("step")?.toDoubleOrNull() ?: 0.004
                val stops = (1..n).map { i ->
                    Stop(
                        Place("probe-$i", "점검정류지$i", base.lat + step * i, base.lon + step * i, PlaceCategory.OTHER),
                        StopSource.HUMAN,
                    )
                }
                val dest = GeoPoint(base.lat + step * (n + 1), base.lon + step * (n + 1), "점검목적지")
                Log.i(TAG, "route base=$base stops=${stops.map { "${it.place.name}(${it.place.lat},${it.place.lon})" }} dest=$dest")
                val m = RollingWaypointMachine(dest, stops)
                machine = m
                c.navi.execute(m.handle(NaviEvent.Start))
            }
            "navi_wpinfo" -> {
                navi(c, collect)
                c.navi.requestWaypointInfo()
            }
            "navi_cancel" -> c.navi.cancelRoute()
            "navi_state" -> Log.i(TAG, "machine state: ${machine?.state}")
            else -> Log.w(TAG, "unknown cmd: $cmd")
        }
    }

    private fun vehicle(c: AppContainer, collect: (String, suspend () -> Unit) -> Unit) {
        c.vehicle.start()
        collect("vehicle.signals") { c.vehicle.signals.collect { Log.i(TAG, "signal: $it") } }
        collect("vehicle.features") { c.vehicle.features.collect { Log.i(TAG, "vehicle features: $it") } }
    }

    private fun tts(c: AppContainer, collect: (String, suspend () -> Unit) -> Unit) {
        c.speechOutput.start()
        collect("tts.state") { c.speechOutput.state.collect { Log.i(TAG, "tts state: $it") } }
    }

    private fun stt(c: AppContainer, collect: (String, suspend () -> Unit) -> Unit) {
        c.speechInput.start()
        collect("stt.state") { c.speechInput.state.collect { Log.i(TAG, "stt state: $it") } }
        collect("stt.events") { c.speechInput.events.collect { Log.i(TAG, "stt event: $it") } }
    }

    private fun location(c: AppContainer, collect: (String, suspend () -> Unit) -> Unit) {
        c.location.start()
        collect("loc.state") { c.location.state.collect { Log.i(TAG, "loc state: $it") } }
        collect("loc.fix") { c.location.location.collect { Log.i(TAG, "loc: $it") } }
        collect("loc.proximity") {
            c.location.proximity.collect { p ->
                Log.i(TAG, "proximity: $p")
                machine?.let { m -> c.navi.execute(m.handle(p.toNaviEvent()).also { Log.i(TAG, "machine(prox) → $it") }) }
            }
        }
    }

    private fun navi(c: AppContainer, collect: (String, suspend () -> Unit) -> Unit) {
        c.navi.start()
        collect("navi.state") { c.navi.state.collect { Log.i(TAG, "navi state: $it") } }
        collect("navi.events") {
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
        collect("navi.turn") { c.navi.nextTurn.collect { Log.i(TAG, "next turn: $it") } }
    }
}
