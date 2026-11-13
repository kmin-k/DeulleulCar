package ai.deulleulcar.app.demo

import ai.deulleulcar.app.flow.AppController
import ai.deulleulcar.app.flow.AppUiState
import ai.deulleulcar.app.flow.TripUi
import ai.deulleulcar.app.location.FakeLocationSource
import ai.deulleulcar.app.navi.FakeNaviPort
import ai.deulleulcar.app.vehicle.FakeVehicleSignalSource
import ai.deulleulcar.app.vehicle.VehicleSnapshot
import ai.deulleulcar.app.voice.FakeSpeechInput
import ai.deulleulcar.core.demo.DemoAction
import ai.deulleulcar.core.demo.DemoObservation
import ai.deulleulcar.core.demo.DemoRunStatus
import ai.deulleulcar.core.demo.DemoScenarioId
import ai.deulleulcar.core.demo.DemoScenarios
import ai.deulleulcar.core.demo.DemoScriptRunner
import ai.deulleulcar.core.demo.DemoSpeed
import ai.deulleulcar.core.demo.DriveSimulation
import ai.deulleulcar.core.demo.DriveTarget
import ai.deulleulcar.core.demo.DriveTick
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.Gear
import ai.deulleulcar.core.model.TelltaleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 데모 바에 보여 줄 가상 주행 상태. */
data class DemoDriveUi(val targetName: String, val percent: Int, val remainingM: Int, val dwelling: Boolean, val paused: Boolean)

/** 데모 바 상태. [scenario]가 null이면 자유 조작(가상 주행만). */
data class DemoUi(
    val scenario: DemoScenarioId? = null,
    val caption: String? = null,
    val step: Int = 0,
    val total: Int = 0,
    val status: DemoRunStatus? = null,
    val speed: DemoSpeed = DemoSpeed.X1,
    val drive: DemoDriveUi? = null,
) {
    val visible: Boolean get() = scenario != null || drive != null
}

/**
 * 데모 모드의 "운전 대역". 1초마다([TICK_MS]) 한 번:
 * 1. 안내가 시작되면 기어를 D로 바꿔 출발하고, 목적지에 도착하면 P로 세운다.
 * 2. [DriveSimulation]으로 위치를 다음 정류지 쪽으로 옮기고, 도착하면 가짜 내비가 도착 이벤트를 낸다
 *    (실제 NaviHelper 응답을 기다리지 않는다). 차의 할 일 때문에 들른 곳이면 그 경고를 끈다(주유·공기 주입).
 * 3. 시나리오가 돌고 있으면 [DemoScriptRunner]가 낸 행동(말하기·기어·경고등·연료)을 가짜 어댑터에 넣는다.
 *
 * 무거운 일은 없다: 틱마다 상태 몇 개를 비교하고 위치 하나를 바꾼다. 애니메이션도 없다.
 */
