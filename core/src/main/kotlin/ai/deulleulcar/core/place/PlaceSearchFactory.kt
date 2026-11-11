package ai.deulleulcar.core.place

import kotlinx.coroutines.CancellationException

/**
 * [primary]를 먼저 쓰고, 실패하면([PlaceSearchException] 등) [fallback]으로 대신한다.
 * 결과의 [PlaceSearchResult.source]로 어느 쪽이 답했는지, [PlaceSearchResult.fallbackReason]으로 왜 대신했는지 안다.
 * 기본 소스가 "0건"을 돌려준 것은 실패가 아니므로 그대로 돌려준다.
 * [hours]가 있으면 영업시간이 비어 있는 장소를 채운다.
 */
class FallbackPlaceSearch(
    private val primary: PlaceSearch?,
    private val fallback: PlaceSearch,
    private val hours: HoursProvider? = null,
) : PlaceSearch {
    override suspend fun search(query: PlaceQuery): PlaceSearchResult {
        val result = if (primary == null) {
            fallback.search(query)
        } else {
            try {
                primary.search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallback.search(query).copy(fallbackReason = e.message ?: e.javaClass.simpleName)
            }
        }
        return if (hours == null) result else result.copy(places = result.places.map { it.withHoursFrom(hours) })
    }
}

object PlaceSearchFactory {
    /**
     * 키가 있으면 카카오(실패 시 데모로 대신), 없으면 데모. 영업시간은 큐레이션 → 업종 기본값 순으로 채운다.
     * @param kakaoRestApiKey local.properties/설정 화면의 카카오 REST 키. 빈 값이면 키 없음으로 본다.
     */
    fun create(
        kakaoRestApiKey: String?,
        kakaoBaseUrl: String = KakaoLocalPlaceSearch.DEFAULT_BASE_URL,
        demo: PlaceSearch = DemoPlaceSearch(),
        hours: HoursProvider = CompositeHoursProvider.demoDefault(),
    ): PlaceSearch {
        val kakao = kakaoRestApiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { KakaoLocalPlaceSearch(it, kakaoBaseUrl) }
        return FallbackPlaceSearch(kakao, demo, hours)
    }
}
