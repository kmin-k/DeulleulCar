package ai.deulleulcar.app.vehicle

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.model.CarSignal
import ai.deulleulcar.core.model.Gear
import ai.deulleulcar.core.model.TelltaleState
import ai.deulleulcar.core.model.TelltaleType
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 앱이 구독하는 차량 신호 묶음. */
enum class VehicleFeature {
    TELLTALES, RANGE, GEAR, ODOMETER,

    /** Safety 레벨 신호(경고등과 별도). capability가 있을 때만 구독. */
    BRAKE_FLUID, ENGINE_OIL, WASHER_FLUID,
}

/**
 * 지금까지 받은 차량 상태의 최신값. UI가 처음 그릴 때 [VehicleSignalSource.signals]를 기다리지 않고 읽는다.
 * @property telltales 켜져 있는(ON/BLINK) 경고등만
 */
data class VehicleSnapshot(
    val gear: Gear? = null,
    val rangeKm: Double? = null,
    val odometerKm: Double? = null,
    val telltales: Map<TelltaleType, TelltaleState> = emptyMap(),
)

/**
 * 차량 신호 소스. Pleos Vehicle SDK 구현([PleosVehicleSignalSource])과 데모·테스트용 [FakeVehicleSignalSource]가 있다.
 *
 * [start]하면 지원되는 신호만 구독하고, 값이 바뀔 때마다 [signals]로 [CarSignal]을 내보낸다(경고등은 바뀐 것만).
 * [stop]은 구독을 모두 해제하고 SDK를 놓는다. 다시 [start]할 수 있다.
 */
interface VehicleSignalSource {
    /** 값이 바뀔 때마다 나오는 신호. 늦게 구독하면 지난 신호는 못 받으므로 초기값은 [snapshot]에서 읽는다. */
    val signals: SharedFlow<CarSignal>

    val snapshot: StateFlow<VehicleSnapshot>

    val features: StateFlow<Map<VehicleFeature, FeatureState>>

    fun start()

    fun stop()
}

/** [VehicleSnapshot]에 신호 하나를 반영한다. 구현체가 공통으로 쓴다. */
internal fun VehicleSnapshot.apply(signal: CarSignal): VehicleSnapshot = when (signal) {
    is CarSignal.GearChanged -> copy(gear = signal.gear)
    is CarSignal.RemainingRange -> copy(rangeKm = signal.km)
    is CarSignal.Odometer -> copy(odometerKm = signal.km)
    is CarSignal.Telltale -> copy(
        telltales = if (signal.state == TelltaleState.OFF) telltales - signal.type else telltales + (signal.type to signal.state),
    )
}
