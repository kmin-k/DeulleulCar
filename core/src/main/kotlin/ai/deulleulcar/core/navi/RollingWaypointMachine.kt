package ai.deulleulcar.core.navi

import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.route.GeoPoint
import ai.deulleulcar.core.route.Josa
import ai.deulleulcar.core.route.RouteSpeech
import java.time.Duration
import java.time.Instant

/**
 * 근접 알림으로 도착을 보조 판정하는 기준.
 * @property minDwell 반경 진입 → 이탈 사이 이만큼 머물렀으면 도착으로 본다(그냥 지나친 것과 구분).
 * @property arriveOnEnter true면 진입만으로 도착 처리(반경을 아주 작게 잡았을 때).
 */
data class ProximityPolicy(val minDwell: Duration = Duration.ofMinutes(1), val arriveOnEnter: Boolean = false)

enum class GuidanceStatus {
    /** [NaviEvent.Start] 전. */
    IDLE,
    GUIDING,

    /** 사용자가 안내를 취소함. [NaviEvent.Start]로 재개. */
    SUSPENDED,

    /** 목적지 도착. 이후 이벤트는 무시. */
    FINISHED,
}

/**
 * 상태 머신 스냅샷. UI(주행 미니 카드 "다음 들를 곳 1/3")가 읽는다.
 * @property exposed 지금 내비에 들어가 있다고 믿는 경유지(최대 2곳)
 */
data class RollingState(
    val status: GuidanceStatus,
    val visited: List<Stop>,
    val skipped: List<Stop>,
    val remaining: List<Stop>,
    val exposed: List<Stop>,
) {
    val total: Int get() = visited.size + skipped.size + remaining.size
    val next: Stop? get() = remaining.firstOrNull()
}

/**
 * 롤링 경유지 상태 머신 (spec 결정사항, 기획서 5-3(a)).
 *
 * NaviHelper는 경유지를 최대 2개만 받으므로, 전체 정류지는 앱이 들고 있고 내비에는 항상 "남은 정류지 중 앞 2곳"만 넣는다.
 * 순수 상태 머신이다: [handle]에 이벤트를 넣으면 실행할 [NaviCommand] 목록이 나온다. 실제 SDK 호출은 어댑터가 한다.
 *
 * - 도착 판정은 idempotent: 이미 들렀거나 건너뛴 곳의 도착 이벤트, 모르는 장소는 무시한다.
 * - 뒤쪽 정류지에 먼저 도착하면 그 앞의 남은 정류지는 건너뛴 것으로 본다([RollingState.skipped]).
 * - 도착 이벤트가 안 오는 경우를 대비해 근접 알림 진입 → 이탈([ProximityPolicy])도 도착으로 본다.
 * - 경유지 반영 방식은 [SlotSyncStrategy]로 바꿀 수 있다. 기본은 내비 내부 동작에 덜 기대는 재요청 방식.
 *
 * 정류지는 장소 id로 구분하므로 한 계획 안에서 place.id가 유일해야 한다.
 * 스레드 안전하지 않음: 한 스레드(또는 직렬화된 코루틴)에서만 호출한다.
 */
