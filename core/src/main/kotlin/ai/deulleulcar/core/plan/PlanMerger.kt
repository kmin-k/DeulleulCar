package ai.deulleulcar.core.plan

import ai.deulleulcar.core.cartask.CarTaskClassifier
import ai.deulleulcar.core.route.GeoPoint
import ai.deulleulcar.core.route.RouteOptimizer
import ai.deulleulcar.core.route.RoutePlan
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime

/** 사용자가 추천을 거절한 기록. */
data class DeclineRecord(val taskId: String, val placeId: String, val detourMinutes: Int, val at: Instant)

/**
 * 추천을 계획에 합치거나(수락) 기록만 남긴다(거절).
 *
 * 핵심 규칙(spec 결정사항): 차의 할 일은 **사용자가 허락하기 전에는 동선에 넣지 않는다.**
 * - [accept]: 해결지를 `Stop(source = CAR, linkedTaskId)`로 넣고 순서를 다시 계산한 **새** 계획을 돌려준다.
 * - [decline]: 받은 계획을 그대로 돌려주고 거절만 기록한다.
 *
 * 할 일 상태(ACCEPTED/SNOOZED) 갱신은 콜백으로 넘긴다. [withClassifier]로 [CarTaskClassifier]와 바로 연결할 수 있다.
 * 스레드 안전하지 않음.
 */
class PlanMerger(
    private val optimizer: RouteOptimizer = RouteOptimizer(),
    private val onAccepted: (taskId: String) -> Unit = {},
    private val onDeclined: (taskId: String) -> Unit = {},
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val declineLog = mutableListOf<DeclineRecord>()

    val declines: List<DeclineRecord> get() = declineLog.toList()

    /**
     * @param from 다시 계산할 출발점(주행 중이면 현재 위치, [plan]은 남은 정류지 계획).
     * @return 할 일 정류지가 들어간 새 계획. 해결지가 닫혀 있는 등 들를 수 없게 됐으면 null(계획 불변, 콜백 없음).
     */
    fun accept(
        plan: RoutePlan,
        suggestion: Suggestion,
        from: GeoPoint = plan.origin,
        at: LocalDateTime = plan.departAt,
    ): RoutePlan? {
        val request = SuggestionEngine.withTask(plan, suggestion.task, suggestion.place, from, at)
        val merged = optimizer.optimize(request)
        if (merged.stops.none { it.stop.linkedTaskId == suggestion.task.id }) return null
        onAccepted(suggestion.task.id)
        return merged
    }

    /** 계획은 바꾸지 않는다. 같은 인스턴스를 그대로 돌려준다. */
    fun decline(plan: RoutePlan, suggestion: Suggestion): RoutePlan {
        declineLog += DeclineRecord(suggestion.task.id, suggestion.place.id, suggestion.detourMinutes, clock.instant())
        onDeclined(suggestion.task.id)
        return plan
    }

    companion object {
        /** 수락 → [CarTaskClassifier.accept], 거절 → [CarTaskClassifier.decline]. */
        fun withClassifier(
            classifier: CarTaskClassifier,
            optimizer: RouteOptimizer = RouteOptimizer(),
            clock: Clock = Clock.systemDefaultZone(),
        ) = PlanMerger(
            optimizer = optimizer,
            onAccepted = { classifier.accept(it) },
            onDeclined = { classifier.decline(it) },
            clock = clock,
        )
    }
}
