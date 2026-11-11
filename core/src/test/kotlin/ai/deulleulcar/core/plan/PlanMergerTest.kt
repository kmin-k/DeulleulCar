package ai.deulleulcar.core.plan

import ai.deulleulcar.core.cartask.CarTaskClassifier
import ai.deulleulcar.core.cartask.CarTaskUpdate
import ai.deulleulcar.core.model.CarSignal
import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.CarTaskStatus
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.model.TelltaleState
import ai.deulleulcar.core.model.TelltaleType
import ai.deulleulcar.core.route.Fixtures
import ai.deulleulcar.core.route.Fixtures.hours
import ai.deulleulcar.core.route.Fixtures.place
import ai.deulleulcar.core.route.Fixtures.request
import ai.deulleulcar.core.route.Fixtures.route
import ai.deulleulcar.core.route.GeoPoint
import ai.deulleulcar.core.route.RouteOptimizer
import ai.deulleulcar.core.route.RoutePlan
import ai.deulleulcar.core.route.TravelTimeEstimator
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 격자 도시: 위경도 0.01도 = 1분, 체류 0분. 우회 시간을 정확히 맞추기 위한 가짜 추정기. */
object GridEstimator : TravelTimeEstimator {
    override fun travelTime(from: GeoPoint, to: GeoPoint): Duration =
        Duration.ofSeconds(((abs(from.lat - to.lat) + abs(from.lon - to.lon)) * 100 * 60).roundToLong())

    override fun dwellTime(place: Place): Duration = Duration.ZERO
}

class PlanMergerTest {
    private val optimizer = RouteOptimizer(GridEstimator)
    private val engine = SuggestionEngine(optimizer)

    // 회사(x=0) → 약국(0.03) → 마트(0.07) → 집(0.1). 직행 10분.
    private val pharmacy = place("pharmacy", 0.03, category = PlaceCategory.PHARMACY)
    private val mart = place("mart", 0.07, category = PlaceCategory.MART)
    private val plan: RoutePlan = optimizer.optimize(route(request("p", pharmacy), request("m", mart)))

    private val at = Instant.parse("2026-11-11T09:00:00Z")
    private fun task(id: String, severity: Severity, kind: CarTaskKind = CarTaskKind.Warning(TelltaleType.TPMS)) =
        CarTask(id, kind, severity, at)

    /** 경로 축에서 북쪽으로 [offset]도 떨어진 주유소 → 우회 2 × offset × 100분. */
    private fun gas(id: String, x: Double, offset: Double, hours: ai.deulleulcar.core.model.OpeningHours? = null) =
        place(id, x, y = offset, category = PlaceCategory.GAS_STATION, hours = hours)

    private fun suggest(task: CarTask, vararg places: Place) = engine.suggest(plan, listOf(task)) { places.toList() }

    @Test
    fun `거절하면 계획은 그대로다`() {
        val classifier = CarTaskClassifier()
        val created = assertIs<CarTaskUpdate.Created>(
            classifier.onSignal(CarSignal.Telltale(TelltaleType.TPMS, TelltaleState.ON)),
        ).task
        val merger = PlanMerger.withClassifier(classifier, optimizer)
        val suggestion = engine.suggest(plan, classifier.tasks) { listOf(gas("g", 0.05, 0.01)) }.single()

        val after = merger.decline(plan, suggestion)
        assertSame(plan, after)
        assertEquals(listOf("pharmacy", "mart"), after.stops.map { it.stop.place.id })
        assertTrue(after.stops.none { it.stop.source == StopSource.CAR })
        assertEquals(created.id, merger.declines.single().taskId)
        assertEquals(CarTaskStatus.SNOOZED, classifier.find(created.id)!!.status)
    }

    @Test
    fun `추천만으로는 계획이 바뀌지 않는다`() {
        val before = plan.copy()
        suggest(task("t", Severity.RECOMMEND), gas("g", 0.05, 0.01))
        assertEquals(before, plan)
    }

