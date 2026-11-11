package ai.deulleulcar.core.navi

import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.model.GeoPoint

/** 경유지를 다시 맞추는 이유. */
enum class SyncCause {
    /** FIRST 슬롯 정류지에 도착해서 한 칸씩 당겨졌다. */
    ARRIVAL,

    /** 재탐색 뒤 내비 경유지가 앱 계획과 다르다. */
    RESYNC,

    /** 정류지 삽입·교체로 앞쪽 2곳이 바뀌었다. */
    PLAN_CHANGED,
}

/**
 * 앱이 원하는 "다음 2곳"([after])을 내비에 반영하는 방법.
 *
 * ⚠ T13에서 에뮬레이터로 확인할 점 (NaviHelper 실제 동작 미확인):
 * 1. `onWayPointArrived` 뒤 내비가 도착한 경유지를 스스로 지우는지, SECOND가 FIRST로 당겨지는지
 * 2. `addWaypoint`로 이미 찬 슬롯에 넣으면 덮어쓰는지, 실패하는지
 * 3. 경유지 삭제 API(슬롯별/전체)가 있는지, `requestRouteWithWaypoints` 재요청이 안내 중에도 매끄러운지
 * 4. `onRouteStateInfo`에서 현재 경유지 목록을 얻을 수 있는지 (없으면 [NaviEvent.RouteRecalculated]에 null)
 *
 * 확인 전 기본값은 내비 내부 동작에 기대지 않는 [ReRequestRoute]다.
 * 확인 결과에 따라 [ClearAndAdd]나 [Incremental]로 바꾸면 된다(상태 머신은 그대로).
 */
fun interface SlotSyncStrategy {
    /**
     * @param before 내비가 지금 들고 있다고 앱이 믿는 경유지
     * @param after 새로 노출할 경유지(0~2개)
     */
    fun sync(destination: GeoPoint, before: List<Stop>, after: List<Stop>, cause: SyncCause): List<NaviCommand>

    /** 매번 `requestRouteWithWaypoints(목적지, 다음 2곳)`을 새로 요청한다. 내비 슬롯 동작과 무관하게 맞는다. */
    data object ReRequestRoute : SlotSyncStrategy {
        override fun sync(destination: GeoPoint, before: List<Stop>, after: List<Stop>, cause: SyncCause) =
            listOf(NaviCommand.RequestRoute(destination, after))
    }

    /** 경유지를 모두 지우고 다음 2곳을 FIRST, SECOND 순서로 다시 넣는다. 삭제 API가 있을 때 쓴다. */
    data object ClearAndAdd : SlotSyncStrategy {
        override fun sync(destination: GeoPoint, before: List<Stop>, after: List<Stop>, cause: SyncCause) =
            listOf<NaviCommand>(NaviCommand.ClearWaypoints) + addAll(after)
    }

    /**
     * 도착하면 내비가 FIRST를 지우고 SECOND를 FIRST로 당긴다고 가정하고, 빈 SECOND만 채운다.
     * 가정이 맞지 않는 상황(도착이 아닌 변경, 건너뛴 정류지 등)은 [ClearAndAdd]로 처리한다.
     */
    data object Incremental : SlotSyncStrategy {
        override fun sync(destination: GeoPoint, before: List<Stop>, after: List<Stop>, cause: SyncCause): List<NaviCommand> {
            val shifted = before.drop(1)
            val simpleShift = cause == SyncCause.ARRIVAL && after.take(shifted.size) == shifted
            if (!simpleShift) return ClearAndAdd.sync(destination, before, after, cause)
            return after.drop(shifted.size).mapIndexed { i, stop ->
                NaviCommand.AddWaypoint(WaypointSlot.entries[shifted.size + i], stop)
            }
        }
    }

    companion object {
        internal fun addAll(stops: List<Stop>) =
            stops.mapIndexed { i, s -> NaviCommand.AddWaypoint(WaypointSlot.entries[i], s) }
    }
}
