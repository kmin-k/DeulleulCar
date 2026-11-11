package ai.deulleulcar.core.place

import ai.deulleulcar.core.model.Place
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 출발점–목적지 "경로 근처" 장소 검색.
 *
 * 한계: 실제 경로 폴리라인이 없어서 두 지점을 잇는 직선 위에 샘플 지점을 찍고, 각 지점 반경 안을 검색해
 * 합친다(중복 제거). 도로가 직선에서 크게 벗어나는 구간(강·산·고가도로 우회)은 놓치거나 실제 우회가 큰
 * 후보가 들어올 수 있다. 결과의 [CorridorHit.offsetM]은 직선까지의 거리로, 실제 "+N분"은 경로 최적화(T8)가
 * 이동시간 추정으로 다시 계산해야 한다. 짧은 구간 근사이므로 수 km 규모 퇴근길을 가정한다(평면 근사 사용).
 */
class CorridorSearch(private val search: PlaceSearch) {

    /**
     * @param template 업종·키워드·필수 속성·반경·건수. [PlaceQuery.center]는 무시하고 샘플 지점으로 바꾼다.
     * @param samples 샘플 지점 수(양 끝 포함). null이면 반경의 1.5배 간격이 되도록 정한다(2..8).
     */
    suspend fun search(
        template: PlaceQuery,
        from: GeoPoint,
        to: GeoPoint,
        samples: Int? = null,
    ): CorridorResult = coroutineScope {
        val points = samplePoints(from, to, samples ?: autoSampleCount(from.distanceTo(to), template.radiusM))
        val results = points.map { p -> async { search.search(template.copy(center = p)) } }.awaitAll()

        val amenities = mutableMapOf<String, Set<Amenity>>()
        val unique = LinkedHashMap<String, Place>()
        for (r in results) {
            amenities.putAll(r.amenities)
            for (place in r.places) unique.putIfAbsent(place.id, place)
        }
        val hits = unique.values
            .map { CorridorHit(it, offsetM = crossTrackDistanceM(it.point, from, to), progress = progress(it.point, from, to)) }
            .sortedWith(compareBy({ it.offsetM }, { it.progress }))
        CorridorResult(
            hits = hits,
            // 일부 지점만 데모로 대신했어도 전체를 "데모 섞임"으로 보여 주도록 DEMO를 우선한다.
            source = if (results.any { it.source == PlaceDataSource.DEMO }) PlaceDataSource.DEMO else PlaceDataSource.KAKAO,
            amenities = amenities.filterKeys { it in unique },
            fallbackReason = results.firstNotNullOfOrNull { it.fallbackReason },
            samplePoints = points,
        )
    }

    companion object {
        fun autoSampleCount(distanceM: Double, radiusM: Int): Int =
            if (radiusM <= 0) 2 else (ceil(distanceM / (radiusM * 1.5)).toInt() + 1).coerceIn(2, 8)

        /** 직선 위에 양 끝 포함 [n]개 지점. */
        fun samplePoints(from: GeoPoint, to: GeoPoint, n: Int): List<GeoPoint> {
            require(n >= 2) { "샘플 지점은 2개 이상: $n" }
            return (0 until n).map { i ->
                val t = i.toDouble() / (n - 1)
                GeoPoint(from.lat + (to.lat - from.lat) * t, from.lon + (to.lon - from.lon) * t)
            }
        }

        /** [p]에서 선분 from–to까지 거리(미터). 국지 평면 근사. */
        fun crossTrackDistanceM(p: GeoPoint, from: GeoPoint, to: GeoPoint): Double {
            val (px, py) = project(p, from)
            val (bx, by) = project(to, from)
            val len2 = bx * bx + by * by
            if (len2 == 0.0) return sqrt(px * px + py * py)
            val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val dx = px - t * bx
            val dy = py - t * by
            return sqrt(dx * dx + dy * dy)
        }

        /** 선분 위 투영 위치(0=출발, 1=도착). */
        fun progress(p: GeoPoint, from: GeoPoint, to: GeoPoint): Double {
            val (px, py) = project(p, from)
            val (bx, by) = project(to, from)
            val len2 = bx * bx + by * by
            return if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        }

        private fun project(p: GeoPoint, origin: GeoPoint): Pair<Double, Double> {
            val mPerDegLat = Math.toRadians(1.0) * GeoPoint.EARTH_RADIUS_M
            val mPerDegLon = mPerDegLat * cos(Math.toRadians(origin.lat))
            return (p.lon - origin.lon) * mPerDegLon to (p.lat - origin.lat) * mPerDegLat
        }
    }
}

/**
 * @property offsetM 출발–도착 직선까지 거리(미터). 우회 크기의 대략적인 지표.
 * @property progress 직선 위 투영 위치(0=출발, 1=도착).
 */
data class CorridorHit(val place: Place, val offsetM: Double, val progress: Double)

data class CorridorResult(
    val hits: List<CorridorHit>,
    val source: PlaceDataSource,
    val amenities: Map<String, Set<Amenity>> = emptyMap(),
    val fallbackReason: String? = null,
    val samplePoints: List<GeoPoint> = emptyList(),
) {
    val places: List<Place> get() = hits.map { it.place }
    fun amenitiesOf(place: Place): Set<Amenity> = amenities[place.id].orEmpty()
}
