package ai.deulleulcar.core.demo

import ai.deulleulcar.core.model.GeoPoint

/** 데모 주행 배속. 시뮬레이션 틱(1초)은 그대로 두고 한 틱에 가는 거리만 늘린다. */
enum class DemoSpeed(val factor: Int, val label: String) {
    X1(1, "1x"),
    X4(4, "4x");

    fun next(): DemoSpeed = entries[(ordinal + 1) % entries.size]
}

/** 가상 주행이 향하는 곳 하나(다음 경유지 또는 목적지). [id]가 바뀌면 지금 위치에서 새 구간을 시작한다. */
data class DriveTarget(val id: String, val point: GeoPoint, val name: String, val isDestination: Boolean)

/** 한 번의 [DriveSimulation.update] 결과. */
sealed interface DriveTick {
    /** 갈 곳이 없음(안내 중이 아님). */
    data object Idle : DriveTick

    /**
     * 가는 중. [paused]면 정차·질문 대기로 멈춰 있다.
     * @property fraction 이번 구간 진행률(0~1).
     */
    data class Moving(
        val target: DriveTarget,
        val position: GeoPoint,
        val fraction: Double,
        val remainingM: Double,
        val paused: Boolean,
    ) : DriveTick

    /** 이번 틱에 [target]에 도착했다. 호출한 쪽이 내비 도착 이벤트를 낸다(한 곳에 한 번만). */
    data class Arrived(val target: DriveTarget) : DriveTick

    /** 경유지에서 볼일 보는 중(잠깐 머문 뒤 다음 구간으로). */
    data class Dwelling(val at: DriveTarget, val leftMs: Long) : DriveTick
}

/**
 * 데모 위치 시뮬레이션: 지금 위치에서 다음 정류지까지 직선으로 보간해 움직인다. 순수 로직이라 시계를 주입받는다.
 *
 * - 구간 시간 = 거리 / [metersPerSecond] (1x 기준), [minLegMs]~[maxLegMs]로 자른다. 데모 구역 1~3km가 10~25초.
 * - 배속은 경과 시간에 곱한다. 틱 간격(호출 주기)은 호출한 쪽이 정한다(1Hz 이하 권장).
 * - 도착은 [DriveTick.Arrived]로 한 번만 알린다. 같은 id가 다시 와도(상태 반영 전) 다시 도착시키지 않는다.
 * - [arriveNow]는 수동 "다음" 버튼: 다음 [update]에서 바로 도착(머무는 중이면 바로 출발).
 */
class DriveSimulation(
    private val metersPerSecond: Double = 120.0,
    private val minLegMs: Long = 8_000,
    private val maxLegMs: Long = 30_000,
    private val dwellMs: Long = 4_000,
) {
    var speed: DemoSpeed = DemoSpeed.X1

    private var from: GeoPoint? = null
    private var target: DriveTarget? = null
    private var legMs: Long = 0
    private var progressMs: Long = 0
    private var lastMs: Long? = null
    private var dwellAt: DriveTarget? = null
    private var dwellLeftMs: Long = 0
    private var forceArrive = false
    /** 구간이 없을 때(도착해 머무는 중 등) 위치. */
    private var resting: GeoPoint? = null
    private val arrivedIds = mutableSetOf<String>()

    /** 지금 시뮬레이션 위치. 구간이 없으면 null. */
    val position: GeoPoint?
        get() {
            val f = from ?: return resting
            val t = target ?: return resting
            return lerp(f, t.point, fraction())
        }

    /** 머무는 중이거나 구간이 진행 중인지(“다음”이 의미 있는지). */
    val active: Boolean get() = target != null || dwellAt != null

    fun reset() {
        from = null
        target = null
        legMs = 0
        progressMs = 0
        lastMs = null
        dwellAt = null
        dwellLeftMs = 0
        forceArrive = false
        resting = null
        arrivedIds.clear()
    }

    fun arriveNow(): Boolean {
        if (!active) return false
        forceArrive = true
        return true
    }

    /**
     * @param next 지금 가야 할 곳(없으면 안내 끝).
     * @param here 구간을 새로 시작할 때 출발점(현재 위치).
     * @param moving false면 시간이 흘러도 제자리(정차·질문 대기). 수동 도착은 멈춰 있어도 된다.
     */
    fun update(nowMs: Long, next: DriveTarget?, here: GeoPoint, moving: Boolean): DriveTick {
        val dt = ((nowMs - (lastMs ?: nowMs)).coerceAtLeast(0)) * speed.factor
        lastMs = nowMs
        if (next == null) {
            val keep = lastMs
            reset()
            lastMs = keep
            return DriveTick.Idle
        }

        dwellAt?.let { at ->
            if (forceArrive) {
                dwellLeftMs = 0
                forceArrive = false
            } else {
                dwellLeftMs -= dt
            }
            if (dwellLeftMs > 0) return DriveTick.Dwelling(at, dwellLeftMs)
            dwellAt = null
        }
        if (next.id in arrivedIds) return DriveTick.Dwelling(next, 0) // 도착 반영을 기다리는 중

        if (target?.id != next.id) {
            val start = position ?: here
            from = start
            target = next
            legMs = legDurationMs(start.distanceTo(next.point))
            progressMs = 0
        } else if (target != next) {
            target = next // 같은 곳, 이름 등만 바뀜
        }

        if (forceArrive) {
            forceArrive = false
            progressMs = legMs
        } else if (moving) {
            progressMs = (progressMs + dt).coerceAtMost(legMs)
        }

        val t = target!!
        if (progressMs >= legMs) {
            arrivedIds += t.id
            resting = t.point
            from = null
            target = null
            if (!t.isDestination) {
                dwellAt = t
                dwellLeftMs = dwellMs
            }
            return DriveTick.Arrived(t)
        }
        val pos = position!!
        return DriveTick.Moving(t, pos, fraction(), pos.distanceTo(t.point), paused = !moving)
    }

    /** 1x 기준 구간 시간. */
    fun legDurationMs(distanceM: Double): Long =
        (distanceM / metersPerSecond * 1000).toLong().coerceIn(minLegMs, maxLegMs)

    private fun fraction(): Double = if (legMs <= 0) 1.0 else (progressMs.toDouble() / legMs).coerceIn(0.0, 1.0)

    private fun lerp(a: GeoPoint, b: GeoPoint, f: Double) = GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
}
