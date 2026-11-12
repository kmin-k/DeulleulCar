package ai.deulleulcar.app.flow

import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskStatus
import ai.deulleulcar.core.model.DriveState
import ai.deulleulcar.core.navi.RollingState
import ai.deulleulcar.core.plan.Suggestion
import ai.deulleulcar.core.route.RoutePlan
import ai.deulleulcar.core.trip.PlanProposal

/** 정차 중 화면. 주행 중에는 무엇이든 S3(주행 미니 카드)로 그린다. */
enum class Screen { HOME, PLAN, DETAIL, SETTINGS }

/** 어떤 데이터로 돌고 있는지(화면 상단에 조용히 표시). */
data class ModeInfo(val demo: Boolean, val llm: Boolean, val kakao: Boolean) {
    /** 키가 없거나 데모 모드라 규칙 기반·데모 장소로 동작하는지. */
    val limited: Boolean get() = demo || !llm || !kakao

    val label: String
        get() = buildList {
            if (demo) add("데모 모드")
            add(if (llm) "AI 해석" else "규칙 해석")
            add(if (kakao) "실제 장소" else "데모 장소")
        }.joinToString(" · ")
}

/**
 * 차의 할 일 카드 하나.
 * @property bestFix 경로(또는 집 가는 길) 기준 가장 빠른 해결 장소. 계산 전이거나 없으면 null.
 * @property visited 이 할 일 때문에 해결 장소에 이미 들렀음(경고가 꺼지면 사라짐).
 */
data class CarTaskItem(
    val task: CarTask,
    val title: String,
    val line: String,
    val escalated: Boolean,
    val bestFix: Suggestion? = null,
    val visited: Boolean = false,
) {
    val accepted: Boolean get() = task.status == CarTaskStatus.ACCEPTED
}

/**
 * 확정 전 계획(S2).
 * @property pending 아직 답하지 않은 차의 할 일 추천. 계획([PlanProposal.plan])에는 들어 있지 않다.
 * @property baseExtraMinutes 사람의 볼일만 넣었을 때 직행 대비 늘어난 시간. "공기압까지 넣으면 +N분" 비교용.
 */
data class ProposalUi(
    val proposal: PlanProposal,
    val plan: RoutePlan,
    val pending: List<Suggestion>,
    val speech: String,
)

/** 안내 중인 여정. [plan]은 남은 정류지 기준으로 갱신된다(도착 예정 시각 표시용). */
data class TripUi(
    val id: String,
    val plan: RoutePlan,
    val rolling: RollingState,
) {
    val destinationName: String get() = plan.destination.name ?: "목적지"
}

/** 긴급 알림(S4). [fix]는 가장 가까운 해결 장소(찾는 중이면 null, [searching] = true). */
data class UrgentUi(val task: CarTask, val fix: Suggestion?, val searching: Boolean, val title: String)

/** 주행 중 경로 근처 해결 기회(S3 카드 1장). */
data class OpportunityUi(val suggestion: Suggestion, val speech: String)

/** 주행 중 "하나 더 들러"로 만든 변경안. */
data class AdditionUi(val plan: RoutePlan, val addedNames: List<String>, val extraMinutes: Int, val speech: String)

/** 할 일 상세(S5). [options]는 해결 장소 후보(우회 작은 순). */
data class DetailUi(val item: CarTaskItem, val options: List<Suggestion>?, val detectedAgo: String)

data class VoiceUi(
    val listening: Boolean = false,
    val partial: String? = null,
    /** 마지막으로 알아들은 말(상단 표시). */
    val heard: String? = null,
    /** 마지막으로 앱이 한 말(S2 말풍선 등). */
    val spoken: String? = null,
)

data class AppUiState(
    val drive: DriveState = DriveState.PARKED,
    val screen: Screen = Screen.HOME,
    val mode: ModeInfo = ModeInfo(demo = false, llm = false, kakao = false),
    val tasks: List<CarTaskItem> = emptyList(),
    val planning: Boolean = false,
    val proposal: ProposalUi? = null,
    val trip: TripUi? = null,
    val urgent: UrgentUi? = null,
    val opportunity: OpportunityUi? = null,
    val addition: AdditionUi? = null,
    /** 정차 중 "다음 경로에 넣어 둘까요?"를 물은 할 일 id. */
    val parkedAskTaskId: String? = null,
    val detail: DetailUi? = null,
    val voice: VoiceUi = VoiceUi(),
    /** 잠깐 보여 줄 안내(오류·확인 등). */
    val notice: String? = null,
) {
    val driving: Boolean get() = drive == DriveState.DRIVING
}
