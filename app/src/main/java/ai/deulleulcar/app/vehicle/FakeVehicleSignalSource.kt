package ai.deulleulcar.app.vehicle

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.model.CarSignal
import ai.deulleulcar.core.model.Gear
import ai.deulleulcar.core.model.TelltaleState
import ai.deulleulcar.core.model.TelltaleType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 데모 모드·테스트용. [emit] 또는 편의 함수로 원하는 신호를 넣는다. */
class FakeVehicleSignalSource(
    initial: VehicleSnapshot = VehicleSnapshot(gear = Gear.P, rangeKm = 420.0, odometerKm = 12_000.0),
) : VehicleSignalSource {
    private val _signals = MutableSharedFlow<CarSignal>(extraBufferCapacity = 64)
    override val signals: SharedFlow<CarSignal> = _signals.asSharedFlow()

    private val _snapshot = MutableStateFlow(initial)
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val _features = MutableStateFlow(VehicleFeature.entries.associateWith { FeatureState.UNKNOWN })
    override val features: StateFlow<Map<VehicleFeature, FeatureState>> = _features.asStateFlow()

    var started = false
        private set

    override fun start() {
        started = true
        _features.value = VehicleFeature.entries.associateWith { FeatureState.ACTIVE }
    }

    override fun stop() {
        started = false
    }

    fun emit(signal: CarSignal) {
        _snapshot.update { it.apply(signal) }
        _signals.tryEmit(signal)
    }

    /** 신호를 내지 않고 값만 바꾼다(데모 시나리오 초기화: 할 일은 컨트롤러가 따로 비운다). */
    fun reset(snapshot: VehicleSnapshot) {
        _snapshot.value = snapshot
    }

    fun telltale(type: TelltaleType, state: TelltaleState = TelltaleState.ON) = emit(CarSignal.Telltale(type, state))

    fun range(km: Double) = emit(CarSignal.RemainingRange(km))

    fun gear(gear: Gear) = emit(CarSignal.GearChanged(gear))

    fun odometer(km: Double) = emit(CarSignal.Odometer(km))
}
