package ai.deulleulcar.core.trip

import ai.deulleulcar.core.cartask.CarTaskCatalog
import ai.deulleulcar.core.cartask.CopyTemplates
import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.HoursSource
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.plan.Suggestion
import ai.deulleulcar.core.route.ExcludedRequest
import ai.deulleulcar.core.route.ExclusionReason
import ai.deulleulcar.core.route.Josa
import ai.deulleulcar.core.route.PlannedStop
import ai.deulleulcar.core.route.RoutePlan
import ai.deulleulcar.core.route.RouteSpeech
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 화면·음성 공통 문구 (docs/ux/ux-flow.md §4·§5). 카드와 TTS가 같은 말을 쓰도록 한곳에 모았다.
 * 순수 함수만 있다.
 */
object TripCopy {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun time(t: LocalDateTime): String = t.format(hhmm)

    /** 차의 할 일 카드 제목. 연료는 남은 거리를 넣는다. */
    fun title(task: CarTask): String {
        val km = task.rangeKm
        return if (task.kind == CarTaskKind.LowRange && km != null) {
            "주행 가능 거리 ${CopyTemplates.roundKm(km)}km"
        } else {
            CarTaskCatalog.info(task.kind).meaning
        }
    }

    /** 카드의 한 줄 설명: 위험한가 + 언제까지. */
    fun oneLiner(task: CarTask, escalated: Boolean = false): String {
        val copy = CopyTemplates.describe(task, escalated)
        return "${copy.risk.trimEnd()} ${copy.deadline}"
    }

    fun severityLabel(severity: Severity): String = when (severity) {
        Severity.URGENT -> "긴급"
        Severity.RECOMMEND -> "권장"
        Severity.HOLD -> "보류"
    }

    /** 해결 방법 한 줄 (S5 "어떻게 해결해요?"). */
    fun howToFix(task: CarTask): String {
        val info = CarTaskCatalog.info(task.kind)
        val where = info.fixPlaces.joinToString("·") { it.label }
        val stem = info.action.removeSuffix("는")
        val conditional = stem + if (Josa.hasFinalConsonant(stem)) "으면" else "면"
        return "${where}에서 해결할 수 있어요. ${conditional} 돼요."
    }

    /**
     * 계획 제안 음성 (시나리오 1): "약국이 7시에 닫아서 먼저 갈게요. 약국, 마트 순서로 들르면 평소보다 9분 더 걸려요. 출발할까요?"
     * 추천이 있으면 끝에 차의 할 일 질문을 붙인다("…경로상 +3분. 들를까요?").
     */
    fun proposalSpeech(plan: RoutePlan, suggestion: Suggestion? = null): String = buildString {
        orderNote(plan)?.let { append(it).append(' ') }
        val names = plan.stops.map { RouteSpeech.nameOf(it.stop) }
        val extra = plan.extraTotalMinutes
        val cost = if (extra > 0) "평소보다 ${extra}분 더 걸려요." else "시간은 거의 더 들지 않아요."
        when (names.size) {
            0 -> append("바로 목적지로 갈게요.")
            1 -> append("${Josa.eulReul(names[0])} 들르면 $cost")
            else -> append("${names.joinToString(", ")} 순서로 들르면 $cost")
        }
        excludedSentence(plan.excluded)?.let { append(' ').append(it) }
        if (suggestion != null) {
            append(" 참, ").append(suggestionSpeech(suggestion))
        } else {
            append(" 출발할까요?")
        }
    }

    /**
     * 순서 설명 한 문장. 영업시간 때문에 순서를 바꿨으면 그 이유, 아니면 첫 정류지가 곧 닫을 때(도착 후 [soonMinutes]분 안)
     * "약국이 7시에 닫아서 먼저 갈게요."라고 알려 준다. 업종 기본값 영업시간이면 단정하지 않으므로 말하지 않는다.
     */
    fun orderNote(plan: RoutePlan, soonMinutes: Long = 45): String? {
        plan.reorderReasons.firstOrNull()?.let { return RouteSpeech.reorderReason(it) }
        if (plan.stops.size < 2) return null
        val first = plan.stops.first()
        if (!closesSoon(first, soonMinutes)) return null
        return "${Josa.iGa(RouteSpeech.nameOf(first.stop))} ${RouteSpeech.clock(first.closesAt!!)}에 닫아서 먼저 갈게요."
    }

