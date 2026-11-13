package ai.deulleulcar.app.ui

import ai.deulleulcar.app.flow.AppUiState
import ai.deulleulcar.app.flow.CarTaskItem
import ai.deulleulcar.app.flow.DetailUi
import ai.deulleulcar.app.flow.ModeInfo
import ai.deulleulcar.app.flow.OpportunityUi
import ai.deulleulcar.app.flow.ProposalUi
import ai.deulleulcar.app.flow.Screen
import ai.deulleulcar.app.flow.TripUi
import ai.deulleulcar.app.flow.UrgentUi
import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.app.settings.Settings
import ai.deulleulcar.app.ui.theme.DC
import ai.deulleulcar.app.ui.theme.DeulleulCarTheme
import ai.deulleulcar.core.llm.HybridErrandParser
import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.DriveState
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.TelltaleType
import ai.deulleulcar.core.navi.RollingWaypointMachine
import ai.deulleulcar.core.place.DemoSeed
import ai.deulleulcar.core.place.PlaceSearchFactory
import ai.deulleulcar.core.trip.KnownPlaces
import ai.deulleulcar.core.trip.PlanResult
import ai.deulleulcar.core.trip.TripCopy
import ai.deulleulcar.core.trip.TripPlanner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDateTime

/** (디버그 빌드 전용) 미리보기·화면 확인용 표본 상태. 데모 시드와 실제 계획 로직으로 만든다(시나리오 1·3, 평일 18:40). */
object PreviewSamples {
    private val seed = DemoSeed.default
    private val places = KnownPlaces(seed.home, seed.work)
    private val at = LocalDateTime.of(2026, 11, 11, 18, 40)
    private val planner by lazy { TripPlanner(HybridErrandParser(llm = null), PlaceSearchFactory.create(kakaoRestApiKey = null)) }
    private val mode = ModeInfo(demo = true, llm = false, kakao = false)

    private val tpms = CarTask("car-1", CarTaskKind.Warning(TelltaleType.TPMS), Severity.RECOMMEND, Instant.now().minusSeconds(3 * 3600))
    private val washer = CarTask("car-2", CarTaskKind.Warning(TelltaleType.WASHER_FLUID_LOW), Severity.HOLD, Instant.now().minusSeconds(86_400))
    private val fuel = CarTask("car-3", CarTaskKind.LowRange, Severity.URGENT, Instant.now(), rangeKm = 25.0)

    private val proposalResult by lazy {
        runBlocking { planner.plan("퇴근길에 약국이랑 마트 들렀다 집에 가자", seed.work, places, at, suggestible = listOf(tpms)) } as PlanResult.Ready
    }

    private fun item(t: CarTask) = CarTaskItem(
        t, TripCopy.title(t), TripCopy.oneLiner(t), false,
        bestFix = runBlocking { planner.fixOptions(t, planner.directPlan(seed.work, places.homePoint, at), seed.work, at, 1).firstOrNull() },
    )

    val tasks: List<CarTaskItem> by lazy { listOf(item(tpms), item(washer)) }

    val home: AppUiState by lazy { AppUiState(mode = mode, tasks = tasks) }

    val homeWithTrip: AppUiState by lazy {
        val plan = proposalResult.proposal.plan
        val m = RollingWaypointMachine(plan.destination, plan.orderedStops)
        m.handle(ai.deulleulcar.core.navi.NaviEvent.Start)
        home.copy(trip = TripUi("t", plan, m.state))
    }

    val plan: AppUiState by lazy {
        val p = proposalResult.proposal
        val pending = p.suggestions
        home.copy(
            screen = Screen.PLAN,
            proposal = ProposalUi(p, p.plan, pending, TripCopy.proposalSpeech(p.plan, pending.firstOrNull())),
        )
    }

    val driving: AppUiState by lazy { homeWithTrip.copy(drive = DriveState.DRIVING) }

    val drivingOpportunity: AppUiState by lazy {
        val s = proposalResult.proposal.suggestions.first()
        driving.copy(opportunity = OpportunityUi(s, TripCopy.suggestionSpeech(s)))
    }

    val urgent: AppUiState by lazy {
        val fix = runBlocking { planner.fixOptions(fuel, planner.directPlan(seed.work, places.homePoint, at), seed.work, at, 1).firstOrNull() }
        driving.copy(urgent = UrgentUi(fuel, fix, false, TripCopy.title(fuel)))
    }

    val detail: AppUiState by lazy {
        val options = runBlocking { planner.fixOptions(tpms, planner.directPlan(seed.work, places.homePoint, at), seed.work, at, 3) }
        home.copy(screen = Screen.DETAIL, detail = DetailUi(tasks.first(), options, "3시간 전"))
    }

    val settings = Settings(demoMode = true)
    val features = listOf("차량 · 경고등" to FeatureState.ACTIVE, "음성 인식" to FeatureState.ACTIVE, "내비게이션" to FeatureState.NOT_SUPPORTED)
}

/** 컨트롤러 없이 상태만으로 그리는 화면(미리보기·스크린샷용). */
@Composable
fun StaticScreen(state: AppUiState) {
    DeulleulCarTheme {
        Column(Modifier.fillMaxSize().background(DC.Bg)) {
            StatusBar(state.driving, "18:41", state.mode, middle = state.proposal?.let { "\"${it.proposal.utterance}\"" }) {
                BigButton("데모", {}, height = DC.Touch * 0.6f)
                if (!state.driving) BigButton("설정", {}, height = DC.Touch * 0.6f)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val urgent = state.urgent
                when {
                    urgent != null -> UrgentScreen(urgent, 10, {}, {}, if (state.driving) null else ({}))
                    state.driving -> DrivingScreen(state, {}, {}, {})
                    state.screen == Screen.SETTINGS ->
                        SettingsScreen(PreviewSamples.settings, PreviewSamples.features, {}, { _, _ -> }, {}, {}, {}, {}, {})
                    state.screen == Screen.DETAIL -> DetailScreen(state.detail!!, false, {}, {}, {})
                    state.screen == Screen.PLAN -> PlanScreen(state.proposal!!, false, {}, {}, {}, {}, {})
                    else -> HomeScreen(state, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
    }
}

private const val W = 1120
private const val H = 800

@Preview(name = "S1 홈", widthDp = W, heightDp = H)
@Composable
private fun HomePreview() = StaticScreen(PreviewSamples.homeWithTrip)

@Preview(name = "S2 계획 확인", widthDp = W, heightDp = H)
@Composable
private fun PlanPreview() = StaticScreen(PreviewSamples.plan)

@Preview(name = "S3 주행 미니 카드", widthDp = W, heightDp = H)
@Composable
private fun DrivingPreview() = StaticScreen(PreviewSamples.drivingOpportunity)

@Preview(name = "S4 긴급 알림", widthDp = W, heightDp = H)
@Composable
private fun UrgentPreview() = StaticScreen(PreviewSamples.urgent)

@Preview(name = "S5 할 일 상세", widthDp = W, heightDp = H)
@Composable
private fun DetailPreview() = StaticScreen(PreviewSamples.detail)

@Preview(name = "S6 설정", widthDp = W, heightDp = H)
@Composable
private fun SettingsPreview() = StaticScreen(PreviewSamples.home.copy(screen = Screen.SETTINGS))
