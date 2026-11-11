package ai.deulleulcar.core.plan

/**
 * 주행 중 "경로 근처 해결 기회" 제안 정책 (docs/ux/ux-flow.md 등급표 "주행 중 기회", 기획서 5-3(b)).
 *
 * - 한 주행([tripId])에서 같은 할 일은 1회만 제안 (알림 피로 방지). 긴급도 포함.
 *   긴급의 "10분 뒤 다시 알림"은 이 정책이 아니라 긴급 알림 쪽에서 따로 다룬다.
 * - 보류는 주행 중 제안하지 않는다.
 * - 권장은 우회 ≤ [recommendMaxDetourMinutes]분일 때만.
 * - 주행 중 화면은 카드 1장이 원칙이라 한 번에 1건만 고른다([next]).
 *
 * 스레드 안전하지 않음.
 */
class OpportunityPolicy(private val recommendMaxDetourMinutes: Int = 5) {
    private val offered = mutableMapOf<String, MutableSet<String>>()

    /** 기록은 남기지 않고 제안 가능 여부만 본다. */
    fun canOffer(tripId: String, suggestion: Suggestion): Boolean {
        if (suggestion.task.id in offered[tripId].orEmpty()) return false
        return when (suggestion.strength) {
            SuggestionStrength.STRONG -> true
            SuggestionStrength.CARD -> suggestion.detourMinutes <= recommendMaxDetourMinutes
            SuggestionStrength.SOFT -> false
        }
    }

    /**
     * [suggestions](보통 [SuggestionEngine.suggest] 결과, 강한 순) 중 지금 제안할 1건을 고르고 "제안함"으로 기록한다.
     * 제안할 것이 없으면 null.
     */
    fun next(tripId: String, suggestions: List<Suggestion>): Suggestion? {
        val pick = suggestions.firstOrNull { canOffer(tripId, it) } ?: return null
        markOffered(tripId, pick.task.id)
        return pick
    }

    fun markOffered(tripId: String, taskId: String) {
        offered.getOrPut(tripId) { mutableSetOf() } += taskId
    }

    /** 주행이 끝나면 기록을 지운다(다음 주행에서는 다시 제안 가능). */
    fun endTrip(tripId: String) {
        offered.remove(tripId)
    }
}
