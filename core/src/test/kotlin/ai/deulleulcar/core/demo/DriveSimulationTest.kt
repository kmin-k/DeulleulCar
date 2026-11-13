package ai.deulleulcar.core.demo

import ai.deulleulcar.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DriveSimulationTest {
    private val a = GeoPoint(37.5004, 127.0356)
    private val b = GeoPoint(37.5004, 127.0476) // 동쪽 약 1.06km
    private val c = GeoPoint(37.4921, 127.0738)
    private val wp = DriveTarget("wp", b, "약국", isDestination = false)
    private val dest = DriveTarget("dest", c, "집", isDestination = true)

    /** 1초 간격으로 [n]번 update. 마지막 결과를 돌려준다. */
    private fun DriveSimulation.run(fromMs: Long, n: Int, target: DriveTarget?, moving: Boolean = true): Pair<Long, List<DriveTick>> {
        var t = fromMs
        val ticks = (1..n).map {
            t += 1_000
            update(t, target, a, moving)
        }
        return t to ticks
    }

    @Test
    fun `구간 시간은 거리 비례이고 최소·최대로 자른다`() {
        val sim = DriveSimulation(metersPerSecond = 100.0, minLegMs = 8_000, maxLegMs = 30_000)
        assertEquals(8_000, sim.legDurationMs(100.0))
        assertEquals(15_000, sim.legDurationMs(1_500.0))
        assertEquals(30_000, sim.legDurationMs(10_000.0))
    }

    @Test
    fun `1x에서는 구간 시간만큼 걸려 도착하고 위치는 직선 보간된다`() {
        val sim = DriveSimulation(metersPerSecond = 100.0)
        val legMs = sim.legDurationMs(a.distanceTo(b))
        assertIs<DriveTick.Moving>(sim.update(0, wp, a, moving = true)).also { assertEquals(0.0, it.fraction) }
        val (_, ticks) = sim.run(0, (legMs / 1000).toInt() - 1, wp)
        val mid = assertIs<DriveTick.Moving>(ticks.last())
        assertTrue(mid.fraction in 0.8..0.99, "${mid.fraction}")
        assertTrue(mid.position.lon > a.lon && mid.position.lon < b.lon)
        assertEquals(a.lat, mid.position.lat, 1e-9)
        val arrived = sim.update(legMs + 1_000, wp, a, moving = true)
        assertEquals(DriveTick.Arrived(wp), arrived)
    }

    @Test
    fun `4x는 같은 틱 수로 네 배 빨리 간다`() {
        val one = DriveSimulation(metersPerSecond = 100.0)
        val four = DriveSimulation(metersPerSecond = 100.0).apply { speed = DemoSpeed.X4 }
        one.update(0, wp, a, true)
        four.update(0, wp, a, true)
        val f1 = assertIs<DriveTick.Moving>(one.update(2_000, wp, a, true)).fraction
        val f4 = assertIs<DriveTick.Moving>(four.update(2_000, wp, a, true)).fraction
        assertEquals(f1 * 4, f4, 1e-9)
    }

    @Test
    fun `도착은 한 번만 알리고 경유지에서는 잠깐 머문 뒤 다음 구간을 시작한다`() {
        val sim = DriveSimulation(metersPerSecond = 100.0, dwellMs = 3_000)
        sim.update(0, wp, a, true)
        val (t, ticks) = sim.run(0, 40, wp)
        assertEquals(1, ticks.count { it is DriveTick.Arrived })
        // 상태가 아직 안 바뀌어 같은 곳이 와도 다시 도착시키지 않는다
        assertTrue(ticks.dropWhile { it !is DriveTick.Arrived }.drop(1).all { it is DriveTick.Dwelling })

        // 다음 곳: 머무는 시간이 끝난 뒤 경유지(b)에서 출발
        val t2 = t + 1_000
        val next = assertIs<DriveTick.Moving>(sim.update(t2, dest, a, true))
        assertEquals(dest, next.target)
        assertTrue(next.position.distanceTo(b) < 400, "경유지 근처에서 출발")
    }

    @Test
    fun `정차 중에는 멈춰 있고 다음 버튼은 바로 도착시킨다`() {
        val sim = DriveSimulation(metersPerSecond = 100.0)
        sim.update(0, wp, a, moving = false)
        val (t, ticks) = sim.run(0, 5, wp, moving = false)
        val held = assertIs<DriveTick.Moving>(ticks.last())
        assertEquals(0.0, held.fraction)
        assertTrue(held.paused)

        assertTrue(sim.arriveNow())
        assertEquals(DriveTick.Arrived(wp), sim.update(t + 1_000, wp, a, moving = false))
        // 머무는 중 "다음"이면 바로 출발
        assertTrue(sim.arriveNow())
        assertIs<DriveTick.Moving>(sim.update(t + 2_000, dest, a, moving = true))
    }

    @Test
    fun `목적지 도착 뒤에는 머물지 않고 안내가 끝나면 Idle`() {
        val sim = DriveSimulation()
        sim.update(0, dest, a, true)
        sim.arriveNow()
        assertEquals(DriveTick.Arrived(dest), sim.update(1_000, dest, a, true))
        assertEquals(DriveTick.Idle, sim.update(2_000, null, a, true))
        assertTrue(!sim.active)
        assertTrue(!sim.arriveNow())
    }

    @Test
    fun `가는 중에 다음 곳이 바뀌면 지금 위치에서 새 구간을 시작한다`() {
        val sim = DriveSimulation(metersPerSecond = 100.0)
        sim.update(0, dest, a, true)
        val before = assertIs<DriveTick.Moving>(sim.update(5_000, dest, a, true)).position
        val other = DriveTarget("gas", b, "주유소", isDestination = false)
        val after = assertIs<DriveTick.Moving>(sim.update(5_000, other, a, true))
        assertEquals(other, after.target)
        assertEquals(0.0, after.fraction)
        assertEquals(before, after.position)
    }
}
