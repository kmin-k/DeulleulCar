package ai.deulleulcar.core.route

import ai.deulleulcar.core.model.Stop
import java.time.Duration
import java.time.LocalDateTime

/**
 * 정류지 순서 최적화 (spec T8).
 *
 * - 볼일마다 후보 중 한 곳을 고르고 순서를 정한다. 정류지 [RouteOptions.maxStops]개 이하 완전 탐색.
 * - 제약: 도착 예정 시각에 영업 중이어야 한다([ai.deulleulcar.core.model.OpeningHours]가 없으면 제약 없음).
 * - 목적: ① 들를 수 있는 볼일 수 최대 ② 총 소요(운전 + 체류) 최소.
 * - 다 넣을 수 없으면 일부를 빼고, 뺀 볼일은 [RoutePlan.excluded]에 이유와 함께 돌려준다.
 * - 영업시간 때문에 "영업시간을 무시한 최적 순서"와 달라졌으면 [RoutePlan.reorderReasons]로 이유를 준다.
 *
 * 계산량: 부분 경로를 모두 펼치는 DFS. 5곳 × 후보 3곳이면 약 4만 개 부분 경로로 즉시 끝난다.
 * 상태 없음, 스레드 안전.
 */
class RouteOptimizer(
    private val estimator: TravelTimeEstimator = HaversineEstimator(),
    private val options: RouteOptions = RouteOptions(),
) {

    fun optimize(request: RouteRequest): RoutePlan {
        val excluded = mutableListOf<ExcludedRequest>()
        val searchable = mutableListOf<StopRequest>()
        for (r in request.requests) {
            when {
                r.candidates.isEmpty() -> excluded += ExcludedRequest(r, ExclusionReason.NO_CANDIDATES)
                searchable.size >= options.maxStops -> excluded += ExcludedRequest(r, ExclusionReason.TOO_MANY_STOPS)
                else -> searchable += r
            }
        }
        require(searchable.map { it.id }.toSet().size == searchable.size) { "볼일 id가 중복됩니다" }

        val graph = buildGraph(request, searchable)
        val pinned = searchable.indexOfFirst { it.id == request.pinnedFirstRequestId }.takeIf { it >= 0 }
        val search = Search(graph, request.departAt, pinned, ignoreHours = false).also { it.run() }

        val fullCount = searchable.size
        val bestMask = (0 until (1 shl fullCount))
            .filter { search.best[it] != Long.MAX_VALUE }
            .sortedWith(
                // 긴급(고정) 볼일 포함 > 들르는 볼일 수 > 총 소요
                compareByDescending<Int> { m -> pinned != null && m and (1 shl pinned) != 0 }
                    .thenByDescending { Integer.bitCount(it) }
                    .thenBy { search.best[it] }
                    .thenBy { it },
            )
            .first() // 빈 경로(mask 0)는 항상 가능
        val path = search.bestPath[bestMask]!!

        for (i in searchable.indices) {
            if (bestMask and (1 shl i) != 0) continue
            val alone = search.best[1 shl i] != Long.MAX_VALUE
            excluded += ExcludedRequest(searchable[i], if (alone) ExclusionReason.CONFLICT else ExclusionReason.CLOSED)
        }
        val order = request.requests.map { it.id }
        excluded.sortBy { order.indexOf(it.request.id) }

        return buildPlan(request, graph, path, excluded, reorderReasons(graph, request.departAt, path, pinned))
    }

    /** 영업시간을 무시한 최적 순서와 비교해 순서가 바뀐 이유를 찾는다. */
    private fun reorderReasons(graph: Graph, departAt: LocalDateTime, path: IntArray, pinned: Int?): List<ReorderReason> {
        if (path.size < 2) return emptyList()
        val sub = graph.restrictTo(path)
        val relaxed = Search(sub, departAt, pinned?.let { p -> sub.requestIndexOf(graph, p) }, ignoreHours = true)
            .also { it.run() }
        val full = (1 shl sub.requestCount) - 1
        val relaxedPath = relaxed.bestPath[full]?.map { sub.originalIndex[it] } ?: return emptyList()
        if (relaxedPath == path.toList()) return emptyList()

        val relaxedArrivals = graph.arrivals(relaxedPath.toIntArray(), departAt)
        val chosenArrivals = graph.arrivals(path, departAt)
        val reasons = mutableListOf<ReorderReason>()
        for ((pos, cand) in path.withIndex()) {
            val stop = graph.cands[cand].stop
            val hours = stop.place.openingHours ?: continue
            val relaxedAt = relaxedArrivals[relaxedPath.indexOf(cand)]
            if (isOpen(stop, relaxedAt)) continue
            val closesAt = hours.closingTimeAt(chosenArrivals[pos])
            val movedEarlier = pos < relaxedPath.indexOf(cand)
            reasons += if (movedEarlier && closesAt != null) {
                ReorderReason.ClosesSoon(stop.place.id, closesAt, stop)
            } else {
                ReorderReason.OpensLater(stop.place.id, stop)
            }
        }
        return reasons
    }

    private fun buildPlan(
        request: RouteRequest,
        graph: Graph,
        path: IntArray,
        excluded: List<ExcludedRequest>,
        reasons: List<ReorderReason>,
    ): RoutePlan {
        var t = 0L
        var drive = 0L
        var dwell = 0L
        var prev = Graph.ORIGIN
        val stops = path.map { c ->
            val leg = graph.travel(prev, c)
            drive += leg
            t += leg
            val arrival = request.departAt.plusSeconds(t)
            val stay = graph.dwell[c]
            dwell += stay
            t += stay
            prev = c
            val stop = graph.cands[c].stop
            PlannedStop(
                requestId = graph.requests[graph.cands[c].request].id,
                stop = stop,
                arrival = arrival,
                departure = request.departAt.plusSeconds(t),
                closesAt = stop.place.openingHours?.closingTimeAt(arrival),
            )
        }
        val last = graph.travelToDest(prev)
        drive += last
        t += last
        return RoutePlan(
            origin = request.origin,
            destination = request.destination,
            departAt = request.departAt,
            stops = stops,
            arrivalAtDestination = request.departAt.plusSeconds(t),
            driveTime = Duration.ofSeconds(drive),
            dwellTime = Duration.ofSeconds(dwell),
            directDriveTime = Duration.ofSeconds(graph.travelToDest(Graph.ORIGIN)),
            reorderReasons = reasons,
            excluded = excluded,
        )
    }

    private fun isOpen(stop: Stop, at: LocalDateTime): Boolean {
        val hours = stop.place.openingHours ?: return true
        val close = hours.closingTimeAt(at) ?: return false
        return !at.plus(options.closingMargin).isAfter(close)
    }

    private class Cand(val request: Int, val stop: Stop)

    /**
     * 후보 정류지 그래프. 이동 시간은 미리 초 단위로 계산해 둔다.
     * 노드: [ORIGIN](-1), 후보 0..n-1. 목적지는 [travelToDest].
     */
    private class Graph(
        val requests: List<StopRequest>,
        val cands: List<Cand>,
        val fromOrigin: LongArray,
        val between: Array<LongArray>,
        val toDest: LongArray,
        val dwell: LongArray,
        val directSeconds: Long,
        /** [restrictTo]로 만든 부분 그래프의 후보 → 원래 그래프 후보 번호. */
        val originalIndex: IntArray,
    ) {
        val requestCount: Int get() = requests.size

        fun travel(from: Int, to: Int): Long = if (from == ORIGIN) fromOrigin[to] else between[from][to]

        fun travelToDest(from: Int): Long = if (from == ORIGIN) directSeconds else toDest[from]

        fun candsOf(request: Int): List<Int> = cands.indices.filter { cands[it].request == request }

        fun arrivals(path: IntArray, departAt: LocalDateTime): List<LocalDateTime> {
            var t = 0L
            var prev = ORIGIN
            return path.map { c ->
                t += travel(prev, c)
                val at = departAt.plusSeconds(t)
                t += dwell[c]
                prev = c
                at
            }
        }

        /** [path]의 후보만 남긴 그래프. 볼일 번호는 경로에 나온 볼일 순서로 다시 매긴다. */
        fun restrictTo(path: IntArray): Graph {
            val reqs = path.map { cands[it].request }
            val newCands = path.mapIndexed { i, c -> Cand(i, cands[c].stop) }
            return Graph(
                requests = reqs.map { requests[it] },
                cands = newCands,
                fromOrigin = LongArray(path.size) { fromOrigin[path[it]] },
                between = Array(path.size) { i -> LongArray(path.size) { j -> between[path[i]][path[j]] } },
                toDest = LongArray(path.size) { toDest[path[it]] },
                dwell = LongArray(path.size) { dwell[path[it]] },
                directSeconds = directSeconds,
                originalIndex = path.copyOf(),
            )
        }

        /** 원래 그래프의 볼일 번호 → 이 부분 그래프의 볼일 번호. 없으면 null. */
        fun requestIndexOf(original: Graph, originalRequest: Int): Int? =
            originalIndex.indexOfFirst { original.cands[it].request == originalRequest }.takeIf { it >= 0 }

        companion object {
            const val ORIGIN = -1
        }
    }

    private fun buildGraph(request: RouteRequest, searchable: List<StopRequest>): Graph {
        val o = request.origin
        val d = request.destination
        val direct = estimator.travelTime(o, d).seconds
        val cands = mutableListOf<Cand>()
        for ((ri, r) in searchable.withIndex()) {
            // 처음 도착 가능한 시각(직행)에 영업 중인 곳 우선, 그다음 직행 대비 우회가 작은 곳.
            r.candidates
                .sortedWith(
                    compareBy<Stop> { s ->
                        val at = request.departAt.plus(estimator.travelTime(o, s.place.point))
                        if (isOpen(s, at)) 0 else 1
                    }.thenBy { s ->
                        estimator.travelTime(o, s.place.point).seconds + estimator.travelTime(s.place.point, d).seconds
                    },
                )
                .take(options.maxCandidatesPerRequest)
                .forEach { cands += Cand(ri, it) }
        }
        val points = cands.map { it.stop.place.point }
        return Graph(
            requests = searchable,
            cands = cands,
            fromOrigin = LongArray(cands.size) { estimator.travelTime(o, points[it]).seconds },
            between = Array(cands.size) { i ->
                LongArray(cands.size) { j -> if (i == j) 0 else estimator.travelTime(points[i], points[j]).seconds }
            },
            toDest = LongArray(cands.size) { estimator.travelTime(points[it], d).seconds },
            dwell = LongArray(cands.size) { estimator.dwellTime(cands[it].stop.place).seconds },
            directSeconds = direct,
            originalIndex = IntArray(cands.size) { it },
        )
    }

    /**
     * 모든 부분 경로를 펼치며, 들른 볼일 집합(mask)마다 "지금 목적지로 가면" 총 소요가 가장 작은 경로를 기록한다.
     * @param pinned 이 볼일은 첫 정류지로만 들를 수 있다.
     */
    private inner class Search(
        private val graph: Graph,
        private val departAt: LocalDateTime,
        private val pinned: Int?,
        private val ignoreHours: Boolean,
    ) {
        val best = LongArray(1 shl graph.requestCount) { Long.MAX_VALUE }
        val bestPath = arrayOfNulls<IntArray>(1 shl graph.requestCount)
        private val candsByRequest = (0 until graph.requestCount).map { graph.candsOf(it) }
        private val path = IntArray(graph.requestCount)

        fun run() = visit(Graph.ORIGIN, 0L, 0, 0)

        private fun visit(cur: Int, elapsed: Long, mask: Int, depth: Int) {
            val total = elapsed + graph.travelToDest(cur)
            if (total < best[mask]) {
                best[mask] = total
                bestPath[mask] = path.copyOf(depth)
            }
            for (r in 0 until graph.requestCount) {
                if (mask and (1 shl r) != 0) continue
                if (pinned != null && r == pinned && depth != 0) continue
                for (c in candsByRequest[r]) {
                    val arrive = elapsed + graph.travel(cur, c)
                    if (!ignoreHours && !isOpen(graph.cands[c].stop, departAt.plusSeconds(arrive))) continue
                    path[depth] = c
                    visit(c, arrive + graph.dwell[c], mask or (1 shl r), depth + 1)
                }
            }
        }
    }
}
