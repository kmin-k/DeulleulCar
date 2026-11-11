package ai.deulleulcar.app.navi

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.navi.NaviCommand
import ai.deulleulcar.core.navi.NaviEvent
import ai.deulleulcar.core.navi.WaypointSlot
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 내비가 들고 있다고 알려 준 경유지 하나(`onWayPointInfo`). */
data class ReportedWaypoint(
    val name: String?,
    val lat: Double,
    val lon: Double,
    val poiId: String?,
    /** 앱 계획의 정류지와 맞춰 본 place id. 못 찾으면 null. */
    val matchedPlaceId: String?,
    val remainDistanceM: Int,
    val remainTimeSec: Int,
)

/** 내비 쪽 이벤트. [Machine]은 RollingWaypointMachine에 그대로 넣고, 나머지는 UI·로그용. */
sealed interface NaviPortEvent {
    data class Machine(val event: NaviEvent) : NaviPortEvent

    data class RouteStarted(val name: String?, val distanceM: Int, val durationSec: Int) : NaviPortEvent

    data object RoutingCompleted : NaviPortEvent

    data object RouteEnded : NaviPortEvent

    data class WaypointChanged(val slot: WaypointSlot?, val added: Boolean) : NaviPortEvent

    data class WaypointsReported(val waypoints: List<ReportedWaypoint>) : NaviPortEvent

    data class Error(val code: String) : NaviPortEvent
}

/** 다음 회전 안내(TBT). */
data class TurnInfo(val distanceM: Double, val turnType: String?, val description: String?)

/**
 * NaviHelper 포트. RollingWaypointMachine이 낸 [NaviCommand]를 실제 내비 호출로 실행하고,
 * 내비 콜백을 [NaviPortEvent]로 바꿔 내보낸다.
 *
 * [NaviCommand.Speak]는 내비가 아니라 [onSpeak]로 넘긴다(AppContainer가 VoiceAnnouncer에 연결).
 */
interface NaviPort {
    val state: StateFlow<FeatureState>

    val events: SharedFlow<NaviPortEvent>

    /** 다음 회전 정보. 안내 중이 아니면 null. VoiceTurnScheduler 입력으로 쓴다. */
    val nextTurn: StateFlow<TurnInfo?>

    var onSpeak: ((String) -> Unit)?

    fun start()

    /** 명령을 순서대로 실행한다. 연결 전이면 연결 뒤 실행한다. */
    fun execute(commands: List<NaviCommand>)

    /** `getWaypointInfo()` → [NaviPortEvent.WaypointsReported]. */
    fun requestWaypointInfo()

    fun cancelRoute()

    fun release()
}
