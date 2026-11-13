package ai.deulleulcar.app.demo

import ai.deulleulcar.app.flow.AppController
import ai.deulleulcar.app.flow.ControllerConfig
import ai.deulleulcar.app.flow.ModeInfo
import ai.deulleulcar.app.location.FakeLocationSource
import ai.deulleulcar.app.navi.FakeNaviPort
import ai.deulleulcar.app.vehicle.FakeVehicleSignalSource
import ai.deulleulcar.app.voice.FakeSpeechInput
import ai.deulleulcar.app.voice.FakeSpeechOutput
import ai.deulleulcar.app.voice.VoiceAnnouncer
import ai.deulleulcar.core.demo.DemoRunStatus
import ai.deulleulcar.core.demo.DemoScenarioId
import ai.deulleulcar.core.demo.DemoSpeed
import ai.deulleulcar.core.llm.HybridErrandParser
import ai.deulleulcar.core.model.Gear
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.model.TelltaleType
import ai.deulleulcar.core.navi.NaviCommand
import ai.deulleulcar.core.place.DemoSeed
import ai.deulleulcar.core.place.PlaceSearchFactory
import ai.deulleulcar.core.trip.KnownPlaces
import ai.deulleulcar.core.trip.TripPlanner
import ai.deulleulcar.core.voice.Utterance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DemoDirectorTest {
    private val seed = DemoSeed.default
    private val weekday1840 = LocalDateTime.of(2026, 11, 11, 18, 40)

    private class Rig(
        val director: DemoDirector,
        val controller: AppController,
        val vehicle: FakeVehicleSignalSource,
        val navi: FakeNaviPort,
        val tts: FakeSpeechOutput,
        val location: FakeLocationSource,
        var resets: Int = 0,
    ) {
        val state get() = controller.state.value
        val routes get() = navi.executed.filterIsInstance<NaviCommand.RequestRoute>()
        fun spokenText() = tts.spoken.joinToString("\n")
    }

    private fun TestScope.rig(): Rig {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        // 자유 조작 시작 상태: 점검 주기 지남(보류 1건). 시나리오는 초기화해서 이것을 지워야 한다.
        val vehicle = FakeVehicleSignalSource()
        val navi = FakeNaviPort()
        val tts = FakeSpeechOutput()
        val stt = FakeSpeechInput()
        val location = FakeLocationSource(seed.work)
        val announcer = VoiceAnnouncer(tts)
        navi.onSpeak = { announcer.announce(Utterance(it)) }
        val controller = AppController(
            scope = scope, vehicle = vehicle, speechIn = stt, speechOut = tts, announcer = announcer, navi = navi,
            location = location,
            planner = TripPlanner(HybridErrandParser(llm = null), PlaceSearchFactory.create(kakaoRestApiKey = null)),
            places = { KnownPlaces(seed.home, seed.work) },
            now = { weekday1840 },
            mode = ModeInfo(demo = true, llm = false, kakao = false),
            config = ControllerConfig(startupWindow = Duration.ZERO),
            compute = dispatcher,
        )
        controller.start()
        lateinit var r: Rig
        val director = DemoDirector(
            scope = scope, controller = controller, vehicle = vehicle, navi = navi, location = location, speechInput = stt,
            startPoint = { seed.work }, onReset = { r.resets++ }, nowMs = { testScheduler.currentTime },
        )
        r = Rig(director, controller, vehicle, navi, tts, location)
        return r
    }

    /** 1초 틱을 [seconds]번(또는 [until]이 될 때까지) 돌린다. 앱의 1Hz 틱과 같다. */
    private fun TestScope.ticks(r: Rig, seconds: Int, until: () -> Boolean = { false }): Int {
        repeat(seconds) { i ->
            advanceTimeBy(DemoDirector.TICK_MS)
            runCurrent()
            r.director.tick()
            runCurrent()
            if (until()) return i + 1
        }
        return seconds
    }

    private fun TestScope.playToEnd(r: Rig, id: DemoScenarioId, maxSeconds: Int = 600): Int {
        r.director.play(id)
        runCurrent()
        return ticks(r, maxSeconds) { r.director.ui.value.status == DemoRunStatus.DONE }
    }

    @Test
    fun `시나리오 1 - 한 번 누르면 계획 확인, 출발, 가상 주행, 도착까지 저절로 간다`() = runTest {
        val r = rig()
        assertEquals(1, r.state.tasks.size, "시작 상태에는 보류 할 일(정기 점검)이 있다")
        val took = playToEnd(r, DemoScenarioId.S1)

        assertEquals(DemoRunStatus.DONE, r.director.ui.value.status, r.director.ui.value.caption)
        assertEquals(1, r.resets)
        assertTrue(r.state.tasks.isEmpty(), "시나리오 시작 때 초기화")
        assertTrue(r.spokenText().contains("약국이 7시에 닫아서 먼저 갈게요."), r.spokenText())
        assertEquals(2, r.routes.first().waypoints.size)
        assertTrue(r.spokenText().contains("목적지에 도착했어요."), r.spokenText())
        assertNull(r.state.trip)
        ticks(r, 2)
        assertEquals(Gear.P, r.vehicle.snapshot.value.gear, "도착하면 세운다")
        assertTrue(r.location.location.value!!.point.distanceTo(seed.home) < 1.0, "집 위치로 이동")
        assertTrue(took < 120, "1x에서도 2분 안에 끝난다: ${took}s")
    }

    @Test
    fun `시나리오 2 - 도착 이벤트마다 다음 정류지를 넣는다`() = runTest {
        val r = rig()
        playToEnd(r, DemoScenarioId.S2)
        assertEquals(DemoRunStatus.DONE, r.director.ui.value.status, r.director.ui.value.caption)
        val routes = r.routes
        assertEquals(2, routes.first().waypoints.size)
        assertTrue(routes.size >= 3, "도착할 때마다 다시 요청: ${routes.size}")
        assertTrue(r.spokenText().contains("다음은"), r.spokenText())
        assertNull(r.state.trip)
    }

    @Test
    fun `시나리오 3 - 타이어는 허락 뒤에만 경로에 들어가고 들르면 경고가 꺼진다`() = runTest {
        val r = rig()
        playToEnd(r, DemoScenarioId.S3)
        assertEquals(DemoRunStatus.DONE, r.director.ui.value.status, r.director.ui.value.caption)
        assertTrue(r.spokenText().contains("타이어 공기압이 낮아요."), r.spokenText())
        assertTrue(r.routes.first().waypoints.any { it.source == StopSource.CAR }, "수락한 주유소가 첫 경로에 들어 있다")
        assertNull(r.vehicle.snapshot.value.telltales[TelltaleType.TPMS], "들른 뒤 경고등 꺼짐")
        assertTrue(r.state.tasks.isEmpty(), "들른 뒤 해결")
    }

    @Test
    fun `시나리오 4 - 긴급 연료는 바로 알리고 응 하면 주유소로 가서 해결한다`() = runTest {
        val r = rig()
        playToEnd(r, DemoScenarioId.S4)
        assertEquals(DemoRunStatus.DONE, r.director.ui.value.status, r.director.ui.value.caption)
        assertTrue(r.spokenText().contains("25km"), r.spokenText())
        assertEquals(PlaceCategory.GAS_STATION, r.routes.first().waypoints.first().place.category)
        assertEquals(DemoDirector.FULL_RANGE_KM, r.vehicle.snapshot.value.rangeKm)
        assertTrue(r.state.tasks.isEmpty())
    }

    @Test
    fun `4x는 1x보다 빨리 끝나고 다음 버튼으로 더 빨리 넘길 수 있다`() = runTest {
        val slow = rig()
        val t1 = playToEnd(slow, DemoScenarioId.S1)

        val fast = rig()
        fast.director.speed = DemoSpeed.X4
        val t4 = playToEnd(fast, DemoScenarioId.S1)
        assertTrue(t4 * 2 < t1, "4x $t4 s / 1x $t1 s")

        val manual = rig()
        manual.director.play(DemoScenarioId.S1)
        runCurrent()
        var presses = 0
        while (manual.director.ui.value.status != DemoRunStatus.DONE && presses < 40) {
            manual.director.next()
            runCurrent()
            presses++
        }
        assertEquals(DemoRunStatus.DONE, manual.director.ui.value.status, "다음만 눌러도 끝까지 간다")
    }

    @Test
    fun `자유 조작 - 안내가 시작되면 출발하고 위치가 다음 정류지로 다가간다`() = runTest {
        val r = rig()
        r.controller.submitUtterance("퇴근길에 약국이랑 마트 들렀다 집에 가자")
        runCurrent()
        r.controller.confirmPlan()
        runCurrent()
        val trip = assertNotNull(r.state.trip)
        val next = trip.rolling.remaining.first().place.point
        val start = r.location.location.value!!.point.distanceTo(next)
        ticks(r, 3)
        assertEquals(Gear.D, r.vehicle.snapshot.value.gear)
        val drive = assertNotNull(r.director.ui.value.drive)
        assertTrue(r.location.location.value!!.point.distanceTo(next) < start)
        assertTrue(drive.percent > 0)
        assertNull(r.director.ui.value.scenario)
    }
}
