package ai.deulleulcar.core.navi

import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.navi.NaviCommand.AddWaypoint
import ai.deulleulcar.core.navi.NaviCommand.ClearWaypoints
import ai.deulleulcar.core.navi.NaviCommand.RequestRoute
import ai.deulleulcar.core.navi.NaviCommand.Speak
import ai.deulleulcar.core.navi.NaviEvent.DestinationArrived
import ai.deulleulcar.core.navi.NaviEvent.Start
import ai.deulleulcar.core.navi.NaviEvent.WaypointArrived
import ai.deulleulcar.core.route.Fixtures
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RollingWaypointMachineTest {
    private val categories = listOf(
        PlaceCategory.LAUNDRY, PlaceCategory.PHARMACY, PlaceCategory.MART, PlaceCategory.BANK, PlaceCategory.BAKERY,
    )

    private fun stops(n: Int): List<Stop> = (0 until n).map { i ->
        Fixtures.human(Fixtures.place("s$i", 0.01 * (i + 1), category = categories[i]))
    }

    private val dest = Fixtures.destination

    private fun machine(stops: List<Stop>, strategy: SlotSyncStrategy = SlotSyncStrategy.ReRequestRoute) =
        RollingWaypointMachine(dest, stops, strategy)

    private fun List<NaviCommand>.route() = filterIsInstance<RequestRoute>().single().waypoints.map { it.place.id }
    private fun List<NaviCommand>.speech() = filterIsInstance<Speak>().single().text
    private fun ids(stops: List<Stop>) = stops.map { it.place.id }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 5])
    fun `정류지 N개를 순서대로 들르며 항상 다음 2곳만 내비에 넣는다`(n: Int) {
        val all = stops(n)
        val m = machine(all)
        assertEquals(ids(all.take(2)), m.handle(Start).route())

        for (i in 0 until n) {
            val out = m.handle(WaypointArrived())
            val expected = ids(all.drop(i + 1).take(2))
            assertEquals(expected, out.route(), "도착 ${i + 1}번째 뒤")
            assertEquals(expected, ids(m.state.exposed))
            assertTrue(out.last() is Speak)
        }
        assertEquals(n, m.state.visited.size)
        assertTrue(m.state.remaining.isEmpty())
        assertEquals("목적지에 도착했어요.", m.handle(DestinationArrived).speech())
        assertEquals(GuidanceStatus.FINISHED, m.state.status)
        assertTrue(m.handle(WaypointArrived()).isEmpty())
    }

    @Test
    fun `도착 문구는 다음 2곳을 말한다`() {
        val m = machine(stops(3))
        m.handle(Start)
        assertEquals("세탁소 도착했어요. 다음은 약국, 그다음 마트예요.", m.handle(WaypointArrived()).speech())
        assertEquals("약국 도착했어요. 다음은 마트예요.", m.handle(WaypointArrived()).speech())
        assertEquals("마트 도착했어요. 이제 집으로 갈게요.", m.handle(WaypointArrived()).speech())
    }

    @Test
    fun `마지막 정류지 뒤에는 목적지만 남는다`() {
        val m = machine(stops(3))
        m.handle(Start)
        repeat(2) { m.handle(WaypointArrived()) }
        val out = m.handle(WaypointArrived(placeId = "s2"))
        assertEquals(RequestRoute(dest, emptyList()), out.first())
        assertTrue(m.state.exposed.isEmpty())
        assertEquals(null, m.state.next)
        assertTrue(m.handle(WaypointArrived()).isEmpty()) // 남은 경유지 없음
    }

    @Test
    fun `중복 도착 이벤트는 무시한다`() {
        val m = machine(stops(3))
        m.handle(Start)
        m.handle(WaypointArrived(placeId = "s0"))
        assertTrue(m.handle(WaypointArrived(placeId = "s0")).isEmpty())
        assertTrue(m.handle(WaypointArrived(placeId = "unknown")).isEmpty())
        assertEquals(ids(stops(3).drop(1)), ids(m.state.remaining))
    }

    @Test
    fun `뒤쪽 정류지에 먼저 도착하면 앞의 정류지는 건너뛴 것으로 본다`() {
        val m = machine(stops(4))
        m.handle(Start)
        val out = m.handle(WaypointArrived(slot = WaypointSlot.SECOND)) // s1 도착, s0 건너뜀
        assertEquals(listOf("s0"), ids(m.state.skipped))
        assertEquals(listOf("s1"), ids(m.state.visited))
        assertEquals(listOf("s2", "s3"), out.route())
        assertTrue(m.handle(WaypointArrived(placeId = "s0")).isEmpty()) // 늦게 온 s0 도착은 무시

        m.handle(WaypointArrived())
        m.handle(WaypointArrived())
        assertEquals("목적지에 도착했어요.", m.handle(DestinationArrived).speech())
    }

    @Test
    fun `정류지를 남기고 목적지에 도착하면 못 들른 곳을 알려 준다`() {
        val m = machine(stops(3))
        m.handle(Start)
        m.handle(WaypointArrived())
        assertEquals("목적지에 도착했어요. 약국, 마트는 다음에 들러요.", m.handle(DestinationArrived).speech())
        assertEquals(listOf("s1", "s2"), ids(m.state.skipped))
    }

    @Test
    fun `근접 알림에 충분히 머물면 도착으로 보고, 지나치기만 하면 무시한다`() {
        val m = machine(stops(3))
        m.handle(Start)
        val t0 = Instant.parse("2026-11-11T09:40:00Z")
        m.handle(NaviEvent.ProximityEntered("s0", t0))
        assertTrue(m.handle(NaviEvent.ProximityExited("s0", t0.plusSeconds(20))).isEmpty()) // 지나침

        m.handle(NaviEvent.ProximityEntered("s0", t0.plusSeconds(60)))
        val out = m.handle(NaviEvent.ProximityExited("s0", t0.plusSeconds(400)))
        assertEquals(listOf("s1", "s2"), out.route())
        assertEquals(listOf("s0"), ids(m.state.visited))
        // 내비 도착 이벤트가 늦게 와도 중복 처리하지 않음
        assertTrue(m.handle(WaypointArrived(placeId = "s0")).isEmpty())
    }

    @Test
    fun `재탐색 후 내비 경유지가 다르면 다시 맞추고, 같거나 모르면 그대로 둔다`() {
        val m = machine(stops(3))
        m.handle(Start)
        assertTrue(m.handle(NaviEvent.RouteRecalculated(listOf("s0", "s1"))).isEmpty())
        assertTrue(m.handle(NaviEvent.RouteRecalculated(null)).isEmpty())
        val out = m.handle(NaviEvent.RouteRecalculated(listOf("s1")))
        assertEquals(listOf("s0", "s1"), out.route())
    }

    @Test
    fun `안내를 취소했다가 다시 시작하면 남은 정류지로 이어 간다`() {
        val m = machine(stops(3))
        m.handle(Start)
        m.handle(WaypointArrived())
        m.handle(NaviEvent.RouteCancelled)
        assertEquals(GuidanceStatus.SUSPENDED, m.state.status)
        assertTrue(m.handle(WaypointArrived()).isEmpty())
        assertEquals(listOf("s1", "s2"), m.handle(Start).route())
    }

    @Test
    fun `중간 삽입은 앞쪽 2곳이 바뀔 때만 내비를 갱신한다`() {
        val m = machine(stops(3))
        m.handle(Start)
        val gas = Stop(Fixtures.place("gas", 0.015, category = PlaceCategory.GAS_STATION), ai.deulleulcar.core.model.StopSource.CAR, "car-1")

        assertEquals(listOf("s0", "gas"), m.handle(NaviEvent.StopInserted(gas, 1)).route())
        assertEquals(listOf("s0", "gas", "s1", "s2"), ids(m.state.remaining))
        assertTrue(m.handle(NaviEvent.StopInserted(gas, 0)).isEmpty()) // 이미 있음

        val bank = Fixtures.human(Fixtures.place("bank", 0.09, category = PlaceCategory.BANK))
        assertTrue(m.handle(NaviEvent.StopInserted(bank, 99)).isEmpty()) // 맨 뒤: 앞 2곳 그대로
        assertEquals("bank", m.state.remaining.last().place.id)

        assertEquals(
            "세탁소 도착했어요. 다음은 주유소, 그다음 약국이에요.",
            m.handle(WaypointArrived()).speech(),
        )
    }

    @Test
    fun `남은 정류지 교체 시 이미 들른 곳은 빼고 반영한다`() {
        val all = stops(4)
        val m = machine(all)
        m.handle(Start)
        m.handle(WaypointArrived())
        val out = m.handle(NaviEvent.RemainingReplaced(listOf(all[0], all[3], all[1], all[2])))
        assertEquals(listOf("s3", "s1"), out.route())
        assertEquals(listOf("s3", "s1", "s2"), ids(m.state.remaining))
    }

    @Test
    fun `ClearAndAdd 전략은 경유지를 비우고 2곳을 다시 넣는다`() {
        val all = stops(3)
        val m = machine(all, SlotSyncStrategy.ClearAndAdd)
        assertEquals(listOf("s0", "s1"), m.handle(Start).route())
        val out = m.handle(WaypointArrived()).dropLast(1)
        assertEquals(
            listOf(ClearWaypoints, AddWaypoint(WaypointSlot.FIRST, all[1]), AddWaypoint(WaypointSlot.SECOND, all[2])),
            out,
        )
    }

    @Test
    fun `Incremental 전략은 빈 SECOND만 채우고, 건너뛰면 전체를 다시 넣는다`() {
        val all = stops(5)
        val m = machine(all, SlotSyncStrategy.Incremental)
        m.handle(Start)
        assertEquals(listOf(AddWaypoint(WaypointSlot.SECOND, all[2])), m.handle(WaypointArrived()).dropLast(1))
        val skip = m.handle(WaypointArrived(placeId = "s3")).dropLast(1) // s2 건너뜀
        assertEquals(listOf(ClearWaypoints, AddWaypoint(WaypointSlot.FIRST, all[4])), skip)
        assertEquals(emptyList(), m.handle(WaypointArrived()).dropLast(1)) // 마지막: 넣을 것 없음
    }
}
