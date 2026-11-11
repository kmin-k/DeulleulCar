package ai.deulleulcar.core.route

import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.plan.GridEstimator
import ai.deulleulcar.core.route.Fixtures.destination
import ai.deulleulcar.core.route.Fixtures.evening
import ai.deulleulcar.core.route.Fixtures.hours
import ai.deulleulcar.core.route.Fixtures.origin
import ai.deulleulcar.core.route.Fixtures.place
import ai.deulleulcar.core.route.Fixtures.request
import ai.deulleulcar.core.route.Fixtures.route
import java.time.Duration
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RouteOptimizerTest {
    private val estimator = HaversineEstimator()
    private val optimizer = RouteOptimizer(estimator)

    private fun ids(plan: RoutePlan) = plan.stops.map { it.stop.place.id }

    @Test
    fun `가까운 순서대로 편성한다`() {
        val mart = place("mart", 0.02, category = PlaceCategory.MART)
        val pharmacy = place("pharmacy", 0.06, category = PlaceCategory.PHARMACY)
        val plan = optimizer.optimize(route(request("p", pharmacy), request("m", mart)))
        assertEquals(listOf("mart", "pharmacy"), ids(plan))
        assertTrue(plan.reorderReasons.isEmpty())
        assertTrue(plan.excluded.isEmpty())
    }

    @Test
    fun `영업 종료가 임박한 정류지를 앞으로 당기고 이유를 남긴다`() {
        // 19:00 종료, 마감 10분 전(18:50)까지 도착해야 함. 마트 먼저면 약국 18:51쯤, 약국 먼저면 18:46쯤.
        val mart = place("mart", 0.02, category = PlaceCategory.MART, hours = hours(10, 23))
        val pharmacy = place("pharmacy", 0.06, category = PlaceCategory.PHARMACY, hours = hours(9, 19))
        val plan = optimizer.optimize(route(request("m", mart), request("p", pharmacy)))

        assertEquals(listOf("pharmacy", "mart"), ids(plan))
        val reason = assertIs<ReorderReason.ClosesSoon>(plan.reorderReasons.single())
        assertEquals("pharmacy", reason.placeId)
        assertEquals(evening.toLocalDate().atTime(19, 0), reason.closingTime)
        assertEquals("약국이 7시에 닫아서 먼저 갈게요.", RouteSpeech.reorderReason(reason))
        assertTrue(plan.stops.first().arrival.toLocalTime() < LocalTime.of(18, 50))
        assertEquals(evening.toLocalDate().atTime(19, 0), plan.stops.first().closesAt)
    }

    @Test
    fun `아직 열지 않은 곳은 뒤로 미룬다`() {
        // 출발 07:50. 빵집은 08:00 개점이라 바로 가면 닫혀 있음 → 세탁소 먼저.
        val bakery = place("bakery", 0.01, category = PlaceCategory.BAKERY, hours = hours(8, 20))
        val laundry = place("laundry", 0.05, category = PlaceCategory.LAUNDRY)
        val plan = optimizer.optimize(
            route(request("b", bakery), request("l", laundry), departAt = evening.withHour(7).withMinute(50)),
        )
        assertEquals(listOf("laundry", "bakery"), ids(plan))
        assertIs<ReorderReason.OpensLater>(plan.reorderReasons.single())
    }

    @Test
    fun `직행 대비 +N분을 반올림해서 계산한다`() {
        val onTheWay = place("a", 0.05)
        val straight = optimizer.optimize(route(request("a", onTheWay)))
        assertEquals(0, straight.extraDriveMinutes)
        assertEquals(5, straight.extraTotalMinutes) // 체류 5분

        val offAxis = place("b", 0.05, y = 0.02)
        val plan = optimizer.optimize(route(request("b", offAxis)))
        val p = offAxis.point
        val expected = (
            (estimator.travelTime(origin, p) + estimator.travelTime(p, destination) - estimator.travelTime(origin, destination))
                .seconds / 60.0
            ).roundToInt()
        assertTrue(expected > 0)
        assertEquals(expected, plan.extraDriveMinutes)
        assertEquals(estimator.travelTime(origin, destination), plan.directDriveTime)
        assertEquals(plan.driveTime + Duration.ofMinutes(5), plan.totalTime)
        assertEquals(plan.departAt + plan.totalTime, plan.arrivalAtDestination)
    }

    @Test
    fun `후보가 여러 곳이면 가까운 곳을 고른다`() {
        val far = place("far", 0.05, y = 0.04, category = PlaceCategory.PHARMACY)
        val near = place("near", 0.05, y = 0.002, category = PlaceCategory.PHARMACY)
        val plan = optimizer.optimize(route(request("p", far, near)))
        assertEquals(listOf("near"), ids(plan))
        assertEquals("p", plan.stops.single().requestId)
    }

    @Test
    fun `가까운 후보가 닫혀 있으면 열린 먼 후보를 고른다`() {
        val far = place("far", 0.05, y = 0.04, category = PlaceCategory.PHARMACY, hours = hours(9, 22))
        val near = place("near", 0.05, y = 0.002, category = PlaceCategory.PHARMACY, hours = hours(9, 18))
        val plan = optimizer.optimize(route(request("p", near, far)))
        assertEquals(listOf("far"), ids(plan))
    }

    @Test
    fun `모두 들를 수 없으면 직행 계획과 제외 이유를 돌려준다`() {
        val a = place("a", 0.03, hours = hours(9, 18))
        val b = place("b", 0.06, hours = hours(9, 17))
        val plan = optimizer.optimize(route(request("a", a), request("b", b), StopRequest("c", emptyList())))

        assertTrue(plan.stops.isEmpty())
        assertEquals(
            listOf("a" to ExclusionReason.CLOSED, "b" to ExclusionReason.CLOSED, "c" to ExclusionReason.NO_CANDIDATES),
            plan.excluded.map { it.request.id to it.reason },
        )
        assertEquals(0, plan.extraDriveMinutes)
        assertEquals(plan.directDriveTime, plan.driveTime)
        assertEquals("바로 목적지로 갈게요.", RouteSpeech.summary(plan))
    }

    @Test
    fun `함께 들를 수 없는 볼일은 하나를 빼고 CONFLICT로 알린다`() {
        // 둘 다 18:55 종료(18:45까지 도착). 하나는 앞쪽, 하나는 출발지 뒤쪽이라 둘 다 시간 안에 못 감.
        val ahead = place("ahead", 0.04, hours = hours(9, 18, 55))
        val behind = place("behind", -0.04, hours = hours(9, 18, 55))
        val plan = optimizer.optimize(route(request("ahead", ahead), request("behind", behind)))
        assertEquals(listOf("ahead"), ids(plan)) // 경로 앞쪽이 총 소요가 작다
        assertEquals(ExclusionReason.CONFLICT, plan.excluded.single().reason)
        assertEquals("behind", plan.excluded.single().request.id)
    }

    @Test
    fun `고정한 볼일은 첫 정류지로 둔다`() {
        val mart = place("mart", 0.02, category = PlaceCategory.MART)
        val gas = place("gas", 0.07, category = PlaceCategory.GAS_STATION)
        val plan = optimizer.optimize(route(request("m", mart), request("g", gas), pinned = "g"))
        assertEquals(listOf("gas", "mart"), ids(plan))
    }

    @Test
    fun `정류지 상한을 넘는 볼일은 제외한다`() {
        val reqs = (1..6).map { request("r$it", place("p$it", it * 0.01)) }.toTypedArray()
        val plan = optimizer.optimize(route(*reqs))
        assertEquals(5, plan.stops.size)
        assertEquals(ExclusionReason.TOO_MANY_STOPS, plan.excluded.single().reason)
        assertEquals("r6", plan.excluded.single().request.id)
    }

    @Test
    fun `정류지 5곳에 후보가 여러 곳이어도 빠르게 최적 순서를 낸다`() {
        val categories = listOf(
            PlaceCategory.PHARMACY, PlaceCategory.MART, PlaceCategory.LAUNDRY, PlaceCategory.BANK, PlaceCategory.BAKERY,
        )
        val reqs = categories.mapIndexed { i, cat ->
            val places = (0 until 5).map { k ->
                place("$cat-$k", 0.015 * (i + 1) + 0.003 * k, y = 0.004 * (k - 2), category = cat, hours = hours(9, 22))
            }
            request(cat.name, *places.toTypedArray())
        }.reversed()
        val started = System.nanoTime()
        val plan = optimizer.optimize(route(*reqs.toTypedArray()))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(5, plan.stops.size)
        assertEquals(categories, plan.stops.map { it.stop.place.category }) // 동쪽으로 가며 차례대로
        assertTrue(plan.stops.all { it.stop.place.id.endsWith("-2") }) // 경로 축 위(y=0) 후보
        assertTrue(elapsedMs < 1_000, "5곳 최적화 ${elapsedMs}ms")
    }

    @Test
    fun `마감 10분 전까지 도착하면 들르고, 9분 전이면 제외한다`() {
        // 격자 추정기: 약국까지 정확히 3분 → 18:33 도착
        val grid = RouteOptimizer(GridEstimator)
        val ok = place("ok", 0.03, category = PlaceCategory.PHARMACY, hours = hours(9, 18, 43)) // 10분 전
        assertEquals(listOf("ok"), ids(grid.optimize(route(request("p", ok)))))

        val late = place("late", 0.03, category = PlaceCategory.PHARMACY, hours = hours(9, 18, 42)) // 9분 전
        val plan = grid.optimize(route(request("p", late)))
        assertTrue(plan.stops.isEmpty())
        assertEquals(ExclusionReason.CLOSED, plan.excluded.single().reason)

        // 여유 0분으로 바꾸면 같은 곳도 들른다
        val noMargin = RouteOptimizer(GridEstimator, RouteOptions(closingMargin = Duration.ZERO))
        assertEquals(listOf("late"), ids(noMargin.optimize(route(request("p", late)))))
    }

    @Test
    fun `마감 여유 때문에 순서가 바뀐다`() {
        // 격자 추정기 + 체류 8분. 약국 18:50 종료 → 18:40까지 도착해야 함.
        // 마트 먼저: 약국 18:31 + 8 + 2 = 18:41 (여유 0분이면 허용, 10분이면 불가). 약국 먼저: 18:33.
        val dwell8 = object : TravelTimeEstimator by GridEstimator {
            override fun dwellTime(place: ai.deulleulcar.core.model.Place) = Duration.ofMinutes(8)
        }
        val mart = place("mart", 0.01, category = PlaceCategory.MART)
        val pharmacy = place("pharmacy", 0.03, category = PlaceCategory.PHARMACY, hours = hours(9, 18, 50))
        val req = route(request("m", mart), request("p", pharmacy))

        val noMargin = RouteOptimizer(dwell8, RouteOptions(closingMargin = Duration.ZERO)).optimize(req)
        assertEquals(listOf("mart", "pharmacy"), ids(noMargin))

        val plan = RouteOptimizer(dwell8).optimize(req)
        assertEquals(listOf("pharmacy", "mart"), ids(plan))
        assertIs<ReorderReason.ClosesSoon>(plan.reorderReasons.single())
    }
}
