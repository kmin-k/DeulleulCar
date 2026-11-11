package ai.deulleulcar.app.navi

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.navi.NaviCommand
import ai.deulleulcar.core.navi.NaviEvent
import ai.deulleulcar.core.navi.WaypointSlot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 데모·테스트용 내비. 받은 명령을 [executed]에 기록하고, 경로 요청이 오면 바로 RouteStarted를 낸다.
 * [arriveNext]·[arriveDestination]·[setTurnDistance]로 주행을 흉내 낸다.
 */
class FakeNaviPort : NaviPort {
    private val _state = MutableStateFlow(FeatureState.UNKNOWN)
    override val state: StateFlow<FeatureState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<NaviPortEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<NaviPortEvent> = _events.asSharedFlow()
    private val _nextTurn = MutableStateFlow<TurnInfo?>(null)
    override val nextTurn: StateFlow<TurnInfo?> = _nextTurn.asStateFlow()
    override var onSpeak: ((String) -> Unit)? = null

    val executed = mutableListOf<NaviCommand>()
    private val slots = arrayOfNulls<Stop>(2)

    override fun start() {
        _state.value = FeatureState.ACTIVE
    }

    @Synchronized
    override fun execute(commands: List<NaviCommand>) {
        for (cmd in commands) {
            executed += cmd
            when (cmd) {
                is NaviCommand.RequestRoute -> {
                    slots.fill(null)
                    cmd.waypoints.forEachIndexed { i, s -> slots[i] = s }
                    _events.tryEmit(NaviPortEvent.RouteStarted(cmd.destination.name, 0, 0))
                    _events.tryEmit(NaviPortEvent.RoutingCompleted)
                }
                is NaviCommand.AddWaypoint -> slots[cmd.slot.ordinal] = cmd.stop
                is NaviCommand.RemoveWaypoint -> slots[cmd.slot.ordinal] = null
                NaviCommand.ClearWaypoints -> slots.fill(null)
                is NaviCommand.Speak -> onSpeak?.invoke(cmd.text)
            }
        }
    }

    /** FIRST 슬롯 경유지에 도착한 것처럼 이벤트를 낸다. */
    @Synchronized
    fun arriveNext() {
        val stop = slots[0] ?: return
        _events.tryEmit(NaviPortEvent.Machine(NaviEvent.WaypointArrived(placeId = stop.place.id, slot = WaypointSlot.FIRST)))
    }

    fun arriveDestination() {
        _nextTurn.value = null
        _events.tryEmit(NaviPortEvent.Machine(NaviEvent.DestinationArrived))
    }

    fun setTurnDistance(m: Double?) {
        _nextTurn.value = m?.let { TurnInfo(it, null, null) }
    }

    override fun requestWaypointInfo() {
        val reported = slots.filterNotNull().map {
            ReportedWaypoint(it.place.name, it.place.lat, it.place.lon, it.place.id, it.place.id, 0, 0)
        }
        _events.tryEmit(NaviPortEvent.WaypointsReported(reported))
    }

    override fun cancelRoute() {
        _nextTurn.value = null
        _events.tryEmit(NaviPortEvent.Machine(NaviEvent.RouteCancelled))
    }

    override fun release() = Unit
}
