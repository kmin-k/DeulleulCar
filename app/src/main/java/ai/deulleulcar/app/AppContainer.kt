package ai.deulleulcar.app

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
import android.content.Context

/**
 * 수동 DI 컨테이너. 앱 전체에서 어댑터 인스턴스를 하나씩만 만든다.
 * @param useFakes true면 SDK 대신 Fake 구현(데모 모드·SDK 없는 기기).
 */
class AppContainer(context: Context, val useFakes: Boolean = false) {
    private val appContext = context.applicationContext

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
}
