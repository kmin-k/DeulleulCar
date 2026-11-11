package ai.deulleulcar.core.navi

import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.route.GeoPoint
import java.time.Instant

/** NaviHelper 경유지 슬롯. 내비는 경유지를 최대 2개까지만 받는다. */
enum class WaypointSlot { FIRST, SECOND }

/**
 * 상태 머신이 내는 명령. 실제 NaviHelper·TTS 호출은 Android 어댑터(T13)가 한다.
 * 어댑터는 리스트 순서대로 실행한다.
 */
sealed interface NaviCommand {
    /** `requestRouteWithWaypoints(destination, waypoints)`. [waypoints]는 0~2개. */
    data class RequestRoute(val destination: GeoPoint, val waypoints: List<Stop>) : NaviCommand {
        init {
            require(waypoints.size <= RollingWaypointMachine.MAX_WAYPOINTS)
        }
    }

    /** `addWaypoint(slot, stop)` */
    data class AddWaypoint(val slot: WaypointSlot, val stop: Stop) : NaviCommand

    /** 슬롯 하나 비우기. */
    data class RemoveWaypoint(val slot: WaypointSlot) : NaviCommand

    /** 경유지 전부 비우기(목적지는 유지). */
    data object ClearWaypoints : NaviCommand

    /** TTS 한 줄. */
    data class Speak(val text: String) : NaviCommand
}

/** 상태 머신 입력. NaviHelper·FusedLocation 콜백을 어댑터가 이 형태로 바꿔 넣는다. */
sealed interface NaviEvent {
    /** 안내 시작(또는 취소 뒤 재개). */
    data object Start : NaviEvent

    /**
     * `onWayPointArrived`. 내비가 어느 경유지인지 알려 주면 [placeId], 슬롯만 알려 주면 [slot]을 채운다.
     * 둘 다 없으면 현재 FIRST 슬롯 정류지에 도착한 것으로 본다.
     */
    data class WaypointArrived(val placeId: String? = null, val slot: WaypointSlot? = null) : NaviEvent

    /**
     * `onRouteStateInfo` 재탐색 완료. [waypointPlaceIds]는 재탐색 뒤 내비가 들고 있는 경유지(알 수 있을 때만).
     * null이면 내비 상태를 알 수 없으므로 아무것도 하지 않는다(재요청 → 재탐색 → 재요청 무한 반복 방지).
     */
    data class RouteRecalculated(val waypointPlaceIds: List<String>? = null) : NaviEvent

    /** 사용자가 내비 안내를 취소했다. 다시 [Start]가 오면 남은 정류지로 이어 간다. */
    data object RouteCancelled : NaviEvent

    /** `addProximityAlert` 반경 진입. */
    data class ProximityEntered(val placeId: String, val at: Instant) : NaviEvent

    /** 반경 이탈. 진입 후 [ProximityPolicy.minDwell] 이상 머물렀으면 도착으로 본다(도착 이벤트 누락 대비). */
    data class ProximityExited(val placeId: String, val at: Instant) : NaviEvent

    /**
     * 남은 정류지 중간에 하나 끼워 넣기. [position]은 남은 정류지 기준(0 = 바로 다음).
     * 범위를 넘으면 맨 뒤에 붙인다.
     */
    data class StopInserted(val stop: Stop, val position: Int) : NaviEvent

    /** 남은 정류지를 통째로 바꾸기(차의 할 일 수락 후 순서 재계산 결과 등). 이미 들른 곳은 무시된다. */
    data class RemainingReplaced(val stops: List<Stop>) : NaviEvent

    /** `onDestinationArrived` */
    data object DestinationArrived : NaviEvent
}