class RollingWaypointMachine(
    private val destination: GeoPoint,
    stops: List<Stop>,
    private val strategy: SlotSyncStrategy = SlotSyncStrategy.ReRequestRoute,
    private val proximity: ProximityPolicy = ProximityPolicy(),
    private val nameOf: (Stop) -> String = RouteSpeech::nameOf,
) {
    private val remaining = stops.toMutableList()
    private val visited = mutableListOf<Stop>()
    private val skipped = mutableListOf<Stop>()
    private var exposed: List<Stop> = emptyList()
    private var status = GuidanceStatus.IDLE
    private val enteredAt = mutableMapOf<String, Instant>()

    init {
        require(stops.map { it.place.id }.toSet().size == stops.size) { "정류지 장소 id가 중복됩니다" }
    }

    val state: RollingState
        get() = RollingState(status, visited.toList(), skipped.toList(), remaining.toList(), exposed)

    fun handle(event: NaviEvent): List<NaviCommand> {
        if (status == GuidanceStatus.FINISHED) return emptyList()
        return when (event) {
            NaviEvent.Start -> start()
            is NaviEvent.WaypointArrived -> if (status == GuidanceStatus.GUIDING) arrivedAt(resolve(event)) else emptyList()
            is NaviEvent.RouteRecalculated -> recalculated(event)
            NaviEvent.RouteCancelled -> cancelled()
            is NaviEvent.ProximityEntered -> proximityEntered(event)
            is NaviEvent.ProximityExited -> proximityExited(event)
            is NaviEvent.StopInserted -> insert(event)
            is NaviEvent.RemainingReplaced -> replace(event.stops)
            NaviEvent.DestinationArrived -> finish()
        }
    }

    private fun start(): List<NaviCommand> {
        if (status == GuidanceStatus.GUIDING) return emptyList()
        status = GuidanceStatus.GUIDING
        exposed = nextTwo()
        // 처음(또는 재개) 요청은 어떤 전략이든 경로 요청으로 시작한다.
        return listOf(NaviCommand.RequestRoute(destination, exposed))
    }

    private fun resolve(event: NaviEvent.WaypointArrived): String? = when {
        event.placeId != null -> event.placeId
        event.slot != null -> exposed.getOrNull(event.slot.ordinal)?.place?.id
        else -> exposed.firstOrNull()?.place?.id
    }

    private fun arrivedAt(placeId: String?): List<NaviCommand> {
        val index = remaining.indexOfFirst { it.place.id == placeId }
        if (index < 0) return emptyList() // 중복·모르는 도착 이벤트
        val arrived = remaining[index]
        repeat(index) { skipped += remaining.removeAt(0) }
        remaining.removeAt(0)
        visited += arrived
        enteredAt.remove(arrived.place.id)
        return sync(SyncCause.ARRIVAL) + NaviCommand.Speak(arrivalSpeech(arrived))
    }

    private fun recalculated(event: NaviEvent.RouteRecalculated): List<NaviCommand> {
        if (status != GuidanceStatus.GUIDING) return emptyList()
        val navi = event.waypointPlaceIds ?: return emptyList()
        if (navi == exposed.map { it.place.id }) return emptyList()
        return strategy.sync(destination, emptyList(), exposed, SyncCause.RESYNC)
    }

    private fun cancelled(): List<NaviCommand> {
        if (status == GuidanceStatus.GUIDING) {
            status = GuidanceStatus.SUSPENDED
            exposed = emptyList()
        }
        return emptyList()
    }

    private fun proximityEntered(event: NaviEvent.ProximityEntered): List<NaviCommand> {
        if (remaining.none { it.place.id == event.placeId }) return emptyList()
        if (proximity.arriveOnEnter && status == GuidanceStatus.GUIDING) return arrivedAt(event.placeId)
        enteredAt.putIfAbsent(event.placeId, event.at)
        return emptyList()
    }

    private fun proximityExited(event: NaviEvent.ProximityExited): List<NaviCommand> {
        val entered = enteredAt.remove(event.placeId) ?: return emptyList()
        if (status != GuidanceStatus.GUIDING) return emptyList()
        if (Duration.between(entered, event.at) < proximity.minDwell) return emptyList() // 그냥 지나침
        return arrivedAt(event.placeId)
    }

    private fun insert(event: NaviEvent.StopInserted): List<NaviCommand> {
        val id = event.stop.place.id
        if (remaining.any { it.place.id == id }) return emptyList()
        visited.removeAll { it.place.id == id } // 다시 들르기로 한 곳
        skipped.removeAll { it.place.id == id }
        remaining.add(event.position.coerceIn(0, remaining.size), event.stop)
        return syncIfChanged()
    }

    private fun replace(stops: List<Stop>): List<NaviCommand> {
        val done = visited.map { it.place.id }.toSet()
        val next = stops.filter { it.place.id !in done }.distinctBy { it.place.id }
        remaining.clear()
        remaining += next
        skipped.removeAll { s -> next.any { it.place.id == s.place.id } }
        enteredAt.keys.retainAll(next.map { it.place.id }.toSet())
        return syncIfChanged()
    }

    private fun finish(): List<NaviCommand> {
        val missed = remaining.toList()
        skipped += missed
        remaining.clear()
        exposed = emptyList()
        enteredAt.clear()
        status = GuidanceStatus.FINISHED
        val text = buildString {
            append("목적지에 도착했어요.")
            if (missed.isNotEmpty()) {
                append(' ').append(Josa.eunNeun(missed.joinToString(", ") { nameOf(it) })).append(" 다음에 들러요.")
            }
        }
        return listOf(NaviCommand.Speak(text))
    }

    private fun syncIfChanged(): List<NaviCommand> {
        if (status != GuidanceStatus.GUIDING) return emptyList()
        if (nextTwo() == exposed) return emptyList()
        return sync(SyncCause.PLAN_CHANGED)
    }

    private fun sync(cause: SyncCause): List<NaviCommand> {
        val before = exposed
        exposed = nextTwo()
        return strategy.sync(destination, before, exposed, cause)
    }

    private fun nextTwo(): List<Stop> = remaining.take(MAX_WAYPOINTS)

    /** "세탁소 도착했어요. 다음은 약국, 그다음 마트예요." */
    private fun arrivalSpeech(arrived: Stop): String {
        val head = "${nameOf(arrived)} 도착했어요."
        val next = remaining.take(MAX_WAYPOINTS).map(nameOf)
        val tail = when (next.size) {
            0 -> "이제 ${destination.name?.let(Josa::euroRo) ?: "목적지로"} 갈게요."
            1 -> "다음은 ${Josa.ieyo(next[0])}."
            else -> "다음은 ${next[0]}, 그다음 ${Josa.ieyo(next[1])}."
        }
        return "$head $tail"
    }

    companion object {
        const val MAX_WAYPOINTS = 2
    }
}
