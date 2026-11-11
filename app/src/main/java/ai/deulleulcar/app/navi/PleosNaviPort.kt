package ai.deulleulcar.app.navi

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.navi.NaviCommand
import ai.deulleulcar.core.navi.NaviEvent
import ai.deulleulcar.core.navi.WaypointSlot
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos NaviHelper 3.0.5 구현.
 *
 * - 명령 → API: RequestRoute → `requestRouteWithWaypoints`(경유지 0개면 `requestRoute`), AddWaypoint → `addWaypoint`,
 *   RemoveWaypoint → `removeWaypoint(FIRST|SECOND)`, ClearWaypoints → `removeWaypoint(ALL)`.
 *   경유지의 `poiId`에는 앱의 place id를 넣어, `onWayPointInfo`로 돌아온 경유지를 계획과 맞춘다.
 * - 이벤트 → NaviEvent: `onWayPointArrived` → WaypointArrived(슬롯·place id), `onDestinationArrived` → DestinationArrived,
 *   `onRouteCancelled` → RouteCancelled, `onReRouteEnded` → `getWaypointInfo()` 응답을 기다려 RouteRecalculated.
 * - 안내 중에는 [TBT_POLL_MS]마다 `getTBTInfo()`를 불러 [nextTurn]을 갱신한다(푸시만으로 오는지 미확인이라 폴링 병행).
 *
 * 모든 상태 변경은 메인 스레드에서 한다.
 */
class PleosNaviPort(context: Context) : NaviPort {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(FeatureState.UNKNOWN)
    override val state: StateFlow<FeatureState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NaviPortEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<NaviPortEvent> = _events.asSharedFlow()

    private val _nextTurn = MutableStateFlow<TurnInfo?>(null)
    override val nextTurn: StateFlow<TurnInfo?> = _nextTurn.asStateFlow()

    override var onSpeak: ((String) -> Unit)? = null

    private var navi: NaviHelper? = null
    private var connected = false
    private val pending = ArrayDeque<NaviCommand>()

    /** 내비에 들어가 있다고 믿는 경유지(앱이 보낸 명령 기준). */
    private val slots = arrayOfNulls<Stop>(2)
    private var routing = false
    private var awaitingRecalcInfo = false

