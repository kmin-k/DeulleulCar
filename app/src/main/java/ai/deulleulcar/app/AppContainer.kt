package ai.deulleulcar.app

import ai.deulleulcar.app.location.FakeLocationSource
import ai.deulleulcar.app.location.LocationSource
import ai.deulleulcar.app.location.PleosLocationSource
import ai.deulleulcar.app.navi.FakeNaviPort
import ai.deulleulcar.app.navi.NaviPort
import ai.deulleulcar.app.navi.PleosNaviPort
import ai.deulleulcar.app.vehicle.FakeVehicleSignalSource
import ai.deulleulcar.app.vehicle.PleosVehicleSignalSource
import ai.deulleulcar.app.vehicle.VehicleSignalSource
import ai.deulleulcar.app.voice.FakeSpeechInput
import ai.deulleulcar.app.voice.FakeSpeechOutput
import ai.deulleulcar.app.voice.PleosSpeechInput
import ai.deulleulcar.app.voice.PleosSpeechOutput
import ai.deulleulcar.app.voice.SpeechInput
import ai.deulleulcar.app.voice.SpeechOutput
import ai.deulleulcar.app.voice.VoiceAnnouncer
import ai.deulleulcar.core.voice.Utterance
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach

/**
 * 수동 DI 컨테이너. 앱 전체에서 어댑터 인스턴스를 하나씩만 만든다.
 * @param useFakes true면 SDK 대신 Fake 구현(데모 모드·SDK 없는 기기).
 */
class AppContainer(context: Context, val useFakes: Boolean = false) {
    private val appContext = context.applicationContext

    /** 어댑터끼리 잇는 앱 수명 스코프(메인 스레드). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val vehicle: VehicleSignalSource by lazy {
        if (useFakes) FakeVehicleSignalSource() else PleosVehicleSignalSource(appContext)
    }

    val speechInput: SpeechInput by lazy {
        if (useFakes) FakeSpeechInput() else PleosSpeechInput(appContext)
    }

    val speechOutput: SpeechOutput by lazy {
        if (useFakes) FakeSpeechOutput() else PleosSpeechOutput(appContext)
    }

    /** 말 걸기는 모두 여기로. TBT 거리에 따라 보류·즉시를 고른다. */
    val announcer: VoiceAnnouncer by lazy { VoiceAnnouncer(speechOutput) }

    val navi: NaviPort by lazy {
        (if (useFakes) FakeNaviPort() else PleosNaviPort(appContext)).also { port ->
            // 상태 머신의 Speak 명령 → 일반 우선순위 발화, TBT 거리 → 발화 보류 판단
            port.onSpeak = { text -> announcer.announce(Utterance(text)) }
            port.nextTurn
                .map { it?.distanceM }
                .distinctUntilChanged()
                .onEach { announcer.onTurnDistance(it) }
                .launchIn(appScope)
        }
    }

    val location: LocationSource by lazy {
        if (useFakes) FakeLocationSource() else PleosLocationSource(appContext)
    }
}
