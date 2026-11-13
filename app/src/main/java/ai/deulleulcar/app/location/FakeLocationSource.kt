package ai.deulleulcar.app.location

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.model.GeoPoint
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 데모·테스트용 위치. [moveTo]로 위치를 바꾸면 등록된 근접 알림의 진입·이탈을 계산해 낸다. */
class FakeLocationSource(initial: GeoPoint? = null) : LocationSource {
    private val _state = MutableStateFlow(FeatureState.UNKNOWN)
    override val state: StateFlow<FeatureState> = _state.asStateFlow()
    private val _location = MutableStateFlow(initial?.let { LocationFix(it, 0f, 1f, 0L) })
    override val location: StateFlow<LocationFix?> = _location.asStateFlow()
    private val _proximity = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 32)
    override val proximity: SharedFlow<ProximityEvent> = _proximity.asSharedFlow()

    private data class Alert(val point: GeoPoint, val radiusM: Float, var inside: Boolean)

    private val alerts = mutableMapOf<String, Alert>()

    override fun start() {
        _state.value = FeatureState.ACTIVE
    }

    @Synchronized
    fun moveTo(point: GeoPoint, at: Instant = Instant.now()) {
        _location.value = LocationFix(point, 0f, 1f, at.toEpochMilli())
        for ((id, a) in alerts) {
            val inside = point.distanceTo(a.point) <= a.radiusM
            if (inside != a.inside) {
                a.inside = inside
                _proximity.tryEmit(ProximityEvent(id, inside, at))
            }
        }
    }

    /** 근접 알림을 모두 지우고 [point]로 옮긴다(데모 시나리오 초기화). */
    @Synchronized
    fun reset(point: GeoPoint) {
        alerts.clear()
        _location.value = LocationFix(point, 0f, 1f, Instant.now().toEpochMilli())
    }

    @Synchronized
    override fun addProximityAlert(placeId: String, point: GeoPoint, radiusM: Float) {
        val inside = _location.value?.point?.let { it.distanceTo(point) <= radiusM } ?: false
        alerts[placeId] = Alert(point, radiusM, inside)
    }

    @Synchronized
    override fun removeProximityAlert(placeId: String) {
        alerts.remove(placeId)
    }

    @Synchronized
    override fun removeAllProximityAlerts() = alerts.clear()

    override fun release() = Unit
}