    @Test
    fun `수락하면 CAR 정류지를 넣고 순서를 다시 계산한다`() {
        val classifier = CarTaskClassifier()
        val t = assertIs<CarTaskUpdate.Created>(
            classifier.onSignal(CarSignal.Telltale(TelltaleType.TPMS, TelltaleState.ON)),
        ).task
        val merger = PlanMerger.withClassifier(classifier, optimizer)
        val suggestion = engine.suggest(plan, classifier.tasks) { listOf(gas("g", 0.05, 0.01)) }.single()
        assertEquals(2, suggestion.detourMinutes)

        val merged = assertNotNull(merger.accept(plan, suggestion))
        assertEquals(listOf("pharmacy", "g", "mart"), merged.stops.map { it.stop.place.id })
        val carStop = merged.stops[1].stop
        assertEquals(StopSource.CAR, carStop.source)
        assertEquals(t.id, carStop.linkedTaskId)
        assertEquals(CarTaskStatus.ACCEPTED, classifier.find(t.id)!!.status)
        assertEquals(suggestion.preview, merged)
        assertEquals(plan.extraDriveMinutes + 2, merged.extraDriveMinutes)

        // 이미 계획에 들어간 할 일은 다시 추천하지 않는다
        assertTrue(engine.suggest(merged, listOf(t.copy(status = CarTaskStatus.OPEN))) { listOf(gas("g", 0.05, 0.01)) }.isEmpty())
    }

    @Test
    fun `권장은 우회 5분까지 추천하고 6분이면 추천하지 않는다`() {
        val t = task("t", Severity.RECOMMEND)
        val five = suggest(t, gas("g5", 0.05, 0.025)).single()
        assertEquals(5, five.detourMinutes)
        assertEquals(SuggestionStrength.CARD, five.strength)
        assertTrue(suggest(t, gas("g6", 0.05, 0.03)).isEmpty())
    }

    @Test
    fun `보류는 우회 3분까지만 추천하고 4분이면 추천하지 않는다`() {
        val t = task("t", Severity.HOLD, CarTaskKind.PeriodicInspection)
        val three = suggest(t, gas("g3", 0.05, 0.015)).single()
        assertEquals(3, three.detourMinutes)
        assertEquals(SuggestionStrength.SOFT, three.strength)
        assertTrue(suggest(t, gas("g4", 0.05, 0.02)).isEmpty())
    }

    @Test
    fun `긴급은 우회와 무관하게 첫 정류지로 강하게 추천하고 목록 맨 앞에 온다`() {
        val urgent = task("u", Severity.URGENT, CarTaskKind.LowRange)
        val recommend = task("r", Severity.RECOMMEND)
        val far = gas("far", 0.09, 0.05) // 첫 정류지로 넣으면 크게 돌아감
        val near = gas("near", 0.05, 0.005)
        val list = engine.suggest(plan, listOf(recommend, urgent)) { if (it.id == "u") listOf(far) else listOf(near) }

        assertEquals(listOf("u", "r"), list.map { it.task.id })
        val strong = list.first()
        assertEquals(SuggestionStrength.STRONG, strong.strength)
        assertTrue(strong.insertFirst)
        assertTrue(strong.detourMinutes > 5)
        assertEquals("far", strong.preview.stops.first().stop.place.id)

        // 긴급 수락 후 권장을 수락해도 긴급 정류지는 첫 자리에 남는다
        val merger = PlanMerger(optimizer)
        val withUrgent = assertNotNull(merger.accept(plan, strong))
        assertEquals("far", withUrgent.stops.first().stop.place.id)
        val next = engine.suggest(withUrgent, listOf(recommend)) { listOf(near) }.single()
        val merged = assertNotNull(merger.accept(withUrgent, next))
        assertEquals("far", merged.stops.first().stop.place.id)
        assertEquals(4, merged.stops.size)
    }

    @Test
    fun `해결지 후보가 여러 곳이면 우회가 가장 작은 곳을 고른다`() {
        val s = suggest(task("t", Severity.RECOMMEND), gas("a", 0.05, 0.02), gas("b", 0.05, 0.005)).single()
        assertEquals("b", s.place.id)
        assertEquals(1, s.detourMinutes)
    }

