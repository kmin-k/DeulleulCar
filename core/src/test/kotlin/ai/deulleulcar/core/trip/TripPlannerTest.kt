package ai.deulleulcar.core.trip

import ai.deulleulcar.core.llm.HybridErrandParser
import ai.deulleulcar.core.llm.ParseSource
import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.CarTaskStatus
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.model.TelltaleType
import ai.deulleulcar.core.place.DemoSeed
import ai.deulleulcar.core.place.PlaceDataSource
import ai.deulleulcar.core.place.PlaceSearchFactory
import ai.deulleulcar.core.plan.SuggestionStrength
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TripPlannerTest {
    private val seed = DemoSeed.default
    private val places = KnownPlaces(home = seed.home, work = seed.work)
    private val planner = TripPlanner(HybridErrandParser(llm = null), PlaceSearchFactory.create(kakaoRestApiKey = null))

    /** 2026-11-11은 수요일. 시나리오 1의 평일 18:40 출발. */
    private val weekday1840 = LocalDateTime.of(2026, 11, 11, 18, 40)

    private fun tpms(status: CarTaskStatus = CarTaskStatus.OPEN) =
        CarTask("car-1", CarTaskKind.Warning(TelltaleType.TPMS), Severity.RECOMMEND, Instant.EPOCH, status)

    @Test
    fun `시나리오 1 - 약국이 먼저 닫아서 앞으로 오고 집이 목적지`() = runTest {
        val result = planner.plan("퇴근길에 약국이랑 마트 들렀다 집에 가자", seed.work, places, weekday1840)
        val proposal = assertIs<PlanResult.Ready>(result).proposal
        assertEquals(ParseSource.RULE, proposal.parse.source)
        assertEquals(PlaceDataSource.DEMO, proposal.placeSource)
        assertEquals(listOf(PlaceCategory.PHARMACY, PlaceCategory.MART), proposal.plan.stops.map { it.stop.place.category })
        assertEquals(KnownPlaces.HOME_NAME, proposal.plan.destination.name)
        assertTrue(proposal.plan.extraDriveMinutes >= 0)
        assertTrue(proposal.suggestions.isEmpty())

        val speech = TripCopy.proposalSpeech(proposal.plan)
        assertTrue(speech.endsWith("출발할까요?"), speech)
        assertTrue(speech.contains("약국, 마트 순서로"), speech)
        assertTrue(speech.startsWith("약국이 7시에 닫아서 먼저 갈게요."), speech)
        assertEquals("19:00에 닫아서 먼저 들러요", TripCopy.stopMeta(proposal.plan.stops[0], proposal.plan))
        assertTrue(TripCopy.stopMeta(proposal.plan.stops[1], proposal.plan).endsWith(":00까지 영업"))
    }

    @Test
    fun `시나리오 2 - 정류지 3곳`() = runTest {
        val result = planner.plan("세탁소, 약국, 마트 들렀다 집", seed.work, places, weekday1840)
        val plan = assertIs<PlanResult.Ready>(result).proposal.plan
        assertEquals(3, plan.stops.size)
        assertEquals(3, plan.stops.map { it.stop.place.id }.toSet().size)
    }

    @Test
    fun `시나리오 3 - 타이어 저압은 계획에 넣지 않고 추천만 한다`() = runTest {
        val result = planner.plan(
            "퇴근길에 약국이랑 마트 들렀다 집에 가자", seed.work, places, weekday1840, suggestible = listOf(tpms()),
        )
        val proposal = assertIs<PlanResult.Ready>(result).proposal
        assertTrue(proposal.plan.stops.none { it.stop.source == StopSource.CAR }, "허락 전에는 계획에 없음")
        val s = proposal.suggestions.single()
        assertEquals(SuggestionStrength.CARD, s.strength)
        assertEquals(PlaceCategory.GAS_STATION, s.place.category)
        assertTrue(s.detourMinutes <= 5)
        assertTrue(s.preview.stops.any { it.stop.linkedTaskId == "car-1" })

        val speech = TripCopy.proposalSpeech(proposal.plan, s)
        assertTrue(speech.endsWith("들를까요?"), speech)
        assertTrue(speech.contains(s.place.name), speech)
    }

    @Test
    fun `미리 허락한 차의 할 일은 바로 계획에 들어간다`() = runTest {
        val result = planner.plan(
            "퇴근길에 약국 들러", seed.work, places, weekday1840, accepted = listOf(tpms(CarTaskStatus.ACCEPTED)),
        )
        val plan = assertIs<PlanResult.Ready>(result).proposal.plan
        assertTrue(plan.stops.any { it.stop.linkedTaskId == "car-1" && it.stop.source == StopSource.CAR })
    }

    @Test
    fun `볼일이 없으면 NoErrands`() = runTest {
        assertIs<PlanResult.NoErrands>(planner.plan("오늘 날씨 어때", seed.work, places, weekday1840))
    }

    @Test
    fun `출근길은 회사가 목적지`() = runTest {
        val result = planner.plan("출근길에 카페 들러", seed.home, places, weekday1840.withHour(8))
        val plan = when (result) {
            is PlanResult.Ready -> result.proposal.plan
            is PlanResult.NothingReachable -> result.plan
            else -> error("unexpected $result")
        }
        assertEquals(KnownPlaces.WORK_NAME, plan.destination.name)
    }

    @Test
    fun `주행 중 볼일 추가`() = runTest {
        val base = assertIs<PlanResult.Ready>(planner.plan("약국 들렀다 집", seed.work, places, weekday1840)).proposal.plan
        val (_, added) = planner.addErrands("마트 하나 더 들러", base, seed.work, weekday1840)
        val plan = added!!
        assertEquals(setOf(PlaceCategory.PHARMACY, PlaceCategory.MART), plan.stops.map { it.stop.place.category }.toSet())
    }

    @Test
    fun `긴급 해결 장소 - 직행 계획에서 가장 가까운 주유소`() = runTest {
        val task = CarTask("car-9", CarTaskKind.LowRange, Severity.URGENT, Instant.EPOCH, rangeKm = 25.0)
        val direct = planner.directPlan(seed.work, places.homePoint, weekday1840)
        val options = planner.fixOptions(task, direct, seed.work, weekday1840)
        assertTrue(options.isNotEmpty())
        assertTrue(options.first().preview.stops.first().stop.linkedTaskId == "car-9", "긴급은 첫 정류지")
        val speech = TripCopy.urgentSpeech(task, options.first())
        assertTrue(speech.startsWith("주행 가능 거리가 25km 정도 남았어요."), speech)
    }
}
