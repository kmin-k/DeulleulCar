package ai.deulleulcar.core.place

import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory

/**
 * 장소의 "해결 장소" 속성. 카카오 로컬은 이 정보를 주지 않으므로 데모 시드 데이터에만 있다.
 * 결과에 없으면 "없음"이 아니라 "모름"이다.
 */
enum class Amenity(val label: String) {
    AIR_PUMP("공기주입기"),
    SELF_SERVICE("셀프"),
    FAST_CHARGER("급속 충전기"),
}

/**
 * 장소 검색 조건.
 * @property keyword 사용자가 말한 특정 상호·표현(예: "햇살약국"). null이면 [category]의 대표 검색어를 쓴다.
 * @property radiusM 검색 반경(미터). 카카오 제한에 맞춰 0..20000.
 * @property requiredAmenities 꼭 있어야 하는 속성. 속성을 알 수 없는 소스(카카오)는 이 조건을 걸러내지 못한다.
 * @property limit 최대 결과 수. 카카오 한 페이지 최대치(15)를 넘기지 않는다.
 */
data class PlaceQuery(
    val category: PlaceCategory,
    val center: GeoPoint,
    val keyword: String? = null,
    val radiusM: Int = 2_000,
    val requiredAmenities: Set<Amenity> = emptySet(),
    val limit: Int = 15,
) {
    init {
        require(radiusM in 0..20_000) { "radiusM은 0..20000: $radiusM" }
        require(limit in 1..15) { "limit은 1..15: $limit" }
        require(category != PlaceCategory.OTHER || !keyword.isNullOrBlank()) { "OTHER 업종은 keyword가 필요합니다" }
    }
}

/** 검색 결과가 어디서 왔는지. UI·로그에서 "데모 데이터" 여부를 보여 줄 때 쓴다. */
enum class PlaceDataSource { KAKAO, DEMO }

/**
 * 장소 검색 결과.
 * @property places 기준점에서 가까운 순.
 * @property amenities 장소 id → 알려진 속성. 없는 id는 "모름".
 * @property fallbackReason 기본 소스가 실패해서 다른 소스로 대신했을 때 그 이유(키 값은 넣지 않는다).
 */
data class PlaceSearchResult(
    val places: List<Place>,
    val source: PlaceDataSource,
    val amenities: Map<String, Set<Amenity>> = emptyMap(),
    val fallbackReason: String? = null,
) {
    fun amenitiesOf(place: Place): Set<Amenity> = amenities[place.id].orEmpty()
}

/** 장소 검색. 구현: [KakaoLocalPlaceSearch], [DemoPlaceSearch], [FallbackPlaceSearch]. */
interface PlaceSearch {
    /** 실패하면 [PlaceSearchException]을 던진다. */
    suspend fun search(query: PlaceQuery): PlaceSearchResult
}

/** 장소 검색 실패. [statusCode]는 HTTP 오류일 때만 있다. */
class PlaceSearchException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
