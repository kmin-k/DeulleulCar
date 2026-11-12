package ai.deulleulcar.app.demo

import ai.deulleulcar.app.flow.AppController
import ai.deulleulcar.app.location.FakeLocationSource
import ai.deulleulcar.app.navi.FakeNaviPort
import ai.deulleulcar.app.vehicle.FakeVehicleSignalSource
import ai.deulleulcar.app.voice.FakeSpeechInput
import ai.deulleulcar.core.model.Gear
import ai.deulleulcar.core.model.TelltaleState
import ai.deulleulcar.core.model.TelltaleType

/**
 * 데모 모드(가짜 SDK)에서 시나리오 1~3을 손으로 진행하는 버튼 모음. 화면은 [DemoPanel].
 * 위치 시뮬레이션·자동 진행 같은 본격 데모 모드(T15)는 이 클래스에 덧붙인다.
 */
class DemoControls(
    private val vehicle: FakeVehicleSignalSource,
    private val navi: FakeNaviPort,
    private val location: FakeLocationSource,
    private val speechInput: FakeSpeechInput,
    private val controller: AppController,
) {
    val snapshot get() = vehicle.snapshot

    fun park() = vehicle.gear(Gear.P)

    fun drive() = vehicle.gear(Gear.D)

    fun toggleTelltale(type: TelltaleType) {
        val on = type in vehicle.snapshot.value.telltales
        vehicle.telltale(type, if (on) TelltaleState.OFF else TelltaleState.ON)
    }

    fun range(km: Double) = vehicle.range(km)

    /** 정기 점검 주기(10,000km)를 넘긴 누적 주행거리 / 점검 직후. */
    fun inspectionDue(due: Boolean) = vehicle.odometer(if (due) 12_000.0 else 5_000.0)

    /** 다음 경유지 도착: 위치를 그곳으로 옮기고 내비 도착 이벤트를 낸다. */
    fun arriveNext() {
        controller.state.value.trip?.rolling?.exposed?.firstOrNull()?.let { location.moveTo(it.place.point) }
        navi.arriveNext()
    }

    fun arriveDestination() {
        controller.state.value.trip?.plan?.destination?.let { location.moveTo(it) }
        navi.arriveDestination()
    }

    /** 다음 회전까지 거리. 300m 이내면 일반 음성이 보류된다. */
    fun turnAhead(meters: Double?) = navi.setTurnDistance(meters)

    /** 가짜 마이크에 말을 넣고 마이크 버튼을 누른 것처럼 처리한다. */
    fun say(text: String) {
        speechInput.enqueue(text)
        controller.onMicTapped()
    }

    companion object {
        const val SCENARIO_1 = "퇴근길에 약국이랑 마트 들렀다 집에 가자"
        const val SCENARIO_2 = "세탁소, 약국, 마트 들렀다 집"
    }
}
