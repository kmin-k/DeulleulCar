package ai.deulleulcar.core.model

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    // 2026-11-11은 수요일
    private val wed = LocalDateTime.of(2026, 11, 11, 18, 40)

    @Test
    fun `영업 중 판정과 종료 시각`() {
        val hours = OpeningHours.everyDay(LocalTime.of(9, 0), LocalTime.of(19, 0))
        assertTrue(hours.isOpenAt(wed))
        assertEquals(wed.withHour(19).withMinute(0), hours.closingTimeAt(wed))
        assertFalse(hours.isOpenAt(wed.withHour(19).withMinute(0)))
        assertFalse(hours.isOpenAt(wed.withHour(8)))
    }

    @Test
    fun `자정을 넘기는 영업`() {
        val hours = OpeningHours(mapOf(DayOfWeek.WEDNESDAY to listOf(TimeRange(LocalTime.of(18, 0), LocalTime.of(2, 0)))))
        assertEquals(wed.toLocalDate().plusDays(1).atTime(2, 0), hours.closingTimeAt(wed))
        val thuEarly = LocalDateTime.of(2026, 11, 12, 1, 30)
        assertTrue(hours.isOpenAt(thuEarly))
        assertFalse(hours.isOpenAt(thuEarly.withHour(3)))
    }

    @Test
    fun `24시간 영업과 휴무일`() {
        assertTrue(OpeningHours.always().isOpenAt(wed.withHour(3)))
        val closedWed = OpeningHours(mapOf(DayOfWeek.MONDAY to listOf(TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0)))))
        assertNull(closedWed.closingTimeAt(wed.withHour(10)))
    }

    @Test
    fun `차의 할 일 정류지는 task id가 필요`() {
        val place = Place("p1", "테스트 주유소", 37.5, 127.0, PlaceCategory.GAS_STATION)
        assertFailsWith<IllegalArgumentException> { Stop(place, StopSource.CAR) }
        assertEquals("car-1", Stop(place, StopSource.CAR, linkedTaskId = "car-1").linkedTaskId)
    }
}
