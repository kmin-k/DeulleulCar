package ai.deulleulcar.app.flow

import ai.deulleulcar.app.location.FakeLocationSource
import ai.deulleulcar.app.navi.FakeNaviPort
import ai.deulleulcar.app.vehicle.FakeVehicleSignalSource
import ai.deulleulcar.app.vehicle.VehicleSnapshot
import ai.deulleulcar.app.voice.FakeSpeechInput
import ai.deulleulcar.app.voice.FakeSpeechOutput
import ai.deulleulcar.app.voice.VoiceAnnouncer
import ai.deulleulcar.core.llm.HybridErrandParser
import ai.deulleulcar.core.model.CarTaskStatus
import ai.deulleulcar.core.model.Gear
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.model.TelltaleState
import ai.deulleulcar.core.model.TelltaleType
import ai.deulleulcar.core.navi.NaviCommand
import ai.deulleulcar.core.place.DemoSeed
import ai.deulleulcar.core.place.PlaceSearchFactory
import ai.deulleulcar.core.trip.KnownPlaces
import ai.deulleulcar.core.trip.TripPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {
    private val seed = DemoSeed.default
    private val weekday1840 = LocalDateTime.of(2026, 11, 11, 18, 40) // 수요일

    private class Rig(
        val controller: AppController,
        val vehicle: FakeVehicleSignalSource,
        val navi: FakeNaviPort,
        val tts: FakeSpeechOutput,
        val stt: FakeSpeechInput,
        val location: FakeLocationSource,
        val announcer: VoiceAnnouncer,
    ) {
        val state get() = controller.state.value
        val routes get() = navi.executed.filterIsInstance<NaviCommand.RequestRoute>()
        fun spokenText() = tts.spoken.joinToString("\n")
    }

    private fun TestScope.rig(
        config: ControllerConfig = ControllerConfig(startupWindow = Duration.ZERO),
        initial: VehicleSnapshot = VehicleSnapshot(gear = Gear.P, rangeKm = 420.0, odometerKm = 3_000.0),
    ): Rig {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        val vehicle = FakeVehicleSignalSource(initial)
        val navi = FakeNaviPort()
        val tts = FakeSpeechOutput()
        val stt = FakeSpeechInput()
        val location = FakeLocationSource(seed.work)
        val announcer = VoiceAnnouncer(tts)
        navi.onSpeak = { announcer.announce(ai.deulleulcar.core.voice.Utterance(it)) }
        val controller = AppController(
            scope = scope,
            vehicle = vehicle,
            speechIn = stt,
            speechOut = tts,
            announcer = announcer,
            navi = navi,
            location = location,
            planner = TripPlanner(HybridErrandParser(llm = null), PlaceSearchFactory.create(kakaoRestApiKey = null)),
            places = { KnownPlaces(seed.home, seed.work) },
            now = { weekday1840 },
            mode = ModeInfo(demo = true, llm = false, kakao = false),
            config = config,
            compute = dispatcher,
        )
        controller.start()
        return Rig(controller, vehicle, navi, tts, stt, location, announcer)
    }

    @Test
    fun `시나리오 1 - 말하면 계획을 제안하고 응 하면 경유지 2개로 안내 시작`() = runTest {
        val r = rig()
        r.controller.submitUtterance("퇴근길에 약국이랑 마트 들렀다 집에 가자")
        advanceUntilIdle()

        val proposal = assertNotNull(r.state.proposal)
        assertEquals(Screen.PLAN, r.state.screen)
        assertEquals(listOf(PlaceCategory.PHARMACY, PlaceCategory.MART), proposal.plan.stops.map { it.stop.place.category })
        assertTrue(r.spokenText().contains("약국이 7시에 닫아서 먼저 갈게요."), r.spokenText())
        assertTrue(r.spokenText().contains("출발할까요?"))
        assertTrue(r.routes.isEmpty(), "확인 전에는 내비에 요청하지 않는다")

        r.controller.submitUtterance("응")
        advanceUntilIdle()

        assertNull(r.state.proposal)
        val trip = assertNotNull(r.state.trip)
        assertEquals(2, trip.rolling.remaining.size)
        val route = r.routes.single()
        assertEquals(KnownPlaces.HOME_NAME, route.destination.name)
        assertEquals(2, route.waypoints.size)
    }

    @Test
    fun `시나리오 2 - 정류지 3곳이면 도착할 때마다 다음 정류지를 넣는다`() = runTest {
        val r = rig()
        r.controller.submitUtterance("세탁소, 약국, 마트 들렀다 집")
        advanceUntilIdle()
        r.controller.confirmPlan()
        advanceUntilIdle()
        val first = r.routes.single()
        assertEquals(2, first.waypoints.size)
        val all = assertNotNull(r.state.trip).rolling.remaining
        assertEquals(3, all.size)

        r.navi.arriveNext()
        advanceUntilIdle()

        val second = r.routes.last()
        assertEquals(all.drop(1).map { it.place.id }, second.waypoints.map { it.place.id })
        assertTrue(r.spokenText().contains("도착했어요."), r.spokenText())
        assertEquals(1, r.state.trip!!.rolling.visited.size)

        r.navi.arriveNext()
        r.navi.arriveNext()
        r.navi.arriveDestination()
        advanceUntilIdle()
        assertNull(r.state.trip)
        assertTrue(r.spokenText().contains("목적지에 도착했어요."))
    }

    @Test
    fun `시나리오 3 - 타이어 저압은 경로에 넣지 않고 정차하면 묻고 다음 계획에서 추천한다`() = runTest {
        val r = rig()
        r.vehicle.gear(Gear.D)
        r.vehicle.telltale(TelltaleType.TPMS, TelltaleState.ON)
        advanceUntilIdle()

        val item = r.state.tasks.single()
        assertEquals(Severity.RECOMMEND, item.task.severity)
        assertTrue(!r.spokenText().contains("타이어"), "주행 중에는 말하지 않고 배지만")

        r.vehicle.gear(Gear.P)
        advanceUntilIdle()
        assertEquals(item.task.id, r.state.parkedAskTaskId)
        assertTrue(r.spokenText().contains("타이어 공기압이 낮아요."), r.spokenText())

        r.controller.submitUtterance("나중에")
        advanceUntilIdle()
        assertEquals(CarTaskStatus.SNOOZED, r.controller.classifier.tasks.single().status)

        r.controller.submitUtterance("퇴근길에 약국이랑 마트 들렀다 집에 가자")
        advanceUntilIdle()
        val proposal = assertNotNull(r.state.proposal)
        assertTrue(proposal.plan.stops.none { it.stop.source == StopSource.CAR }, "추천은 계획에 들어 있지 않다")
        val suggestion = proposal.pending.single()
        assertEquals(PlaceCategory.GAS_STATION, suggestion.place.category)
        assertTrue(proposal.speech.endsWith("들를까요?"), proposal.speech)

        r.controller.submitUtterance("응") // 추천 수락
        advanceUntilIdle()
        val merged = assertNotNull(r.state.proposal)
        assertTrue(merged.plan.stops.any { it.stop.source == StopSource.CAR && it.stop.linkedTaskId == item.task.id })
        assertEquals(CarTaskStatus.ACCEPTED, r.controller.classifier.tasks.single().status)
        assertTrue(merged.speech.endsWith("출발할까요?"), merged.speech)

        r.controller.submitUtterance("응") // 출발
        advanceUntilIdle()
        assertEquals(3, r.state.trip!!.rolling.remaining.size)
    }

    @Test
    fun `추천을 거절하면 계획은 그대로고 할 일은 목록에 남는다`() = runTest {
        val r = rig()
        r.vehicle.telltale(TelltaleType.TPMS, TelltaleState.ON)
        advanceUntilIdle()
        r.controller.submitUtterance("다음에") // 정차 중 질문에 답
        r.controller.submitUtterance("퇴근길에 약국이랑 마트 들렀다 집에 가자")
        advanceUntilIdle()
        val before = r.state.proposal!!.plan
        r.controller.declineSuggestion(r.state.proposal!!.pending.single())
        advanceUntilIdle()
        assertEquals(before, r.state.proposal!!.plan)
        r.controller.confirmPlan()
        advanceUntilIdle()
        assertTrue(r.routes.single().waypoints.none { it.source == StopSource.CAR })
        assertEquals(1, r.state.tasks.size)
    }

    @Test
    fun `안내 중 주행하다 생긴 할 일은 기회로만 제안하고 수락해야 경로에 들어간다`() = runTest {
        val r = rig()
        r.controller.submitUtterance("퇴근길에 약국이랑 마트 들렀다 집에 가자")
        advanceUntilIdle()
        r.controller.confirmPlan()
        r.vehicle.gear(Gear.D)
        advanceUntilIdle()

        r.vehicle.telltale(TelltaleType.TPMS, TelltaleState.ON)
        advanceUntilIdle()
        val opportunity = assertNotNull(r.state.opportunity)
        assertTrue(r.routes.flatMap { it.waypoints }.none { it.source == StopSource.CAR }, "허락 전에는 내비에 없음")

        r.controller.reply(true)
        advanceUntilIdle()
        assertNull(r.state.opportunity)
        val remaining = r.state.trip!!.rolling.remaining
        assertTrue(remaining.any { it.linkedTaskId == opportunity.suggestion.task.id })
        assertTrue(r.routes.last().waypoints.isNotEmpty())
    }

    @Test
    fun `회전 300m 이내에서는 일반 제안을 미뤘다가 지나면 말한다`() = runTest {
        val r = rig()
        r.controller.submitUtterance("퇴근길에 약국이랑 마트 들렀다 집에 가자")
        advanceUntilIdle()
        r.controller.confirmPlan()
        r.vehicle.gear(Gear.D)
        advanceUntilIdle()
        // AppContainer가 하는 연결: TBT 거리 → announcer
        r.announcer.onTurnDistance(200.0)

        r.vehicle.telltale(TelltaleType.TPMS, TelltaleState.ON)
        advanceUntilIdle()
        assertNotNull(r.state.opportunity)
        assertTrue(!r.spokenText().contains("타이어"), "회전 직전이라 보류")

        r.announcer.onTurnDistance(null)
        assertTrue(r.spokenText().contains("타이어 공기압이 낮아요."), r.spokenText())
    }

    @Test
    fun `긴급은 바로 알리고 나중에를 고르면 10분 뒤 다시 알린다`() = runTest {
        val r = rig()
        r.vehicle.range(25.0)
        advanceUntilIdle()
        val urgent = assertNotNull(r.state.urgent)
        assertNotNull(urgent.fix)
        assertTrue(r.spokenText().contains("주행 가능 거리가 25km 정도 남았어요."), r.spokenText())
        assertTrue(r.routes.isEmpty(), "허락 전에는 경로를 바꾸지 않는다")

        r.controller.urgentLater()
        advanceUntilIdle()
        assertNull(r.state.urgent)
        advanceTimeBy(Duration.ofMinutes(10).plusSeconds(1).toMillis())
        advanceUntilIdle()
        assertNotNull(r.state.urgent)

        r.controller.urgentAccept()
        advanceUntilIdle()
        val route = r.routes.single()
        assertEquals(PlaceCategory.GAS_STATION, route.waypoints.first().place.category)
        assertEquals(StopSource.CAR, route.waypoints.first().source)
    }

    @Test
    fun `시작할 때 남은 할 일을 한 줄로 요약한다`() = runTest {
        val r = rig(
            config = ControllerConfig(startupWindow = Duration.ofSeconds(3)),
            initial = VehicleSnapshot(gear = Gear.P, rangeKm = 420.0, odometerKm = 12_000.0),
        )
        advanceTimeBy(3_500)
        advanceUntilIdle()
        assertEquals(Severity.HOLD, r.state.tasks.single().task.severity)
        assertTrue(r.spokenText().contains("차의 할 일이 1개 남아 있어요: 정기 점검 시기예요."), r.spokenText())
    }

    @Test
    fun `경고가 꺼지면 목록에서 사라진다`() = runTest {
        val r = rig()
        r.vehicle.telltale(TelltaleType.WASHER_FLUID_LOW, TelltaleState.ON)
        advanceUntilIdle()
        assertEquals(1, r.state.tasks.size)
        assertNull(r.state.parkedAskTaskId, "보류는 묻지 않는다")
        r.vehicle.telltale(TelltaleType.WASHER_FLUID_LOW, TelltaleState.OFF)
        advanceUntilIdle()
        assertTrue(r.state.tasks.isEmpty())
    }

    @Test
    fun `마이크 - 가짜 STT 결과가 계획 요청으로 이어진다`() = runTest {
        val r = rig()
        r.stt.enqueue("약국 들렀다 집")
        r.controller.onMicTapped()
        advanceUntilIdle()
        assertEquals("약국 들렀다 집", r.state.voice.heard)
        assertNotNull(r.state.proposal)
    }
}