    private val tbtPoll = object : Runnable {
        override fun run() {
            if (!routing) return
            runCatching { navi?.getTBTInfo() }
            main.postDelayed(this, TBT_POLL_MS)
        }
    }
    private val recalcTimeout = Runnable {
        if (awaitingRecalcInfo) {
            awaitingRecalcInfo = false
            Log.i(TAG, "재탐색 후 경유지 정보 없음 → RouteRecalculated(null)")
            emit(NaviPortEvent.Machine(NaviEvent.RouteRecalculated(null)))
        }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = onMain {
            Log.i(TAG, "onRouteStarted: $routeStartInfo")
            setRouting(true)
            emit(NaviPortEvent.RouteStarted(routeStartInfo.name, routeStartInfo.distance, routeStartInfo.duration))
        }

        override fun onRoutingCompleted() = onMain {
            Log.i(TAG, "onRoutingCompleted")
            emit(NaviPortEvent.RoutingCompleted)
        }

        override fun onRouteEnded() = onMain {
            Log.i(TAG, "onRouteEnded")
            setRouting(false)
            emit(NaviPortEvent.RouteEnded)
        }

        override fun onReRouteEnded() = onMain {
            Log.i(TAG, "onReRouteEnded → getWaypointInfo")
            awaitingRecalcInfo = true
            main.removeCallbacks(recalcTimeout)
            main.postDelayed(recalcTimeout, RECALC_INFO_TIMEOUT_MS)
            runCatching { navi?.getWaypointInfo() }.onFailure { main.post(recalcTimeout) }
        }

        override fun onRouteCancelled() = onMain {
            Log.i(TAG, "onRouteCancelled")
            setRouting(false)
            emit(NaviPortEvent.Machine(NaviEvent.RouteCancelled))
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = onMain {
            Log.i(TAG, "onWaypointChanged: $waypointChangedInfo")
            emit(
                NaviPortEvent.WaypointChanged(
                    slotOf(waypointChangedInfo.index),
                    added = waypointChangedInfo.cmdType == WaypointCmdType.ADD,
                ),
            )
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = onMain {
            Log.i(TAG, "onWayPointArrived: $waypointArrivedInfo")
            val slot = slotOf(waypointArrivedInfo.index)
            val placeId = matchArrived(waypointArrivedInfo, slot)
            emit(NaviPortEvent.Machine(NaviEvent.WaypointArrived(placeId = placeId, slot = slot)))
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = onMain {
            Log.i(TAG, "onDestinationArrived: $destinationArrivedInfo")
            setRouting(false)
            slots.fill(null)
            emit(NaviPortEvent.Machine(NaviEvent.DestinationArrived))
        }

        override fun onError(errorCode: NaviErrorCode) = onMain {
            Log.w(TAG, "onError: $errorCode")
            emit(NaviPortEvent.Error(errorCode.name))
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = onMain {
            Log.i(TAG, "onRouteStateInfo: $routeStateInfo")
            setRouting(routeStateInfo.isRouting)
        }

        override fun onWayPointInfo(list: List<WaypointInfo>) = onMain {
            Log.i(TAG, "onWayPointInfo: $list")
            val reported = list.map { it.toReported() }
            emit(NaviPortEvent.WaypointsReported(reported))
            if (awaitingRecalcInfo) {
                awaitingRecalcInfo = false
                main.removeCallbacks(recalcTimeout)
                val ids = reported.map { it.matchedPlaceId ?: it.poiId ?: "unknown:${it.name}" }
                emit(NaviPortEvent.Machine(NaviEvent.RouteRecalculated(ids)))
            }
        }

        override fun onNavigationStatus(navigationStatus: NavigationStatus) = onMain {
            Log.i(TAG, "onNavigationStatus: $navigationStatus")
            when (navigationStatus) {
                NavigationStatus.ROUTE_DRIVE -> setRouting(true)
                NavigationStatus.FREE_DRIVE, NavigationStatus.INACTIVE -> setRouting(false)
                else -> Unit
            }
        }

        override fun onTBTInfo(list: List<TBTInfo>) = onMain {
            val first = list.firstOrNull()
            val turn = first?.let { TurnInfo(it.distance, it.type?.name, it.description) }
            if (turn != _nextTurn.value) Log.d(TAG, "onTBTInfo: $turn (${list.size}개)")
            _nextTurn.value = if (routing) turn else null
        }
    }

    override fun start() = onMain {
        if (navi != null) return@onMain
        val helper = try {
            NaviHelper(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper 생성 실패", e)
            _state.value = FeatureState.FAILED
            return@onMain
        }
        navi = helper
        helper.initialize({
            onMain {
                if (navi !== helper) return@onMain
                connected = true
                helper.addListener(listener)
                _state.value = FeatureState.ACTIVE
                Log.i(TAG, "NaviHelper 연결됨, 대기 명령 ${pending.size}개 실행")
                runCatching { helper.getRouteStateInfo() }
                flushPending()
            }
        }, { t ->
            onMain {
                Log.w(TAG, "NaviHelper 연결 실패", t)
                _state.value = if (t is SecurityException) FeatureState.PERMISSION_DENIED else FeatureState.FAILED
            }
        })
    }

    override fun execute(commands: List<NaviCommand>) = onMain {
        pending.addAll(commands)
        if (connected) flushPending() else Log.i(TAG, "연결 전, 명령 ${commands.size}개 대기")
    }

    override fun requestWaypointInfo() = onMain { if (connected) runCatching { navi?.getWaypointInfo() } }

    override fun cancelRoute() = onMain { if (connected) runCatching { navi?.cancelRoute() } }

    override fun release() = onMain {
        val helper = navi ?: return@onMain
        setRouting(false)
        main.removeCallbacks(recalcTimeout)
        pending.clear()
        navi = null
        if (connected) runCatching { helper.removeListener(listener) }
        connected = false
        runCatching { helper.release() }
        _state.value = FeatureState.UNKNOWN
        Log.i(TAG, "NaviHelper release")
    }

    // ---- 명령 실행 ----

    private fun flushPending() {
        val helper = navi ?: return
        while (pending.isNotEmpty()) {
            val cmd = pending.removeFirst()
            try {
                run(helper, cmd)
            } catch (e: Exception) {
                Log.w(TAG, "명령 실패: $cmd", e)
                emit(NaviPortEvent.Error("${e.javaClass.simpleName}: ${e.message}"))
            }
        }
    }

    private fun run(helper: NaviHelper, cmd: NaviCommand) {
        Log.i(TAG, "execute: ${cmd.describe()}")
        when (cmd) {
            is NaviCommand.RequestRoute -> {
                slots.fill(null)
                cmd.waypoints.forEachIndexed { i, s -> slots[i] = s }
                val destination = cmd.destination.toRouteInfo()
                if (cmd.waypoints.isEmpty()) {
                    helper.requestRoute(destination)
                } else {
                    val waypoints = cmd.waypoints.mapIndexed { i, s -> s.toRequest(WaypointSlot.entries[i]) }
                    helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination, waypoints))
                }
            }
            is NaviCommand.AddWaypoint -> {
                slots[cmd.slot.ordinal] = cmd.stop
                helper.addWaypoint(cmd.stop.toRequest(cmd.slot))
            }
            is NaviCommand.RemoveWaypoint -> {
                slots[cmd.slot.ordinal] = null
                helper.removeWaypoint(cmd.slot.toIndex())
            }
            NaviCommand.ClearWaypoints -> {
                slots.fill(null)
                helper.removeWaypoint(WaypointIndex.ALL)
            }
            is NaviCommand.Speak -> onSpeak?.invoke(cmd.text) ?: Log.w(TAG, "onSpeak 미연결: ${cmd.text}")
        }
    }

    // ---- 변환 ----

    private fun GeoPoint.toRouteInfo() = RouteInfo(
        latitude = lat,
        longitude = lon,
        displayLatitude = null,
        displayLongitude = null,
        poiName = name ?: "목적지",
        poiId = "",
        address = "",
        poiSubId = "",
        routeOption = RouteOption.RECOMMENDED,
        poiProvider = "",
        isMobileRequest = false,
    )

    private fun Stop.toRequest(slot: WaypointSlot) = RequestWaypointInfo(
        latitude = place.lat,
        longitude = place.lon,
        poiName = place.name,
        waypointIndex = slot.toIndex(),
        poiId = place.id,
        address = place.address ?: "",
        poiSubId = "",
        poiProvider = "",
    )

    private fun WaypointSlot.toIndex() = when (this) {
        WaypointSlot.FIRST -> WaypointIndex.FIRST
        WaypointSlot.SECOND -> WaypointIndex.SECOND
    }

    private fun slotOf(index: WaypointIndex?): WaypointSlot? = when (index) {
        WaypointIndex.FIRST -> WaypointSlot.FIRST
        WaypointIndex.SECOND -> WaypointSlot.SECOND
        else -> null
    }

    /** 도착한 경유지를 이름 → 슬롯 순으로 계획 정류지와 맞춘다. 못 찾으면 null(상태 머신이 슬롯/FIRST로 판단). */
    private fun matchArrived(info: WaypointArrivedInfo?, slot: WaypointSlot?): String? {
        val known = slots.filterNotNull()
        info?.name?.takeIf { it.isNotBlank() }?.let { name -> known.firstOrNull { it.place.name == name }?.let { return it.place.id } }
        return slot?.let { slots[it.ordinal]?.place?.id }
    }

    private fun WaypointInfo.toReported(): ReportedWaypoint {
        val known = slots.filterNotNull()
        val p = GeoPoint(latitude, longitude)
        val matched = known.firstOrNull { it.place.id == poiId }
            ?: known.firstOrNull { it.place.point.distanceTo(p) <= MATCH_RADIUS_M }
            ?: known.firstOrNull { it.place.name == name }
        return ReportedWaypoint(name, latitude, longitude, poiId, matched?.place?.id, remainDistance, remainTime)
    }

    private fun NaviCommand.describe(): String = when (this) {
        is NaviCommand.RequestRoute -> "RequestRoute(dest=${destination.name}, waypoints=${waypoints.map { it.place.name }})"
        is NaviCommand.AddWaypoint -> "AddWaypoint($slot, ${stop.place.name})"
        is NaviCommand.RemoveWaypoint -> "RemoveWaypoint($slot)"
        NaviCommand.ClearWaypoints -> "ClearWaypoints"
        is NaviCommand.Speak -> "Speak($text)"
    }

    private fun setRouting(value: Boolean) {
        if (routing == value) return
        routing = value
        main.removeCallbacks(tbtPoll)
        if (value) main.post(tbtPoll) else _nextTurn.value = null
    }

    private fun emit(event: NaviPortEvent) {
        _events.tryEmit(event)
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    companion object {
        const val TAG = "DC/Navi"
        private const val TBT_POLL_MS = 2_000L
        private const val RECALC_INFO_TIMEOUT_MS = 3_000L
        private const val MATCH_RADIUS_M = 50.0
    }
}