class DemoDirector(
    private val scope: CoroutineScope,
    private val controller: AppController,
    private val vehicle: FakeVehicleSignalSource,
    private val navi: FakeNaviPort,
    private val location: FakeLocationSource,
    private val speechInput: FakeSpeechInput,
    /** 시나리오 출발 위치(회사). */
    private val startPoint: () -> GeoPoint,
    /** 시나리오를 처음부터 할 때 추가로 되돌릴 것(데모 시각 등). */
    private val onReset: () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sim: DriveSimulation = DriveSimulation(),
) {
    private val _ui = MutableStateFlow(DemoUi())
    val ui: StateFlow<DemoUi> = _ui.asStateFlow()

    private var runner: DemoScriptRunner? = null
    private var ticker: Job? = null

    private var seenTripId: String? = null
    private val departedTrips = mutableSetOf<String>()
    private var arrivedDestination = false
    private var lastTripId: String? = null
    /** 도착 이벤트가 반영된 뒤(다음 틱) 끌 차의 할 일. */
    private var pendingResolve: String? = null

    var speed: DemoSpeed
        get() = sim.speed
        set(value) {
            sim.speed = value
            publish(controller.state.value, null)
        }

    fun start() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
    }

    /** 시나리오를 처음부터 자동으로 진행한다. */
    fun play(id: DemoScenarioId) {
        runner = DemoScriptRunner(DemoScenarios.of(id), nowMs())
        tick()
    }

    /** 시나리오 진행을 멈춘다(화면 상태는 그대로 둔다). */
    fun dismiss() {
        runner = null
        publish(controller.state.value, null)
    }

    fun toggleSpeed() {
        speed = speed.next()
    }

    /** 수동 "다음": 쉬는 시간 건너뛰기 > 다음 정류지에 바로 도착 > 대기 건너뛰기. */
    fun next() {
        val r = runner?.takeIf { it.status != DemoRunStatus.DONE }
        when {
            r != null && r.pausing -> r.skip(nowMs())
            sim.arriveNow() -> Unit
            r != null -> r.skip(nowMs())
        }
        tick()
    }

    /** 테스트에서 직접 부른다. 앱에서는 [start]의 1초 틱이 부른다. */
    fun tick() {
        val now = nowMs()
        val s = controller.state.value
        pendingResolve?.let {
            pendingResolve = null
            resolveTask(it)
        }
        autoGear(s)
        val drive = simulate(now, s)
        val r = runner
        if (r != null && r.status != DemoRunStatus.DONE) {
            r.tick(now, observe(controller.state.value), speed)?.let { apply(it) }
        }
        publish(controller.state.value, drive)
    }

    // ───────────────────────── 주행 ─────────────────────────

    private fun autoGear(s: AppUiState) {
        val trip = s.trip
        if (trip == null) {
            if (lastTripId != null && arrivedDestination && s.driving) vehicle.gear(Gear.P) // 도착해서 세움
            lastTripId = null
            arrivedDestination = false
            seenTripId = null
            return
        }
        lastTripId = trip.id
        if (trip.id in departedTrips) return
        if (s.driving) {
            departedTrips += trip.id
            return
        }
        // 안내가 뜬 다음 틱에 출발(“출발할게요”를 볼 시간)
        if (seenTripId == trip.id && s.urgent == null) {
            departedTrips += trip.id
            vehicle.gear(Gear.D)
        } else {
            seenTripId = trip.id
        }
    }

    private fun simulate(now: Long, s: AppUiState): DemoDriveUi? {
        val trip = s.trip
        val here = location.location.value?.point ?: startPoint()
        val target = trip?.let { targetOf(it) }
        val moving = s.driving && s.urgent == null && s.opportunity == null && s.addition == null
        return when (val t = sim.update(now, target, here, moving)) {
            DriveTick.Idle -> null
            is DriveTick.Moving -> {
                location.moveTo(t.position)
                DemoDriveUi(t.target.name, (t.fraction * 100).roundToInt(), t.remainingM.roundToInt(), dwelling = false, paused = t.paused)
            }
            is DriveTick.Arrived -> {
                location.moveTo(t.target.point)
                if (t.target.isDestination) {
                    arrivedDestination = true
                    navi.arriveDestination()
                } else {
                    trip?.rolling?.remaining?.firstOrNull { it.place.id == t.target.id }?.linkedTaskId?.let { pendingResolve = it }
                    navi.arrive(t.target.id)
                }
                DemoDriveUi(t.target.name, 100, 0, dwelling = !t.target.isDestination, paused = false)
            }
            is DriveTick.Dwelling -> DemoDriveUi(t.at.name, 100, 0, dwelling = true, paused = false)
        }
    }

    private fun targetOf(trip: TripUi): DriveTarget {
        val next = trip.rolling.remaining.firstOrNull()
            ?: return DriveTarget("dest:${trip.id}", trip.plan.destination, trip.destinationName, isDestination = true)
        return DriveTarget(next.place.id, next.place.point, next.place.name, isDestination = false)
    }

    /** 해결 장소에 들렀으니 경고를 끈다(가상 주유·공기 주입). */
    private fun resolveTask(taskId: String) {
        when (val kind = controller.classifier.find(taskId)?.kind) {
            is CarTaskKind.Warning -> vehicle.telltale(kind.telltale, TelltaleState.OFF)
            CarTaskKind.LowRange -> vehicle.range(FULL_RANGE_KM)
            CarTaskKind.PeriodicInspection, null -> Unit
        }
    }

    // ───────────────────────── 대본 ─────────────────────────

    private fun observe(s: AppUiState) = DemoObservation(
        proposalReady = s.proposal != null && !s.planning,
        pendingSuggestions = s.proposal?.pending?.size ?: 0,
        guiding = s.trip != null,
        urgentShown = s.urgent?.searching == false,
        parkedAsk = s.parkedAskTaskId != null,
        taskCount = s.tasks.size,
    )

    private fun apply(action: DemoAction) {
        when (action) {
            DemoAction.Reset -> resetAll()
            is DemoAction.Say -> {
                speechInput.enqueue(action.text)
                controller.onMicTapped()
            }
            is DemoAction.Gear -> vehicle.gear(if (action.driving) Gear.D else Gear.P)
            is DemoAction.Telltale -> vehicle.telltale(action.type, if (action.on) TelltaleState.ON else TelltaleState.OFF)
            is DemoAction.Range -> vehicle.range(action.km)
        }
    }

    private fun resetAll() {
        sim.reset()
        seenTripId = null
        departedTrips.clear()
        arrivedDestination = false
        lastTripId = null
        pendingResolve = null
        vehicle.reset(RESET_SNAPSHOT)
        navi.reset()
        location.reset(startPoint())
        controller.resetForDemo()
        onReset()
    }

    private fun publish(s: AppUiState, drive: DemoDriveUi?) {
        val r = runner
        _ui.value = DemoUi(
            scenario = r?.scenario?.id,
            caption = r?.caption,
            step = r?.let { minOf(it.index + 1, it.total) } ?: 0,
            total = r?.total ?: 0,
            status = r?.status,
            speed = speed,
            drive = drive ?: _ui.value.drive?.takeIf { s.trip != null },
        )
    }

    companion object {
        const val TICK_MS = 1_000L
        const val FULL_RANGE_KM = 420.0
        /** 시나리오 시작 상태: 정차, 연료 충분, 점검 주기 전(보류 할 일 없음), 경고등 없음. */
        val RESET_SNAPSHOT = VehicleSnapshot(gear = Gear.P, rangeKm = FULL_RANGE_KM, odometerKm = 3_000.0)
    }
}
