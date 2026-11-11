package ai.deulleulcar.core.plan

import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.route.GeoPoint
import ai.deulleulcar.core.route.RouteOptimizer
import ai.deulleulcar.core.route.RoutePlan
import ai.deulleulcar.core.route.RouteRequest
import ai.deulleulcar.core.route.StopRequest
import java.time.LocalDateTime

/**
 * 사람의 계획 + 차의 할 일 → 추천 목록 (spec T10).
 *
 * 규칙 (docs/ux/ux-flow.md):
 * - 긴급: 첫 정류지로 넣었을 때 우회가 가장 작은 해결지를 강하게 추천(우회 시간 제한 없음)
 * - 권장: 우회 ≤ 5분인 해결지가 있으면 추천 카드
 * - 보류: 우회 ≤ 3분일 때만 추천
 * - 이미 계획에 들어간(정류지가 연결된) 할 일, 해결지를 넣으면 기존 볼일이 빠지는 경우(긴급 제외)는 추천하지 않는다.
 *
 * 이 클래스는 계획을 바꾸지 않는다. 반환값의 [Suggestion.preview]는 미리보기일 뿐이다.
 */
class SuggestionEngine(
    private val optimizer: RouteOptimizer = RouteOptimizer(),
    private val rules: SuggestionRules = SuggestionRules(),
) {

    /**
     * @param fixPlaces 할 일마다 해결 장소 후보 (장소 검색 결과. 업종은 [ai.deulleulcar.core.cartask.CarTaskCatalog]).
     * @param from 다시 계산할 출발점. 주행 중이면 현재 위치를 넘긴다(이때 [plan]은 남은 정류지만 담은 계획).
     * @param at [from]을 출발하는 시각.
     * @return 강도 순(긴급 → 권장 → 보류), 같은 강도는 우회가 작은 순. 할 일마다 최대 1건.
     */
    fun suggest(
        plan: RoutePlan,
        tasks: List<CarTask>,
        from: GeoPoint = plan.origin,
        at: LocalDateTime = plan.departAt,
        fixPlaces: (CarTask) -> List<Place>,
    ): List<Suggestion> {
        val linked = plan.stops.mapNotNull { it.stop.linkedTaskId }.toSet()
        val base = optimizer.optimize(baseRequest(plan, from, at))
        return tasks
            .filter { it.status in rules.eligibleStatuses && it.id !in linked }
            .mapNotNull { task -> bestFor(plan, base, task, fixPlaces(task), from, at) }
            .sortedWith(compareBy<Suggestion> { it.strength.ordinal }.thenBy { it.detourMinutes })
    }

    /** [task]를 [place]로 들르면 어떻게 되는지 계산한다. 들를 수 없으면 null. 우회 기준은 적용하지 않는다. */
    fun evaluate(
        plan: RoutePlan,
        task: CarTask,
        place: Place,
        from: GeoPoint = plan.origin,
        at: LocalDateTime = plan.departAt,
    ): Suggestion? {
        val base = optimizer.optimize(baseRequest(plan, from, at))
        return evaluate(plan, base, task, place, from, at)
    }

    private fun bestFor(
        plan: RoutePlan,
        base: RoutePlan,
        task: CarTask,
        places: List<Place>,
        from: GeoPoint,
        at: LocalDateTime,
    ): Suggestion? {
        val strength = SuggestionStrength.of(task.severity)
        return places
            .mapNotNull { evaluate(plan, base, task, it, from, at) }
            .filter { rules.allows(strength, it.detourMinutes) }
            .filter { strength == SuggestionStrength.STRONG || it.displaced.isEmpty() }
            .minWithOrNull(compareBy<Suggestion> { it.displaced.size }.thenBy { it.detourMinutes })
    }

    private fun evaluate(
        plan: RoutePlan,
        base: RoutePlan,
        task: CarTask,
        place: Place,
        from: GeoPoint,
        at: LocalDateTime,
    ): Suggestion? {
        val strength = SuggestionStrength.of(task.severity)
        val preview = optimizer.optimize(withTask(plan, task, place, from, at))
        if (preview.stops.none { it.stop.linkedTaskId == task.id }) return null // 해결지가 닫혀 있음 등
        val before = base.excluded.map { it.request.id }.toSet()
        val displaced = preview.excluded.filter { it.request.id !in before }
        val detour = RoutePlan.roundMinutes(preview.driveTime.minus(base.driveTime))
        return Suggestion(task, place, detour, strength, preview, displaced)
    }

    companion object {
        fun requestIdOf(task: CarTask) = "car:${task.id}"

        fun stopOf(task: CarTask, place: Place) = Stop(place, StopSource.CAR, linkedTaskId = task.id)

        internal fun baseRequest(plan: RoutePlan, from: GeoPoint, at: LocalDateTime) =
            RouteRequest(from, plan.destination, plan.asRequests(), at, plan.pinnedFirstRequestId)

        /** 기존 정류지(장소 고정) + 할 일 정류지로 다시 최적화할 요청. 긴급이면 첫 정류지로 고정. */
        internal fun withTask(plan: RoutePlan, task: CarTask, place: Place, from: GeoPoint, at: LocalDateTime): RouteRequest {
            val id = requestIdOf(task)
            val pinned = if (SuggestionStrength.of(task.severity) == SuggestionStrength.STRONG) id else plan.pinnedFirstRequestId
            val requests = plan.asRequests() + StopRequest(id, listOf(stopOf(task, place)))
            return RouteRequest(from, plan.destination, requests, at, pinned)
        }
    }
}