    private fun closesSoon(stop: PlannedStop, soonMinutes: Long): Boolean {
        val closes = stop.closesAt ?: return false
        if (stop.stop.place.openingHours?.source != HoursSource.CURATED) return false
        return !closes.isAfter(stop.arrival.plusMinutes(soonMinutes))
    }

    /** 차의 할 일 추천 한 문장: "타이어 공기압이 낮아요. 역삼 셀프주유소, 경로상 +3분. 들를까요?" */
    fun suggestionSpeech(s: Suggestion, escalated: Boolean = false): String {
        val copy = CopyTemplates.describe(s.task, escalated, s.place.name, s.detourMinutes.toDouble())
        return if (s.task.severity == Severity.RECOMMEND && !escalated) "${copy.what} ${copy.question}" else copy.spoken
    }

    /** 긴급 알림 음성(전체). */
    fun urgentSpeech(task: CarTask, fix: Suggestion?): String =
        CopyTemplates.describe(task, placeName = fix?.place?.name, detourMinutes = fix?.detourMinutes?.toDouble()).spoken

    /** 회전 직전에 쓸 짧은 긴급 음성. */
    fun urgentShort(task: CarTask): String = CopyTemplates.describe(task).what

    /** 정차 중 권장 할 일 한 번 안내(시나리오 3: 회사 도착 후). */
    fun parkedRecommendSpeech(task: CarTask, escalated: Boolean): String {
        val copy = CopyTemplates.describe(task, escalated)
        return "${copy.what} ${copy.risk} ${copy.deadline} 다음 경로에 넣어 둘까요?".replace("  ", " ")
    }

    /** 시동·도착 때 요약: "차의 할 일이 2개 남아 있어요: 타이어 공기압이 낮아요, 워셔액이 부족해요." */
    fun remainingSummary(tasks: List<CarTask>): String? {
        if (tasks.isEmpty()) return null
        val names = tasks.take(3).joinToString(", ") { title(it).removeSuffix(".") }
        return "차의 할 일이 ${tasks.size}개 남아 있어요: $names."
    }

    fun excludedSentence(excluded: List<ExcludedRequest>): String? {
        val first = excluded.firstOrNull() ?: return null
        val name = first.request.candidates.firstOrNull()?.let { RouteSpeech.nameOf(it) } ?: "한 곳"
        return when (first.reason) {
            ExclusionReason.NO_CANDIDATES -> "경로 근처에서 ${Josa.eulReul(name)} 찾지 못했어요."
            ExclusionReason.CLOSED -> "${Josa.eunNeun(name)} 도착할 때 문을 닫아서 뺐어요."
            ExclusionReason.CONFLICT -> "${Josa.eunNeun(name)} 영업시간이 맞지 않아서 뺐어요."
            ExclusionReason.TOO_MANY_STOPS -> "${Josa.eunNeun(name)} 다음에 들러요."
        }
    }

    /** S2 정류지 보조 문구: "19:00에 닫아서 먼저 들러요" / "22:00까지 영업" / "보통 22:00까지예요". */
    fun stopMeta(stop: PlannedStop, plan: RoutePlan): String {
        val reason = plan.reorderReasons.firstOrNull { it.stop.place.id == stop.stop.place.id }
        val closes = stop.closesAt
        val curated = stop.stop.place.openingHours?.source == HoursSource.CURATED
        return when {
            (reason != null || (plan.stops.firstOrNull() == stop && orderNote(plan) != null)) && closes != null ->
                "${time(closes)}에 닫아서 먼저 들러요"
            closes != null && curated -> "${time(closes)}까지 영업"
            closes != null -> "보통 ${time(closes)}까지예요"
            else -> stop.stop.place.category.label
        }
    }
}