    @Test
    fun `기존 볼일과 함께 들를 수 없는 해결지는 권장으로 추천하지 않는다`() {
        // 약국 18:33 도착, 18:35 종료. 주유소(18:34 종료)를 어느 순서로 넣어도 둘 중 하나는 닫힌다.
        val tight = place("pharmacy", 0.03, category = PlaceCategory.PHARMACY, hours = hours(9, 18, 35))
        val tightPlan = optimizer.optimize(route(request("p", tight), request("m", mart)))
        assertEquals(2, tightPlan.stops.size)
        val conflicting = gas("g", 0.02, 0.01, hours = hours(9, 18, 34))
        val t = task("t", Severity.RECOMMEND)
        assertTrue(engine.suggest(tightPlan, listOf(t)) { listOf(conflicting) }.isEmpty())
        assertNull(engine.evaluate(tightPlan, t, conflicting)) // 기존 볼일을 지키고 해결지를 뺀다
    }

    @Test
    fun `긴급 해결지 때문에 빠지는 볼일은 displaced로 알려 준다`() {
        val tight = place("pharmacy", 0.03, category = PlaceCategory.PHARMACY, hours = hours(9, 18, 35))
        val tightPlan = optimizer.optimize(route(request("p", tight), request("m", mart)))
        val urgent = task("u", Severity.URGENT, CarTaskKind.LowRange)
        val s = engine.suggest(tightPlan, listOf(urgent)) { listOf(gas("g", 0.02, 0.03)) }.single()
        assertEquals("g", s.preview.stops.first().stop.place.id)
        assertEquals(listOf("p"), s.displaced.map { it.request.id })
    }

    @Test
    fun `닫힌 해결지만 있으면 추천하지 않고 수락도 null`() {
        val closed = gas("closed", 0.05, 0.005, hours = hours(6, 9))
        val t = task("t", Severity.URGENT, CarTaskKind.LowRange)
        assertTrue(suggest(t, closed).isEmpty())
        assertNull(engine.evaluate(plan, t, closed))
    }

    @Test
    fun `수락·해소된 할 일은 추천 대상이 아니다`() {
        val g = gas("g", 0.05, 0.005)
        assertTrue(suggest(task("a", Severity.RECOMMEND).copy(status = CarTaskStatus.ACCEPTED), g).isEmpty())
        assertTrue(suggest(task("b", Severity.RECOMMEND).copy(status = CarTaskStatus.RESOLVED), g).isEmpty())
        assertEquals(1, suggest(task("c", Severity.RECOMMEND).copy(status = CarTaskStatus.SNOOZED), g).size)
    }

    @Test
    fun `주행 중 기회 제안은 한 주행에 같은 할 일 1회, 보류는 제안하지 않는다`() {
        val policy = OpportunityPolicy()
        val recommend = suggest(task("r", Severity.RECOMMEND), gas("g", 0.05, 0.01)).single()
        val hold = suggest(task("h", Severity.HOLD, CarTaskKind.PeriodicInspection), gas("g", 0.05, 0.01)).single()
        val urgent = suggest(task("u", Severity.URGENT, CarTaskKind.LowRange), gas("g", 0.05, 0.01)).single()

        assertNull(policy.next("trip-1", listOf(hold)))
        assertEquals("u", policy.next("trip-1", listOf(urgent, recommend, hold))?.task?.id)
        assertEquals("r", policy.next("trip-1", listOf(urgent, recommend, hold))?.task?.id)
        assertNull(policy.next("trip-1", listOf(urgent, recommend, hold)))

        assertEquals("u", policy.next("trip-2", listOf(urgent))?.task?.id) // 다음 주행엔 다시
        policy.endTrip("trip-1")
        assertEquals("r", policy.next("trip-1", listOf(recommend))?.task?.id)
    }

    @Test
    fun `주행 중 권장 제안도 우회 5분 경계를 지킨다`() {
        val policy = OpportunityPolicy()
        val five = suggest(task("r5", Severity.RECOMMEND), gas("g5", 0.05, 0.025)).single()
        assertTrue(policy.canOffer("trip", five))
        assertTrue(!policy.canOffer("trip", five.copy(detourMinutes = 6)))
    }
}
