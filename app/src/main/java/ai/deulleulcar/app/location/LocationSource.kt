package ai.deulleulcar.app.location

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.navi.NaviEvent
import java.time.Instant
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 근접 알림 반경 진입·이탈. [placeId]는 [LocationSource.addProximityAlert]에 넘긴 값. */
data class ProximityEvent(val placeId: String, val entered: Boolean, val at: Instant) {
    fun toNaviEvent(): NaviEvent =
        if (entered) NaviEvent.ProximityEntered(placeId, at) else NaviEvent.ProximityExited(placeId, at)
}

/** 현재 위치 한 점. */
data class LocationFix(val point: GeoPoint, val headingDeg: Float, val accuracy: Float, val timestampMillis: Long)

/**
 * 위치 소스(Pleos FusedLocation). 현재 위치와 정류지 근접 알림을 준다.
 * 근접 알림은 도착 이벤트가 안 오는 경우를 대비한 보조 판정용이다(RollingWaypointMachine의 ProximityPolicy).
 */
interface LocationSource {
    val state: StateFlow<FeatureState>

    /** 최신 위치. 아직 모르면 null. */
    val location: StateFlow<LocationFix?>

    val proximity: SharedFlow<ProximityEvent>

    fun start()

    /** 같은 [placeId]로 다시 부르면 이전 알림을 지우고 새로 건다. */
    fun addProximityAlert(placeId: String, point: GeoPoint, radiusM: Float = DEFAULT_RADIUS_M)

    fun removeProximityAlert(placeId: String)

    fun removeAllProximityAlerts()

    fun release()

    companion object {
        const val DEFAULT_RADIUS_M = 150f
    }
}
