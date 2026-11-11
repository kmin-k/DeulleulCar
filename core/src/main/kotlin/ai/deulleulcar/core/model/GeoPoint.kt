package ai.deulleulcar.core.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 위경도 한 점. 장소 좌표([Place.point]), 출발지·목적지, 내비 요청 좌표에 모두 쓴다.
 * @property name 사람이 읽는 이름(예: "회사", 상호). 경로 안내 문구·내비 목적지 이름에 쓴다.
 */
data class GeoPoint(val lat: Double, val lon: Double, val name: String? = null) {
    /** 두 지점 사이 대원 거리(미터, haversine). */
    fun distanceTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}
