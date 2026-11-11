package ai.deulleulcar.core.place

import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 카카오 로컬 REST API로 장소를 찾는다.
 *
 * - 키워드 검색 `GET /v2/local/search/keyword.json` (query 필수, category_group_code 선택)
 * - 카테고리 검색 `GET /v2/local/search/category.json` (category_group_code 필수, query 없음)
 * - 공통: x=경도, y=위도, radius(0~20000m), size(1~15), sort=distance, 헤더 `Authorization: KakaoAK {REST 키}`
 *
 * 카카오 그룹 코드가 정확히 대응하는 업종(약국 PM9, 편의점 CS2, 은행 BK9, 카페 CE7)은 사용자가 특정 상호를
 * 말하지 않았을 때 카테고리 검색을 쓰고, 나머지는 키워드 검색을 쓴다. 카카오 로컬에는 영업시간·편의시설
 * 정보가 없으므로 [Place.openingHours]는 null, [PlaceSearchResult.amenities]는 비어 있다.
 */
class KakaoLocalPlaceSearch(
    private val restApiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    client: OkHttpClient? = null,
) : PlaceSearch {
    private val baseUrl: HttpUrl = baseUrl.toHttpUrl()
    private val client: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    init {
        require(restApiKey.isNotBlank()) { "카카오 REST 키가 비어 있습니다" }
    }

    override suspend fun search(query: PlaceQuery): PlaceSearchResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(query))
            .header("Authorization", "KakaoAK $restApiKey")
            .get()
            .build()
        val body = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw PlaceSearchException(errorMessage(response.code, text), statusCode = response.code)
                }
                text
            }
        } catch (e: IOException) {
            throw PlaceSearchException("카카오 로컬 호출 실패: ${e.javaClass.simpleName}", cause = e)
        }
        val parsed = try {
            json.decodeFromString(KakaoResponse.serializer(), body)
        } catch (e: SerializationException) {
            throw PlaceSearchException("카카오 로컬 응답 해석 실패", cause = e)
        } catch (e: IllegalArgumentException) {
            throw PlaceSearchException("카카오 로컬 응답 해석 실패", cause = e)
        }
        val places = parsed.documents.mapNotNull { it.toPlace(query.category) }
            .sortedBy { it.point.distanceTo(query.center) }
            .take(query.limit)
        PlaceSearchResult(places, PlaceDataSource.KAKAO)
    }

    internal fun buildUrl(query: PlaceQuery): HttpUrl {
        val code = KakaoCategoryMapping.groupCodeFor(query.category)
        val useCategoryEndpoint = query.keyword.isNullOrBlank() && code != null &&
            query.category in KakaoCategoryMapping.categoryEndpointCategories
        val builder = baseUrl.newBuilder()
            .addPathSegments(if (useCategoryEndpoint) "v2/local/search/category.json" else "v2/local/search/keyword.json")
        if (!useCategoryEndpoint) {
            builder.addQueryParameter("query", query.keyword?.takeIf { it.isNotBlank() } ?: KakaoCategoryMapping.keywordFor(query.category))
        }
        if (code != null) builder.addQueryParameter("category_group_code", code)
        return builder
            .addQueryParameter("x", query.center.lon.toString())
            .addQueryParameter("y", query.center.lat.toString())
            .addQueryParameter("radius", query.radiusM.toString())
            .addQueryParameter("sort", "distance")
            .addQueryParameter("size", query.limit.toString())
            .build()
    }

    private fun errorMessage(code: Int, body: String): String {
        val err = runCatching { json.decodeFromString(KakaoError.serializer(), body) }.getOrNull()
        val detail = listOfNotNull(err?.msg, err?.message).joinToString(" ")
        val hint = when {
            "OPEN_MAP_AND_LOCAL" in detail -> "카카오맵(로컬) 서비스가 앱에서 꺼져 있음(앱 설정 > 카카오맵 사용 설정 ON 필요)"
            code == 401 -> "인증 실패(REST 키 확인)"
            code == 403 -> "권한 없음(앱의 카카오맵 사용 설정·플랫폼 설정 확인)"
            code == 429 -> "호출 한도 초과"
            else -> "HTTP 오류"
        }
        val tags = listOfNotNull(err?.errorType, err?.code?.let { "code=$it" }).joinToString(" ")
        return "카카오 로컬 $code $hint" + if (tags.isEmpty()) "" else " ($tags)"
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://dapi.kakao.com/"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

/** 카카오 category_group_code / category_name ↔ [PlaceCategory]. */
object KakaoCategoryMapping {
    /** 카카오 그룹 코드가 우리 업종과 1:1로 맞아서 카테고리 검색을 써도 되는 업종. */
    internal val categoryEndpointCategories = setOf(
        PlaceCategory.PHARMACY, PlaceCategory.CONVENIENCE_STORE, PlaceCategory.BANK, PlaceCategory.CAFE,
    )

    /**
     * 검색에 거는 그룹 코드. 마트는 MT1(대형마트)로 거르면 동네 슈퍼가 빠지므로 걸지 않는다.
     * 충전소는 OL7에 주유소와 섞여 있고 충전소가 OL7이 아닌 경우도 있어 걸지 않는다.
     */
    fun groupCodeFor(category: PlaceCategory): String? = when (category) {
        PlaceCategory.PHARMACY -> "PM9"
        PlaceCategory.CONVENIENCE_STORE -> "CS2"
        PlaceCategory.BANK -> "BK9"
        PlaceCategory.CAFE -> "CE7"
        PlaceCategory.GAS_STATION -> "OL7"
        else -> null
    }

    fun keywordFor(category: PlaceCategory): String = when (category) {
        PlaceCategory.EV_CHARGING -> "전기차충전소"
        PlaceCategory.REPAIR_SHOP -> "자동차정비"
        PlaceCategory.TIRE_SHOP -> "타이어"
        else -> category.label
    }

    /**
     * 응답 한 건의 업종. 그룹 코드 → category_name 순으로 본다. 둘 다 모르면 null.
     * category_name 예: "의료,건강 > 약국", "가정,생활 > 세탁소", "교통,수송 > 자동차 > 주유소".
     */
    fun categoryOf(groupCode: String?, categoryName: String?): PlaceCategory? {
        val name = categoryName.orEmpty()
        when (groupCode) {
            "PM9" -> return PlaceCategory.PHARMACY
            "MT1" -> return PlaceCategory.MART
            "CS2" -> return PlaceCategory.CONVENIENCE_STORE
            "BK9" -> return PlaceCategory.BANK
            "CE7" -> return if ("제과" in name || "베이커리" in name) PlaceCategory.BAKERY else PlaceCategory.CAFE
            "OL7" -> return if ("충전" in name) PlaceCategory.EV_CHARGING else PlaceCategory.GAS_STATION
        }
        return when {
            "약국" in name -> PlaceCategory.PHARMACY
            "충전" in name -> PlaceCategory.EV_CHARGING
            "주유소" in name -> PlaceCategory.GAS_STATION
            "타이어" in name -> PlaceCategory.TIRE_SHOP
            "세차" in name -> PlaceCategory.CAR_WASH
            "정비" in name || "카센터" in name -> PlaceCategory.REPAIR_SHOP
            "세탁" in name -> PlaceCategory.LAUNDRY
            "제과" in name || "베이커리" in name -> PlaceCategory.BAKERY
            "우체국" in name -> PlaceCategory.POST_OFFICE
            "편의점" in name -> PlaceCategory.CONVENIENCE_STORE
            "마트" in name || "슈퍼" in name || "생활용품" in name -> PlaceCategory.MART
            "은행" in name -> PlaceCategory.BANK
            "카페" in name -> PlaceCategory.CAFE
            else -> null
        }
    }
}

@Serializable
internal data class KakaoResponse(val documents: List<KakaoDocument> = emptyList())

@Serializable
internal data class KakaoDocument(
    val id: String,
    @SerialName("place_name") val placeName: String,
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("category_group_code") val categoryGroupCode: String? = null,
    @SerialName("address_name") val addressName: String? = null,
    @SerialName("road_address_name") val roadAddressName: String? = null,
    val x: String,
    val y: String,
) {
    /**
     * 좌표가 깨진 문서는 버린다. 업종을 알 수 없으면 검색한 업종으로 본다(키워드가 맞아서 나온 결과이므로).
     * 다른 업종으로 확인되는 문서(예: "약국" 검색에 섞인 병원)는 버린다. [PlaceCategory.OTHER] 검색은 거르지 않는다.
     */
    fun toPlace(requested: PlaceCategory): Place? {
        val lon = x.toDoubleOrNull() ?: return null
        val lat = y.toDoubleOrNull() ?: return null
        val code = categoryGroupCode?.ifBlank { null }
        val mapped = KakaoCategoryMapping.categoryOf(code, categoryName)
        if (requested != PlaceCategory.OTHER) {
            if (mapped != null && mapped != requested) return null
            // 그룹 코드는 있는데 우리 업종으로 옮길 수 없는 곳(음식점 FD6, 병원 HP8 등)도 다른 업종이다
            if (mapped == null && code != null) return null
        }
        val category = mapped ?: requested
        return Place(
            id = "kakao:$id",
            name = placeName,
            lat = lat,
            lon = lon,
            category = category,
            address = roadAddressName?.ifBlank { null } ?: addressName?.ifBlank { null },
        )
    }
}

/** 카카오 오류 본문. `{code, msg}` 형식과 dapi의 `{errorType, message}` 형식이 둘 다 있다. */
@Serializable
internal data class KakaoError(
    val code: Int? = null,
    val msg: String? = null,
    val errorType: String? = null,
    val message: String? = null,
)
