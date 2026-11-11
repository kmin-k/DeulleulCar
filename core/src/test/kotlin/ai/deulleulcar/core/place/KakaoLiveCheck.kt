package ai.deulleulcar.core.place

import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.PlaceCategory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 실제 카카오 로컬 호출 확인(수동). 기본 test에서는 건너뛴다.
 * 실행: `DEULLEULCAR_LIVE=1 ./gradlew :core:test --tests '*KakaoLiveCheck*'`
 * 키는 저장소 루트 local.properties의 KAKAO_REST_API_KEY에서 읽고, 출력하지 않는다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DEULLEULCAR_LIVE", matches = "1")
class KakaoLiveCheck {
    private fun key(): String {
        val file = listOf(File("local.properties"), File("../local.properties")).first { it.exists() }
        val props = Properties().apply { file.inputStream().use { load(it) } }
        return props.getProperty("KAKAO_REST_API_KEY")?.trim().orEmpty().also { require(it.isNotEmpty()) { "키 없음" } }
    }

    @Test
    fun `역삼역 부근 약국 키워드 검색`() = runBlocking {
        val kakao = KakaoLocalPlaceSearch(key())
        val result = try {
            kakao.search(PlaceQuery(PlaceCategory.PHARMACY, GeoPoint(37.5006, 127.0364), keyword = "약국", radiusM = 1_000))
        } catch (e: PlaceSearchException) {
            println("LIVE 실패: status=${e.statusCode} ${e.message}")
            throw e
        }
        println("LIVE 결과 건수=${result.places.size} 업종=${result.places.groupingBy { it.category }.eachCount()}")
        assertTrue(result.places.isNotEmpty())
    }
}
