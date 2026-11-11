package ai.deulleulcar.core.plan

import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskStatus
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.route.ExcludedRequest
import ai.deulleulcar.core.route.RoutePlan

/** 추천 강도 (docs/ux/ux-flow.md 등급표 "경로를 짤 때"). */
enum class SuggestionStrength {
    /** 긴급: 첫 정류지로 강하게 추천. 우회 시간과 무관. */
    STRONG,

    /** 권장: "겸사겸사 넣을까요? +N분" 추천 카드. 우회 ≤ [SuggestionRules.recommendMaxDetourMinutes]. */
    CARD,

    /** 보류: 우회 ≤ [SuggestionRules.holdMaxDetourMinutes]일 때만 가볍게 추천. */
    SOFT;

    companion object {
        fun of(severity: Severity) = when (severity) {
            Severity.URGENT -> STRONG
            Severity.RECOMMEND -> CARD
            Severity.HOLD -> SOFT
        }
    }
}

/**
 * 차의 할 일 추천 하나. **계획에 반영된 것이 아니다.** 사용자가 수락해야 [PlanMerger.accept]로 합쳐진다.
 * @property detourMinutes 지금 계획 대비 늘어나는 운전 시간(분, 반올림). 카드·TTS의 "+N분".
 * @property preview 수락하면 될 계획(미리보기). 화면에 순서를 보여 줄 때만 쓴다.
 * @property displaced 이 정류지를 넣으면 영업시간 때문에 빠지게 되는 기존 볼일. 긴급일 때만 비어 있지 않을 수 있다.
 */
data class Suggestion(
    val task: CarTask,
    val place: Place,
    val detourMinutes: Int,
    val strength: SuggestionStrength,
    val preview: RoutePlan,
    val displaced: List<ExcludedRequest> = emptyList(),
) {
    /** 첫 정류지로 넣을지(긴급). */
    val insertFirst: Boolean get() = strength == SuggestionStrength.STRONG
}

/**
 * @property eligibleStatuses 추천 대상 상태. "나중에"(SNOOZED)도 다음 경로를 짤 때 다시 추천한다
 *   (ux-flow: 거절이 쌓이면 문구를 강하게 → 다시 묻는다는 뜻). 수락(ACCEPTED)·해소(RESOLVED)는 제외.
 */
data class SuggestionRules(
    val recommendMaxDetourMinutes: Int = 5,
    val holdMaxDetourMinutes: Int = 3,
    val eligibleStatuses: Set<CarTaskStatus> = setOf(CarTaskStatus.OPEN, CarTaskStatus.SNOOZED),
) {
    /** 우회 시간 기준을 통과하는지. 긴급은 항상 통과. */
    fun allows(strength: SuggestionStrength, detourMinutes: Int): Boolean = when (strength) {
        SuggestionStrength.STRONG -> true
        SuggestionStrength.CARD -> detourMinutes <= recommendMaxDetourMinutes
        SuggestionStrength.SOFT -> detourMinutes <= holdMaxDetourMinutes
    }
}
