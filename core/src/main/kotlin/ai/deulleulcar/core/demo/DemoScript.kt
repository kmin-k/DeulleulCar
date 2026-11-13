package ai.deulleulcar.core.demo

import ai.deulleulcar.core.model.TelltaleType

/** 데모 대본이 가짜 차량·마이크에 하는 일. 실제 적용은 앱([ai.deulleulcar.core.demo] 밖)이 한다. */
sealed interface DemoAction {
    /** 할 일·계획·안내·차량 값·데모 시각을 처음 상태로. */
    data object Reset : DemoAction

    /** 가상 마이크에 말을 넣고 마이크 버튼을 누른다(실제 앱과 같은 STT 경로). */
    data class Say(val text: String) : DemoAction

    data class Gear(val driving: Boolean) : DemoAction

    data class Telltale(val type: TelltaleType, val on: Boolean) : DemoAction

    data class Range(val km: Double) : DemoAction
}

/** 대본이 기다리는 화면 상태. */
enum class DemoCondition {
    /** 계획 제안(S2)이 떴다. */
    PROPOSAL_READY,

    /** 계획 제안에 답하지 않은 차의 할 일 추천이 있다. */
    SUGGESTION_PENDING,

    /** 계획 제안이 있고, 남은 추천이 없다(출발을 묻는 중). */
    NO_SUGGESTION_PENDING,

    /** 안내가 시작됐다. */
    TRIP_STARTED,

    /** 안내가 끝났다(목적지 도착). */
    TRIP_FINISHED,

    /** 긴급 알림(S4)이 해결 장소까지 찾아서 떴다. */
    URGENT_SHOWN,

    /** 정차 중 "다음 경로에 넣어 둘까요?"를 묻고 있다. */
    PARKED_ASK,

    /** 차의 할 일이 하나 이상 목록에 있다. */
    TASK_LISTED,
}

/** 대본이 보는 앱 상태 요약(앱 UI 상태에서 만든다). */
data class DemoObservation(
    val proposalReady: Boolean = false,
    val pendingSuggestions: Int = 0,
    val guiding: Boolean = false,
    val urgentShown: Boolean = false,
    val parkedAsk: Boolean = false,
    val taskCount: Int = 0,
) {
    fun satisfies(c: DemoCondition): Boolean = when (c) {
        DemoCondition.PROPOSAL_READY -> proposalReady
        DemoCondition.SUGGESTION_PENDING -> proposalReady && pendingSuggestions > 0
        DemoCondition.NO_SUGGESTION_PENDING -> proposalReady && pendingSuggestions == 0
        DemoCondition.TRIP_STARTED -> guiding
        DemoCondition.TRIP_FINISHED -> !guiding
        DemoCondition.URGENT_SHOWN -> urgentShown
        DemoCondition.PARKED_ASK -> parkedAsk
        DemoCondition.TASK_LISTED -> taskCount > 0
    }
}

/** 대본 한 줄. [caption]은 데모 바에 보여 줄 해설(무슨 일이 일어나는지). */
sealed interface DemoStep {
    val caption: String

    data class Act(val action: DemoAction, override val caption: String) : DemoStep

    /** 배속을 적용한 시간만큼 기다린다(읽을 시간). "다음"으로 건너뛸 수 있다. */
    data class Pause(val ms: Long, override val caption: String) : DemoStep

    /** 조건이 될 때까지 기다린다. [timeoutMs](실제 시간) 안에 안 되면 멈추고 "다음"을 안내한다. */
    data class Wait(val until: DemoCondition, override val caption: String, val timeoutMs: Long = 60_000) : DemoStep
}

enum class DemoScenarioId(val number: Int, val title: String) {
    S1(1, "퇴근길 볼일"),
    S2(2, "정류지 3곳"),
    S3(3, "타이어 저압"),
    S4(4, "연료 부족"),
}

data class DemoScenario(val id: DemoScenarioId, val steps: List<DemoStep>)

/** docs/ux/ux-flow.md 시나리오 1~4 대본. 모두 [DemoAction.Reset]으로 시작한다. */
object DemoScenarios {
    const val ERRANDS_2 = "퇴근길에 약국이랑 마트 들렀다 집에 가자"
    const val ERRANDS_3 = "세탁소, 약국, 마트 들렀다 집"
    const val YES = "응"
    const val LATER = "나중에"

