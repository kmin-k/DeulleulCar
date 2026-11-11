package ai.deulleulcar.core.cartask

import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.Severity
import kotlin.math.roundToInt

/**
 * 3단 구조 안내 문구 (docs/ux/ux-flow.md §5): 무엇 → 지금 위험한가 → 언제까지, 그리고 "들를까요?" 질문.
 * TTS와 카드가 같은 문구를 쓴다. LLM이 없거나 실패했을 때도 이 템플릿만으로 안내가 완성된다.
 */
data class CarTaskCopy(
    val what: String,
    val risk: String,
    val deadline: String,
    val question: String,
) {
    /** TTS로 읽을 전체 문장. */
    val spoken: String get() = listOf(what, risk, deadline, question).joinToString(" ")
}

object CopyTemplates {

    /**
     * @param escalated [CarTaskClassifier.isEscalated] 결과. 권장 문구를 한 단계 강하게 한다.
     * @param placeName 추천할 해결 장소 이름 (없으면 업종 이름으로 묻는다).
     * @param detourMinutes 경로상 추가 시간(분). 있으면 "경로상 +N분"을 붙인다.
     */
    fun describe(
        task: CarTask,
        escalated: Boolean = false,
        placeName: String? = null,
        detourMinutes: Double? = null,
    ): CarTaskCopy {
        val info = CarTaskCatalog.info(task.kind)
        val what = what(task, info)
        val risk = risk(task.severity, info, escalated)
        val deadline = deadline(task.severity, info, escalated)
        val question = question(task, info, placeName, detourMinutes)
        return CarTaskCopy(what, risk, deadline, question)
    }

    private fun what(task: CarTask, info: CarTaskInfo): String {
        val km = task.rangeKm
        return if (task.kind == CarTaskKind.LowRange && km != null) {
            "주행 가능 거리가 ${roundKm(km)}km 정도 남았어요."
        } else {
            "${info.meaning}."
        }
    }

    private fun risk(severity: Severity, info: CarTaskInfo, escalated: Boolean): String = when (severity) {
        Severity.URGENT ->
            if (info.stopFirst) "계속 달리면 위험할 수 있어요." else "이대로 오래 달리면 위험할 수 있어요."
        Severity.RECOMMEND ->
            if (escalated) "미룬 지 꽤 됐어요. 계속 두면 문제가 커질 수 있어요." else "당장 위험하진 않지만"
        Severity.HOLD -> "급하진 않아요."
    }

    private fun deadline(severity: Severity, info: CarTaskInfo, escalated: Boolean): String = when (severity) {
        Severity.URGENT -> "지금 바로 ${info.action} 게 좋아요."
        Severity.RECOMMEND ->
            if (escalated) "오늘은 꼭 ${info.action} 게 좋아요." else "오늘 안에 ${info.action} 게 좋아요."
        Severity.HOLD -> "근처를 지날 때 ${info.action} 게 좋아요."
    }

    private fun question(task: CarTask, info: CarTaskInfo, placeName: String?, detourMinutes: Double?): String {
        val target = placeName ?: buildString {
            if (task.severity == Severity.URGENT) append("가장 ")
            append("가까운 ").append(info.fixPlaces.first().label)
        }
        return if (detourMinutes != null) {
            "$target, 경로상 +${roundMinutes(detourMinutes)}분. 들를까요?"
        } else {
            "${target}에 들를까요?"
        }
    }

    /** 10km 이상은 5km 단위, 그 미만은 1km 단위로 반올림 ("25km 정도"). */
    fun roundKm(km: Double): Int =
        if (km >= 10) (km / 5).roundToInt() * 5 else km.roundToInt().coerceAtLeast(1)

    /** 1분 미만도 "+1분"으로 말한다. */
    fun roundMinutes(minutes: Double): Int = minutes.roundToInt().coerceAtLeast(1)
}
