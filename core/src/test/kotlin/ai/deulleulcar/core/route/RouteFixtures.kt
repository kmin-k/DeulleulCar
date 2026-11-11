package ai.deulleulcar.core.route

import ai.deulleulcar.core.model.Errand
import ai.deulleulcar.core.model.OpeningHours
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.model.StopSource
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 테스트용 가짜 지도. 출발지에서 동쪽으로 경도 [x]도, 북쪽으로 위도 [y]도 떨어진 곳에 장소를 만든다.
 * 위도 37.5에서 경도 0.01도 ≈ 0.88km. 목적지는 동쪽 0.1도(≈8.8km).
 */
object Fixtures {
    const val BASE_LAT = 37.50
    const val BASE_LON = 127.00

    val origin = GeoPoint(BASE_LAT, BASE_LON, "회사")
    val destination = GeoPoint(BASE_LAT, BASE_LON + 0.1, "집")

    /** 2026-11-11(수) 18:30 퇴근 */
    val evening: LocalDateTime = LocalDateTime.of(2026, 11, 11, 18, 30)

    fun hours(open: Int, close: Int, closeMinute: Int = 0) =
        OpeningHours.everyDay(LocalTime.of(open, 0), LocalTime.of(close, closeMinute))

    fun place(
        id: String,
        x: Double,
        y: Double = 0.0,
        category: PlaceCategory = PlaceCategory.OTHER,
        hours: OpeningHours? = null,
    ) = Place(
        id = id,
        name = "가상${category.label}-$id",
        lat = BASE_LAT + y,
        lon = BASE_LON + x,
        category = category,
        openingHours = hours,
    )

    fun human(place: Place) = Stop(place, StopSource.HUMAN, errand = Errand(place.category.label, place.category))

    fun request(id: String, vararg places: Place) = StopRequest(id, places.map { human(it) })

    fun route(
        vararg requests: StopRequest,
        departAt: LocalDateTime = evening,
        pinned: String? = null,
    ) = RouteRequest(origin, destination, requests.toList(), departAt, pinned)
}