    private const val DRIVE_TIMEOUT_MS = 240_000L

    private fun reset(caption: String) = DemoStep.Act(DemoAction.Reset, caption)
    private fun say(text: String, note: String = "") = DemoStep.Act(DemoAction.Say(text), "운전자: “$text”$note")
    private fun pause(seconds: Int, caption: String) = DemoStep.Pause(seconds * 1_000L, caption)
    private fun wait(c: DemoCondition, caption: String, timeoutMs: Long = 60_000) = DemoStep.Wait(c, caption, timeoutMs)

    private fun driveToEnd(caption: String) = listOf(
        wait(DemoCondition.TRIP_STARTED, "내비에 경유지를 넣고 안내를 시작해요."),
        wait(DemoCondition.TRIP_FINISHED, caption, DRIVE_TIMEOUT_MS),
    )

    private val s1 = DemoScenario(
        DemoScenarioId.S1,
        listOf(
            reset("평일 18:40, 회사 주차장. 퇴근하려는 참이에요."),
            pause(3, "평일 18:40, 회사 주차장. 퇴근하려는 참이에요."),
            say(ERRANDS_2),
            wait(DemoCondition.PROPOSAL_READY, "말 한 번으로 볼일 2개와 목적지(집)를 알아들어요."),
            pause(7, "영업시간과 우회 시간을 따져 순서를 정했어요. 약국이 먼저 닫아서 약국부터예요."),
            say(YES),
        ) + driveToEnd("가상 주행 중이에요. 경유지에 도착하면 다음 곳으로 이어 가요."),
    )

    private val s2 = DemoScenario(
        DemoScenarioId.S2,
        listOf(
            reset("볼일이 3곳이에요. 내비는 경유지를 2곳까지만 받아요."),
            pause(3, "볼일이 3곳이에요. 내비는 경유지를 2곳까지만 받아요."),
            say(ERRANDS_3),
            wait(DemoCondition.PROPOSAL_READY, "세 곳의 순서를 한 번에 짜요."),
            pause(6, "계획은 앱이 모두 들고 있어요. 내비에는 앞의 2곳만 넣어요."),
            say(YES),
        ) + driveToEnd("한 곳에 도착할 때마다 다음 정류지를 내비에 넣어요(롤링 경유지)."),
    )

    private val s3 = DemoScenario(
        DemoScenarioId.S3,
        listOf(
            reset("아침 출근길, 주행 중이에요."),
            DemoStep.Act(DemoAction.Gear(driving = true), "아침 출근길, 주행 중이에요."),
            pause(3, "아침 출근길, 주행 중이에요."),
            DemoStep.Act(DemoAction.Telltale(TelltaleType.TPMS, on = true), "타이어 공기압 경고등이 켜졌어요."),
            wait(DemoCondition.TASK_LISTED, "타이어 공기압 경고등이 켜졌어요."),
            pause(6, "주행 중이라 말하지 않고 배지만 띄워요. 경로에도 넣지 않아요."),
            DemoStep.Act(DemoAction.Gear(driving = false), "회사에 도착해 정차했어요."),
            wait(DemoCondition.PARKED_ASK, "회사에 도착해 정차했어요."),
            pause(7, "정차하자 한 번만 물어봐요: 퇴근길 경로에 넣어 둘까요?"),
            say(LATER),
            pause(4, "할 일 목록에 남겨 둬요. 이제 퇴근 시간이에요."),
            say(ERRANDS_2),
            wait(DemoCondition.SUGGESTION_PENDING, "경로를 짜면서 미뤄 둔 타이어를 다시 꺼내요."),
            pause(7, "공기주입기가 있는 주유소가 경로 근처에 있어요. 겸사겸사 넣을까요?"),
            say(YES, " (넣기)"),
            wait(DemoCondition.NO_SUGGESTION_PENDING, "순서를 다시 계산해요."),
            pause(5, "허락한 뒤에야 계획에 들어갔어요. 출발할까요?"),
            say(YES, " (출발)"),
        ) + driveToEnd("가상 주행 중이에요. 주유소에 들르면 경고등이 꺼져요."),
    )

