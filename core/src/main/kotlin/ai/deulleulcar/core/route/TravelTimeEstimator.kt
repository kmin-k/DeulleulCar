package ai.deulleulcar.core.route

import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import java.time.Duration
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** 위경도 한 점(WGS84). 출발지·목적지처럼 [Place]가 아닌 지점에 쓴다. */
data class GeoPoint(val lat: Double, val lon: Double, val name: String? = null)

val Place.point: GeoPoint get() = GeoPoint(lat, lon, name)

/**
 * 이동 시간 추정기. 지금은 직선거리 기반([HaversineEstimator])이고,
 * 나중에 실제 경로 API(예: 내비 경로 탐색 결과)로 바꿀 수 있게 인터페이스로 분리한다.
 * 구현은 시각에 무관해야 한다(최적화기가 결과를 미리 계산해 재사용함).
 */
interface TravelTimeEstimator {
    /** [from]에서 [to]까지 운전 시간. */
    fun travelTime(from: GeoPoint, to: GeoPoint): Duration

    /** [place]에 들러 볼일을 보는 데 걸리는 시간(주차·대기 포함). */
    fun dwellTime(place: Place): Duration
}

/**
 * 직선거리 × 도로 우회계수 ÷ 도심 평균속도로 운전 시간을 추정한다.
 * @param detourFactor 직선거리 대비 실제 도로 거리 비율
 * @param averageSpeedKmh 도심 평균 주행 속도
 * @param defaultDwell 정차 한 번에 쓰는 기본 시간
 * @param dwellByCategory 업종별 체류 시간 (예: 마트 15분). 없으면 [defaultDwell]
 */
class HaversineEstimator(
    private val detourFactor: Double = 1.3,
    private val averageSpeedKmh: Double = 25.0,
    private val defaultDwell: Duration = Duration.ofMinutes(5),
    private val dwellByCategory: Map<PlaceCategory, Duration> = emptyMap(),
) : TravelTimeEstimator {
    init {
        require(detourFactor >= 1.0) { "우회계수는 1 이상이어야 합니다" }
        require(averageSpeedKmh > 0) { "평균속도는 0보다 커야 합니다" }
    }

    override fun travelTime(from: GeoPoint, to: GeoPoint): Duration {
        val km = distanceKm(from, to) * detourFactor
        return Duration.ofSeconds((km / averageSpeedKmh * 3600).roundToLong())
    }

    override fun dwellTime(place: Place): Duration = dwellByCategory[place.category] ?: defaultDwell

    companion object {
        private const val EARTH_RADIUS_KM = 6371.0088

        /** 두 점 사이 대권 거리(km). */
        fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
            return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
