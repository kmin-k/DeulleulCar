package ai.deulleulcar.core.cartask

import ai.deulleulcar.core.model.CarSignal
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.CarTaskStatus
import ai.deulleulcar.core.model.DriveState
import ai.deulleulcar.core.model.Gear
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.TelltaleState
import ai.deulleulcar.core.model.TelltaleType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

/** 테스트용으로 시간을 직접 움직이는 Clock. */
class MutableClock(var now: Instant = Instant.parse("2026-11-11T09:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) {
        now = now.plus(d)
    }
}

class CarTaskClassifierTest {
    private val clock = MutableClock()
    private val classifier = CarTaskClassifier(clock = clock)

    private fun on(type: TelltaleType) = CarSignal.Telltale(type, TelltaleState.ON)
    private fun blink(type: TelltaleType) = CarSignal.Telltale(type, TelltaleState.BLINK)
    private fun off(type: TelltaleType) = CarSignal.Telltale(type, TelltaleState.OFF)

    @ParameterizedTest
    @EnumSource(
        TelltaleType::class,
        names = ["ENGINE_OIL_PRESSURE", "ENGINE_TEMPERATURE", "EV_SYSTEM", "POWER_DOWN", "MDPS", "H2_LEAK"],
    )
    fun `긴급 경고등`(type: TelltaleType) {
        val created = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(type)))
        assertEquals(Severity.URGENT, created.task.severity)
        assertEquals(CarTaskKind.Warning(type), created.task.kind)
        assertEquals(CarTaskStatus.OPEN, created.task.status)
    }

    @ParameterizedTest
    @EnumSource(
        TelltaleType::class,
        names = [
            "TPMS", "ENGINE", "ABS", "ESC", "AIRBAG", "LOW_VOLT_BATTERY", "ELECTRONIC_PARKING_BRAKE",
            "RBS", "PARTICLE_FILTER", "H2_SENSOR", "MASTER_WARNING",
        ],
    )
    fun `권장 경고등`(type: TelltaleType) {
        val created = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(type)))
        assertEquals(Severity.RECOMMEND, created.task.severity)
    }

    @ParameterizedTest
    @EnumSource(TelltaleType::class, names = ["AFS", "LED_HEADLAMP", "EMERGENCY_CALL"])
    fun `보류 경고등`(type: TelltaleType) {
        val created = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(type)))
        assertEquals(Severity.HOLD, created.task.severity)
    }

    @ParameterizedTest
    @EnumSource(TelltaleType::class, names = ["STATUS_INDICATOR", "UNKNOWN"])
    fun `상태 표시등과 모르는 경고등은 무시`(type: TelltaleType) {
        assertEquals(CarTaskUpdate.None, classifier.onSignal(on(type)))
        assertEquals(CarTaskUpdate.None, classifier.onSignal(blink(type)))
        assertTrue(classifier.tasks.isEmpty())
    }

    @Test
    fun `모든 행동 필요 경고등에 설명 자료가 있다`() {
        TelltaleType.entries.filter { it.isActionable }.forEach { type ->
            val info = CarTaskCatalog.info(CarTaskKind.Warning(type))
            assertTrue(info.meaning.endsWith("요"), "$type meaning")
            assertTrue(info.fixPlaces.isNotEmpty(), "$type fixPlaces")
        }
    }

    @ParameterizedTest
    @CsvSource("AFS,RECOMMEND", "TPMS,URGENT", "ENGINE_OIL_PRESSURE,URGENT")
    fun `점멸은 한 단계 상향`(type: TelltaleType, expected: Severity) {
        val created = assertIs<CarTaskUpdate.Created>(classifier.onSignal(blink(type)))
        assertEquals(expected, created.task.severity)
    }

    @Test
    fun `켜짐에서 점멸로 바뀌면 같은 할 일의 등급만 오른다`() {
        val first = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(TelltaleType.TPMS))).task
        val changed = assertIs<CarTaskUpdate.SeverityChanged>(classifier.onSignal(blink(TelltaleType.TPMS)))
        assertEquals(first.id, changed.after.id)
        assertEquals(first.detectedAt, changed.after.detectedAt)
        assertEquals(Severity.URGENT, changed.after.severity)
        assertEquals(1, classifier.tasks.size)
    }

    @Test
    fun `경고등이 꺼지면 해소`() {
        classifier.onSignal(on(TelltaleType.TPMS))
        val resolved = assertIs<CarTaskUpdate.Resolved>(classifier.onSignal(off(TelltaleType.TPMS)))
        assertEquals(CarTaskStatus.RESOLVED, resolved.task.status)
        assertTrue(classifier.tasks.isEmpty())
        // 없는 할 일을 끄면 변화 없음
        assertEquals(CarTaskUpdate.None, classifier.onSignal(off(TelltaleType.TPMS)))
    }

    @Test
    fun `해소된 뒤 다시 켜지면 새 할 일`() {
        val first = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(TelltaleType.ABS))).task
        classifier.onSignal(off(TelltaleType.ABS))
        val second = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(TelltaleType.ABS))).task
        assertTrue(first.id != second.id)
    }

    @Test
    fun `같은 신호가 반복되면 중복 생성하지 않는다`() {
        classifier.onSignal(on(TelltaleType.TPMS))
        repeat(5) { assertEquals(CarTaskUpdate.None, classifier.onSignal(on(TelltaleType.TPMS))) }
        classifier.onSignal(on(TelltaleType.ENGINE))
        assertEquals(2, classifier.tasks.size)
    }

    @ParameterizedTest
    @CsvSource(
        "0, URGENT", "29, URGENT", "29.9, URGENT",
        "30, RECOMMEND", "50, RECOMMEND", "79, RECOMMEND", "79.9, RECOMMEND",
    )
    fun `주행 가능 거리 경계값`(km: Double, expected: Severity) {
        val created = assertIs<CarTaskUpdate.Created>(classifier.onSignal(CarSignal.RemainingRange(km)))
        assertEquals(CarTaskKind.LowRange, created.task.kind)
        assertEquals(expected, created.task.severity)
        assertEquals(km, created.task.rangeKm)
    }

    @ParameterizedTest
    @CsvSource("80", "80.1", "500")
    fun `주행 가능 거리 80km 이상은 할 일 아님`(km: Double) {
        assertEquals(CarTaskUpdate.None, classifier.onSignal(CarSignal.RemainingRange(km)))
    }

    @Test
    fun `주행 가능 거리가 줄면 권장에서 긴급으로, 주유하면 해소`() {
        classifier.onSignal(CarSignal.RemainingRange(60.0))
        assertEquals(CarTaskUpdate.None, classifier.onSignal(CarSignal.RemainingRange(45.0)))
        assertEquals(45.0, classifier.find(CarTaskKind.LowRange)?.rangeKm)
        val changed = assertIs<CarTaskUpdate.SeverityChanged>(classifier.onSignal(CarSignal.RemainingRange(25.0)))
        assertEquals(Severity.URGENT, changed.after.severity)
        assertEquals(25.0, changed.after.rangeKm)
        assertIs<CarTaskUpdate.Resolved>(classifier.onSignal(CarSignal.RemainingRange(400.0)))
        assertNull(classifier.find(CarTaskKind.LowRange))
    }

    @Test
    fun `나중에 한 할 일이 더 위험해지면 다시 열린다`() {
        val task = assertIs<CarTaskUpdate.Created>(classifier.onSignal(CarSignal.RemainingRange(60.0))).task
        classifier.decline(task.id)
        val changed = assertIs<CarTaskUpdate.SeverityChanged>(classifier.onSignal(CarSignal.RemainingRange(20.0)))
        assertEquals(CarTaskStatus.OPEN, changed.after.status)
        assertEquals(1, changed.after.declineCount)
    }

    @Test
    fun `정기 점검은 마지막 점검 후 주기 이상 달리면 보류`() {
        val c = CarTaskClassifier(clock = clock, inspectionPolicy = InspectionPolicy(lastInspectionKm = 20_000.0))
        assertEquals(CarTaskUpdate.None, c.onSignal(CarSignal.Odometer(29_999.0)))
        val created = assertIs<CarTaskUpdate.Created>(c.onSignal(CarSignal.Odometer(30_000.0)))
        assertEquals(CarTaskKind.PeriodicInspection, created.task.kind)
        assertEquals(Severity.HOLD, created.task.severity)
        assertEquals(CarTaskUpdate.None, c.onSignal(CarSignal.Odometer(30_050.0)))
        // 점검을 받아 기준이 바뀌면 다음 신호에서 해소
        c.inspectionPolicy = InspectionPolicy(lastInspectionKm = 30_050.0)
        assertIs<CarTaskUpdate.Resolved>(c.onSignal(CarSignal.Odometer(30_060.0)))
    }

    @Test
    fun `정기 점검 주기는 파라미터로 바꿀 수 있다`() {
        val c = CarTaskClassifier(clock = clock, inspectionPolicy = InspectionPolicy(0.0, intervalKm = 5_000.0))
        assertIs<CarTaskUpdate.Created>(c.onSignal(CarSignal.Odometer(5_000.0)))
    }

    @Test
    fun `기어 신호는 할 일을 만들지 않는다`() {
        Gear.entries.forEach { assertEquals(CarTaskUpdate.None, classifier.onSignal(CarSignal.GearChanged(it))) }
    }

    @Test
    fun `주행 상태는 기어 P만 정차`() {
        assertEquals(DriveState.PARKED, DriveState.from(Gear.P))
        listOf(Gear.R, Gear.N, Gear.D).forEach { assertEquals(DriveState.DRIVING, DriveState.from(it)) }
    }

    @Test
    fun `수락과 거절`() {
        val task = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(TelltaleType.TPMS))).task
        assertEquals(CarTaskStatus.SNOOZED, classifier.decline(task.id)?.status)
        assertEquals(1, classifier.find(task.id)?.declineCount)
        assertEquals(CarTaskStatus.ACCEPTED, classifier.accept(task.id)?.status)
        assertNull(classifier.accept("없는-id"))
    }

    @Test
    fun `거절 3회면 강도 상향`() {
        val task = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(TelltaleType.TPMS))).task
        repeat(2) { classifier.decline(task.id) }
        assertFalse(classifier.isEscalated(classifier.find(task.id)!!))
        classifier.decline(task.id)
        assertTrue(classifier.isEscalated(classifier.find(task.id)!!))
    }

    @Test
    fun `감지 후 3일 지나면 강도 상향`() {
        val task = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(TelltaleType.TPMS))).task
        clock.advance(Duration.ofDays(3).minusSeconds(1))
        assertFalse(classifier.isEscalated(task))
        clock.advance(Duration.ofSeconds(1))
        assertTrue(classifier.isEscalated(task))
    }

    @Test
    fun `긴급과 보류는 강도 상향 대상이 아니다`() {
        val urgent = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(TelltaleType.MDPS))).task
        val hold = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(TelltaleType.AFS))).task
        clock.advance(Duration.ofDays(10))
        assertFalse(classifier.isEscalated(urgent))
        assertFalse(classifier.isEscalated(hold))
    }

    @Test
    fun `등급 상향 함수`() {
        assertEquals(Severity.RECOMMEND, Severity.HOLD.raised())
        assertEquals(Severity.URGENT, Severity.RECOMMEND.raised())
        assertEquals(Severity.URGENT, Severity.URGENT.raised())
    }

    @ParameterizedTest
    @CsvSource(
        "LOW_FUEL,RECOMMEND", "LOW_EV_BATTERY,RECOMMEND",
        "BRAKE_FLUID_LOW,URGENT", "ENGINE_OIL_LOW,RECOMMEND", "WASHER_FLUID_LOW,HOLD",
    )
    fun `연료·배터리 경고등과 Safety 레벨 신호의 등급`(type: TelltaleType, expected: Severity) {
        val created = assertIs<CarTaskUpdate.Created>(classifier.onSignal(on(type)))
        assertEquals(expected, created.task.severity)
        assertIs<CarTaskUpdate.Resolved>(classifier.onSignal(off(type)))
    }

    @Test
    fun `연료·배터리·워셔액 해결 장소`() {
        fun places(t: TelltaleType) = CarTaskCatalog.info(CarTaskKind.Warning(t)).fixPlaces
        assertEquals(listOf(PlaceCategory.GAS_STATION), places(TelltaleType.LOW_FUEL))
        assertEquals(listOf(PlaceCategory.EV_CHARGING), places(TelltaleType.LOW_EV_BATTERY))
        assertEquals(PlaceCategory.GAS_STATION, places(TelltaleType.WASHER_FLUID_LOW).first())
        assertTrue(CarTaskCatalog.info(CarTaskKind.Warning(TelltaleType.BRAKE_FLUID_LOW)).stopFirst)
    }
}