    private val s4 = DemoScenario(
        DemoScenarioId.S4,
        listOf(
            reset("주행 중이에요. 연료가 거의 바닥났어요."),
            DemoStep.Act(DemoAction.Gear(driving = true), "주행 중이에요. 연료가 거의 바닥났어요."),
            pause(3, "주행 중이에요. 연료가 거의 바닥났어요."),
            DemoStep.Act(DemoAction.Range(25.0), "주행 가능 거리 25km: 긴급 할 일이에요."),
            wait(DemoCondition.URGENT_SHOWN, "가장 가까운 주유소를 찾는 중이에요."),
            pause(8, "바로 음성으로 알리고 가장 가까운 곳을 제안해요. 허락 전에는 경로를 바꾸지 않아요."),
            say(YES, " (들르기)"),
        ) + driveToEnd("주유소로 가상 주행 중이에요. 도착하면 주유한 것으로 처리해요."),
    )

    val all: List<DemoScenario> = listOf(s1, s2, s3, s4)

    fun of(id: DemoScenarioId): DemoScenario = all.first { it.id == id }
}

/** 대본 진행 상태. */
enum class DemoRunStatus { RUNNING, STUCK, DONE }

/**
 * 대본 실행기(순수 로직, 시계 주입). 호출한 쪽이 1초에 한 번쯤 [tick]을 부른다.
 *
 * - 한 틱에 [DemoAction]을 최대 1개 낸다. 다음 틱에 새 화면 상태를 보고 이어 간다(앱이 반영할 시간).
 * - [DemoStep.Pause]는 배속만큼 짧아진다. [DemoStep.Wait]는 조건이 되면 바로 넘어간다.
 * - 대기 시간이 넘으면 [DemoRunStatus.STUCK]: [skip]("다음")으로 넘어갈 수 있다.
 */
class DemoScriptRunner(val scenario: DemoScenario, startMs: Long) {
    var index: Int = 0
        private set
    var status: DemoRunStatus = DemoRunStatus.RUNNING
        private set

    private var stepStartMs: Long = startMs
    private var stepElapsedMs: Long = 0
    private var lastMs: Long = startMs

    val total: Int get() = scenario.steps.size
    val current: DemoStep? get() = scenario.steps.getOrNull(index)

    val caption: String
        get() = when (status) {
            DemoRunStatus.DONE -> "시나리오 ${scenario.id.number} 끝. 다른 시나리오를 골라 보세요."
            DemoRunStatus.STUCK -> "${current?.caption ?: ""} (멈춘 것 같아요. ‘다음’을 눌러 주세요)"
            DemoRunStatus.RUNNING -> current?.caption ?: ""
        }

    /** 지금 단계가 시간 대기(건너뛸 수 있는 쉬는 시간)인지. */
    val pausing: Boolean get() = status == DemoRunStatus.RUNNING && current is DemoStep.Pause

    fun tick(nowMs: Long, obs: DemoObservation, speed: DemoSpeed): DemoAction? {
        val dt = (nowMs - lastMs).coerceAtLeast(0)
        lastMs = nowMs
        stepElapsedMs += dt * speed.factor
        while (status != DemoRunStatus.DONE) {
            when (val step = current ?: return finish()) {
                is DemoStep.Act -> {
                    advance(nowMs)
                    return step.action
                }
                is DemoStep.Pause -> {
                    if (stepElapsedMs < step.ms) return null
                    advance(nowMs)
                }
                is DemoStep.Wait -> {
                    if (obs.satisfies(step.until)) {
                        advance(nowMs)
                    } else {
                        if (nowMs - stepStartMs >= step.timeoutMs) status = DemoRunStatus.STUCK
                        return null
                    }
                }
            }
        }
        return null
    }

    /** "다음": 지금 쉬는 시간·대기를 건너뛴다. */
    fun skip(nowMs: Long) {
        if (status == DemoRunStatus.DONE) return
        if (current is DemoStep.Act) return // 다음 틱에 바로 실행된다
        advance(nowMs)
    }

    private fun advance(nowMs: Long) {
        index++
        stepStartMs = nowMs
        stepElapsedMs = 0
        status = if (index >= scenario.steps.size) DemoRunStatus.DONE else DemoRunStatus.RUNNING
    }

    private fun finish(): DemoAction? {
        status = DemoRunStatus.DONE
        return null
    }
}
