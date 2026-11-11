package ai.deulleulcar.core.place

import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 데모 구역 시드 데이터: 회사·집 좌표와 가상 상호 장소들 (`/demo/places.json`). */
data class DemoSeed(
    val work: GeoPoint,
    val home: GeoPoint,
    val places: List<Place>,
    val amenities: Map<String, Set<Amenity>>,
) {
    companion object {
        const val RESOURCE = "/demo/places.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): DemoSeed {
            val file = json.decodeFromString(DemoSeedFile.serializer(), text)
            val places = file.places.map {
                Place(it.id, it.name, it.lat, it.lon, PlaceCategory.valueOf(it.category), it.address)
            }
            require(places.map { it.id }.toSet().size == places.size) { "데모 장소 id가 중복됩니다" }
            return DemoSeed(
                work = GeoPoint(file.work.lat, file.work.lon),
                home = GeoPoint(file.home.lat, file.home.lon),
                places = places,
                amenities = file.places.filter { it.amenities.isNotEmpty() }
                    .associate { p -> p.id to p.amenities.map { Amenity.valueOf(it) }.toSet() },
            )
        }

        /** 클래스패스의 데모 시드. 한 번만 읽는다. */
        val default: DemoSeed by lazy {
            val stream = DemoSeed::class.java.getResourceAsStream(RESOURCE) ?: error("리소스 없음: $RESOURCE")
            fromJson(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }
}

@Serializable
internal data class DemoSeedFile(val work: DemoPoint, val home: DemoPoint, val places: List<DemoPlace>)

@Serializable
internal data class DemoPoint(val lat: Double, val lon: Double)

@Serializable
internal data class DemoPlace(
    val id: String,
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val address: String? = null,
    val amenities: List<String> = emptyList(),
)

/**
 * 시드 데이터로 동작하는 [PlaceSearch]. 키가 없거나 카카오 호출이 실패할 때, 데모 모드에서 쓴다.
 * 영업시간은 채우지 않는다([HoursProvider]가 채운다).
 *
 * 특정 상호([PlaceQuery.keyword])를 말했고 이름이 맞는 곳이 있으면 그곳만, 없으면 같은 업종 전체에서 찾는다.
 */
class DemoPlaceSearch(private val seed: DemoSeed = DemoSeed.default) : PlaceSearch {
    override suspend fun search(query: PlaceQuery): PlaceSearchResult {
        val sameCategory = seed.places.filter { query.category == PlaceCategory.OTHER || it.category == query.category }
        val keyword = query.keyword?.trim()?.takeIf { it.isNotEmpty() && it != query.category.label }
        val named = keyword?.let { k -> sameCategory.filter { k in it.name || it.name.replace(" ", "").contains(k.replace(" ", "")) } }
        val candidates = if (!named.isNullOrEmpty()) named else if (query.category == PlaceCategory.OTHER) emptyList() else sameCategory
        val places = candidates
            .filter { p -> query.requiredAmenities.all { it in seed.amenities[p.id].orEmpty() } }
            .map { it to it.point.distanceTo(query.center) }
            .filter { (_, d) -> d <= query.radiusM }
            .sortedBy { it.second }
            .take(query.limit)
            .map { it.first }
        return PlaceSearchResult(
            places = places,
            source = PlaceDataSource.DEMO,
            amenities = places.mapNotNull { p -> seed.amenities[p.id]?.let { p.id to it } }.toMap(),
        )
    }
}
