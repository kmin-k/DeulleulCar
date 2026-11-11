package ai.deulleulcar.core.place

import ai.deulleulcar.core.model.HoursSource
import ai.deulleulcar.core.model.OpeningHours
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HoursProviderTest {
    // 2026-11-11 수요일, 2026-11-14 토요일, 2026-11-15 일요일
    private val wed1840 = LocalDateTime.of(2026, 11, 11, 18, 40)
    private val sat = LocalDateTime.of(2026, 11, 14, 12, 0)
    private val sun = LocalDateTime.of(2026, 11, 15, 12, 0)

    private fun place(id: String, name: String, category: PlaceCategory) = Place(id, name, 37.5, 127.04, category)

    @Test
    fun `업종 기본값은 CATEGORY_DEFAULT로 표시된다`() {
        val hours = CategoryDefaultHours.hoursFor(place("x", "어느 약국", PlaceCategory.PHARMACY))!!
        assertEquals(HoursSource.CATEGORY_DEFAULT, hours.source)
        assertTrue(hours.isOpenAt(wed1840))
        assertEquals(wed1840.withHour(19).withMinute(0), hours.closingTimeAt(wed1840))
        assertTrue(hours.isOpenAt(sat))
        assertFalse(hours.isOpenAt(sun))
        assertNull(CategoryDefaultHours.hoursFor(place("o", "기타", PlaceCategory.OTHER)))
        // OTHER 말고는 모든 업종에 기본값이 있다
        PlaceCategory.entries.filter { it != PlaceCategory.OTHER }.forEach {
            assertTrue(CategoryDefaultHours.forCategory(it) != null, "기본값 없음: $it")
        }
        assertTrue(CategoryDefaultHours.forCategory(PlaceCategory.CONVENIENCE_STORE)!!.isOpenAt(wed1840.withHour(3)))
        assertFalse(CategoryDefaultHours.forCategory(PlaceCategory.BANK)!!.isOpenAt(wed1840))
    }

    @Test
    fun `큐레이션 JSON 파싱과 요일 키 우선순위`() {
        val provider = CuratedHoursProvider.fromJson(
            """{"entries":[
              {"id":"a","hours":{"everyday":["10:00-22:00"],"sun":[]}},
              {"id":"b","name":"가상 포차","hours":{"weekdays":["18:00-02:00"],"sat":["18:00-04:00"]}},
              {"id":"c","hours":{"everyday":["00:00-24:00"]}},
              {"id":"d","hours":{"weekdays":["09:00-12:00","13:00-18:00"]}}
            ]}""",
        )
        val a = provider.hoursFor(place("a", "?", PlaceCategory.MART))!!
        assertEquals(HoursSource.CURATED, a.source)
        assertTrue(a.isOpenAt(sat))
        assertFalse(a.isOpenAt(sun)) // 빈 배열 = 휴무

        // 자정 넘김: 수요일 18시 시작 → 목요일 02시 종료
        val b = provider.hoursFor(place("b", "?", PlaceCategory.OTHER))!!
        assertEquals(LocalDateTime.of(2026, 11, 12, 2, 0), b.closingTimeAt(wed1840))
        assertTrue(b.isOpenAt(LocalDateTime.of(2026, 11, 12, 1, 30)))
        assertFalse(b.isOpenAt(LocalDateTime.of(2026, 11, 12, 2, 30)))
        // 토요일 밤 시작 → 일요일 새벽 4시까지
        assertTrue(b.isOpenAt(LocalDateTime.of(2026, 11, 15, 3, 59)))

        val c = provider.hoursFor(place("c", "?", PlaceCategory.CONVENIENCE_STORE))!!
        assertTrue(c.isOpenAt(wed1840.withHour(3)))
        assertTrue(c.isOpenAt(wed1840.withHour(23).withMinute(59)))

        val d = provider.hoursFor(place("d", "?", PlaceCategory.BANK))!!
        assertFalse(d.isOpenAt(wed1840.withHour(12).withMinute(30))) // 점심 휴게
        assertEquals(wed1840.withHour(18).withMinute(0), d.closingTimeAt(wed1840.withHour(15)))

        // id가 다르면 상호명으로 찾는다
        assertEquals(b, provider.hoursFor(place("kakao:9", "가상 포차", PlaceCategory.OTHER)))
        assertNull(provider.hoursFor(place("z", "없는 곳", PlaceCategory.OTHER)))
    }

    @Test
    fun `잘못된 큐레이션 형식은 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            CuratedHoursProvider.fromJson("""{"entries":[{"id":"a","hours":{"monday":["09:00-18:00"]}}]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            CuratedHoursProvider.fromJson("""{"entries":[{"id":"a","hours":{"mon":["0900~1800"]}}]}""")
        }
    }

    @Test
    fun `합성 제공자는 큐레이션을 우선하고 없으면 기본값`() {
        val curated = CuratedHoursProvider.fromJson("""{"entries":[{"id":"p1","hours":{"weekdays":["09:00-18:00"]}}]}""")
        val composite = CompositeHoursProvider(curated, CategoryDefaultHours)
        val curatedHours = composite.hoursFor(place("p1", "가상약국", PlaceCategory.PHARMACY))!!
        assertEquals(HoursSource.CURATED, curatedHours.source)
        assertFalse(curatedHours.isOpenAt(wed1840)) // 기본값(19시)이 아니라 큐레이션(18시)
        val fallback = composite.hoursFor(place("p2", "다른 약국", PlaceCategory.PHARMACY))!!
        assertEquals(HoursSource.CATEGORY_DEFAULT, fallback.source)
        assertTrue(fallback.isOpenAt(wed1840))
    }

    @Test
    fun `이미 영업시간이 있는 장소는 덮어쓰지 않는다`() {
        val known = OpeningHours.everyDay(LocalTime.of(7, 0), LocalTime.of(8, 0), HoursSource.CURATED)
        val p = place("p", "가상", PlaceCategory.PHARMACY).copy(openingHours = known)
        assertSame(p, p.withHoursFrom(CategoryDefaultHours))
        assertEquals(
            HoursSource.CATEGORY_DEFAULT,
            place("q", "가상", PlaceCategory.MART).withHoursFrom(CategoryDefaultHours).openingHours!!.source,
        )
    }
}
