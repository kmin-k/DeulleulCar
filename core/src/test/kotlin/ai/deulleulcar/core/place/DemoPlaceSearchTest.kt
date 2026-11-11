package ai.deulleulcar.core.place

import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.HoursSource
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DemoPlaceSearchTest {
    private val seed = DemoSeed.default
    private val hours = CompositeHoursProvider.demoDefault()
    private val search = FallbackPlaceSearch(null, DemoPlaceSearch(seed), hours)
    private val wed1840 = LocalDateTime.of(2026, 11, 11, 18, 40) // 시나리오 1: 평일 18:40 회사 출발

    @Test
    fun `시드 데이터 구성`() {
        val byCat = seed.places.groupBy { it.category }
        assertTrue(byCat[PlaceCategory.PHARMACY]!!.size in 2..3)
        assertTrue(byCat[PlaceCategory.MART]!!.size in 2..3)
        assertEquals(2, byCat[PlaceCategory.LAUNDRY]!!.size)
        assertEquals(1, byCat[PlaceCategory.CONVENIENCE_STORE]!!.size)
        assertEquals(1, byCat[PlaceCategory.BANK]!!.size)
        assertEquals(2, byCat[PlaceCategory.GAS_STATION]!!.size)
        assertTrue(byCat[PlaceCategory.GAS_STATION]!!.all { Amenity.AIR_PUMP in seed.amenities[it.id].orEmpty() })
        assertTrue(byCat[PlaceCategory.REPAIR_SHOP]!!.size + byCat[PlaceCategory.TIRE_SHOP].orEmpty().size in 1..2)
        assertEquals(1, byCat[PlaceCategory.EV_CHARGING]!!.size)

        // 회사–집 3~5km
        val commute = seed.work.distanceTo(seed.home)
        assertTrue(commute in 3_000.0..5_000.0, "회사–집 거리 $commute")
        // 모든 장소가 데모 구역(회사·집 주변 6km) 안
        assertTrue(seed.places.all { it.point.distanceTo(seed.work) < 6_000 })
        // UX 시안 상호
        val names = seed.places.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("역삼 행복약국", "선릉 생활마트", "역삼 셀프주유소", "역삼 자동차정비")))
    }

    @Test
    fun `모든 데모 장소에 큐레이션 영업시간이 있다`() {
        seed.places.forEach { assertEquals(HoursSource.CURATED, hours.hoursFor(it)?.source, it.name) }
    }

    @Test
    fun `시나리오 1 전제 - 평일 퇴근길에 약국이 마트보다 먼저 닫는다`() = runTest {
        val pharmacies = CorridorSearch(search).search(
            PlaceQuery(PlaceCategory.PHARMACY, seed.work, radiusM = 1_000), seed.work, seed.home,
        ).places
        val marts = CorridorSearch(search).search(
            PlaceQuery(PlaceCategory.MART, seed.work, radiusM = 1_000), seed.work, seed.home,
        ).places
        assertTrue(pharmacies.isNotEmpty() && marts.isNotEmpty())
        fun close(p: Place) = p.openingHours!!.closingTimeAt(wed1840)!!
        // 18:40에 모두 영업 중이고, 약국 중 가장 늦게 닫는 곳도 마트 중 가장 일찍 닫는 곳보다 먼저 닫는다
        (pharmacies + marts).forEach { assertTrue(it.openingHours!!.isOpenAt(wed1840), it.name) }
        assertTrue(pharmacies.maxOf(::close) < marts.minOf(::close))
        val happy = pharmacies.first { it.name == "역삼 행복약국" }
        assertEquals(wed1840.withHour(19).withMinute(0), close(happy))
    }

    @Test
    fun `업종 검색은 가까운 순이고 반경을 지킨다`() = runTest {
        val result = search.search(PlaceQuery(PlaceCategory.PHARMACY, seed.work, radiusM = 5_000))
        assertEquals(PlaceDataSource.DEMO, result.source)
        assertEquals("역삼 행복약국", result.places.first().name)
        val d = result.places.map { it.point.distanceTo(seed.work) }
        assertEquals(d.sorted(), d)
        val near = search.search(PlaceQuery(PlaceCategory.PHARMACY, seed.work, radiusM = 500))
        assertEquals(listOf("역삼 행복약국"), near.places.map { it.name })
    }

    @Test
    fun `상호를 말하면 그 상호, 없는 상호면 같은 업종`() = runTest {
        val named = search.search(PlaceQuery(PlaceCategory.PHARMACY, seed.work, keyword = "늘봄약국", radiusM = 10_000))
        assertEquals(listOf("대치 늘봄약국"), named.places.map { it.name })
        val unknown = search.search(PlaceQuery(PlaceCategory.PHARMACY, seed.work, keyword = "없는약국", radiusM = 10_000))
        assertEquals(3, unknown.places.size)
    }

    @Test
    fun `필수 속성으로 거른다`() = runTest {
        val air = search.search(
            PlaceQuery(PlaceCategory.TIRE_SHOP, seed.work, radiusM = 10_000, requiredAmenities = setOf(Amenity.AIR_PUMP)),
        )
        assertEquals(listOf("대치 타이어"), air.places.map { it.name })
        assertEquals(setOf(Amenity.AIR_PUMP), air.amenitiesOf(air.places.first()))
        val fast = search.search(
            PlaceQuery(PlaceCategory.GAS_STATION, seed.work, radiusM = 10_000, requiredAmenities = setOf(Amenity.FAST_CHARGER)),
        )
        assertTrue(fast.places.isEmpty())
    }

    @Test
    fun `경로 근처 검색은 중복을 없애고 직선에 가까운 순으로 낸다`() = runTest {
        val result = CorridorSearch(search).search(
            PlaceQuery(PlaceCategory.GAS_STATION, seed.work, radiusM = 1_500, requiredAmenities = setOf(Amenity.AIR_PUMP)),
            seed.work, seed.home, samples = 5,
        )
        assertEquals(5, result.samplePoints.size)
        assertEquals(result.places.map { it.id }.distinct(), result.places.map { it.id })
        assertEquals(setOf("역삼 셀프주유소", "선릉 주유소"), result.places.map { it.name }.toSet())
        assertEquals(result.hits.map { it.offsetM }.sorted(), result.hits.map { it.offsetM })
        assertTrue(result.places.all { Amenity.AIR_PUMP in result.amenitiesOf(it) })
        assertEquals(PlaceDataSource.DEMO, result.source)
    }

    @Test
    fun `직선 기하 계산`() {
        val a = GeoPoint(37.5, 127.0)
        val b = GeoPoint(37.5, 127.01) // 동쪽 약 885m
        assertTrue(a.distanceTo(b) in 870.0..900.0)
        val north = GeoPoint(37.5009, 127.005) // 선분 가운데에서 북쪽 약 100m
        assertTrue(CorridorSearch.crossTrackDistanceM(north, a, b) in 95.0..105.0)
        assertEquals(0.5, CorridorSearch.progress(north, a, b), 0.01)
        assertEquals(listOf(a, b), CorridorSearch.samplePoints(a, b, 2))
        assertEquals(2, CorridorSearch.autoSampleCount(500.0, 1000))
        assertEquals(8, CorridorSearch.autoSampleCount(100_000.0, 1000))
        assertFailsWith<IllegalArgumentException> { CorridorSearch.samplePoints(a, b, 1) }
    }

    @Test
    fun `검색 조건 검증`() {
        assertFailsWith<IllegalArgumentException> { PlaceQuery(PlaceCategory.PHARMACY, seed.work, radiusM = 30_000) }
        assertFailsWith<IllegalArgumentException> { PlaceQuery(PlaceCategory.PHARMACY, seed.work, limit = 16) }
        assertFailsWith<IllegalArgumentException> { PlaceQuery(PlaceCategory.OTHER, seed.work) }
    }
}
