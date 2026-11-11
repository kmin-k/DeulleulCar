package ai.deulleulcar.core.place

import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.PlaceCategory
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KakaoLocalPlaceSearchTest {
    private lateinit var server: MockWebServer
    private val center = GeoPoint(37.5006, 127.0364)
    private val fakeKey = "test-key"

    @BeforeEach
    fun setUp() {
        // Windows에서 간헐적으로 "Bad address: listen"이 나서 루프백으로 몇 번 다시 시도한다.
        server = (1..5).firstNotNullOfOrNull {
            runCatching { MockWebServer().also { s -> s.start(InetAddress.getLoopbackAddress(), 0) } }.getOrNull()
        } ?: MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun kakao(client: OkHttpClient? = null) =
        KakaoLocalPlaceSearch(fakeKey, server.url("/").toString(), client)

    private val pharmacyBody = """
        {"meta":{"total_count":2,"pageable_count":2,"is_end":true,"same_name":null},
         "documents":[
          {"id":"111","place_name":"가상약국 B","category_name":"의료,건강 > 약국","category_group_code":"PM9",
           "category_group_name":"약국","phone":"","address_name":"서울 강남구 역삼동 1","road_address_name":"서울 강남구 테헤란로 2",
           "x":"127.0400","y":"37.5010","place_url":"http://place.map.kakao.com/111","distance":"320"},
          {"id":"222","place_name":"가상약국 A","category_name":"의료,건강 > 약국","category_group_code":"PM9",
           "category_group_name":"약국","phone":"","address_name":"서울 강남구 역삼동 3","road_address_name":"",
           "x":"127.0370","y":"37.5007","place_url":"http://place.map.kakao.com/222","distance":"60"},
          {"id":"333","place_name":"좌표 깨진 곳","category_name":"의료,건강 > 약국","category_group_code":"PM9",
           "x":"","y":"","place_url":"","distance":""}
         ]}
    """.trimIndent()

    @Test
    fun `카테고리 검색 요청 형식과 응답 파싱`() = runTest {
        server.enqueue(MockResponse().setBody(pharmacyBody))
        val result = kakao().search(PlaceQuery(PlaceCategory.PHARMACY, center, radiusM = 1500))

        val req = server.takeRequest()
        assertEquals("KakaoAK $fakeKey", req.getHeader("Authorization"))
        val url = req.requestUrl!!
        assertEquals("/v2/local/search/category.json", url.encodedPath)
        assertEquals("PM9", url.queryParameter("category_group_code"))
        assertNull(url.queryParameter("query"))
        assertEquals("127.0364", url.queryParameter("x"))
        assertEquals("37.5006", url.queryParameter("y"))
        assertEquals("1500", url.queryParameter("radius"))
        assertEquals("distance", url.queryParameter("sort"))

        assertEquals(PlaceDataSource.KAKAO, result.source)
        assertEquals(listOf("가상약국 A", "가상약국 B"), result.places.map { it.name }) // 가까운 순, 깨진 좌표 제외
        val a = result.places.first()
        assertEquals("kakao:222", a.id)
        assertEquals(PlaceCategory.PHARMACY, a.category)
        assertEquals(37.5007, a.lat)
        assertEquals(127.0370, a.lon)
        assertEquals("서울 강남구 역삼동 3", a.address) // 도로명이 비면 지번
        assertEquals("서울 강남구 테헤란로 2", result.places[1].address)
        assertNull(a.openingHours)
    }

    @Test
    fun `상호를 말하면 키워드 검색에 그룹 코드를 함께 건다`() = runTest {
        server.enqueue(MockResponse().setBody(pharmacyBody))
        kakao().search(PlaceQuery(PlaceCategory.PHARMACY, center, keyword = "햇살약국"))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/v2/local/search/keyword.json", url.encodedPath)
        assertEquals("햇살약국", url.queryParameter("query"))
        assertEquals("PM9", url.queryParameter("category_group_code"))
    }

    @Test
    fun `그룹 코드가 없는 업종은 대표 키워드로 검색하고 category_name으로 매핑한다`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"documents":[
                  {"id":"1","place_name":"가상세탁","category_name":"가정,생활 > 세탁소","category_group_code":"","x":"127.04","y":"37.50"},
                  {"id":"2","place_name":"가상 분식","category_name":"음식점 > 분식","category_group_code":"FD6","x":"127.04","y":"37.50"},
                  {"id":"3","place_name":"이름만 세탁","category_name":"서비스,산업","category_group_code":"","x":"127.04","y":"37.50"}
                ]}""",
            ),
        )
        val result = kakao().search(PlaceQuery(PlaceCategory.LAUNDRY, center))
        val url = server.takeRequest().requestUrl!!
        assertEquals("세탁소", url.queryParameter("query"))
        assertNull(url.queryParameter("category_group_code"))
        // 다른 업종으로 확인된 분식집은 빠지고, 업종을 모르는 곳은 검색한 업종으로 본다
        assertEquals(setOf("kakao:1", "kakao:3"), result.places.map { it.id }.toSet())
        assertTrue(result.places.all { it.category == PlaceCategory.LAUNDRY })
    }

    @Test
    fun `카테고리 매핑`() {
        val m = KakaoCategoryMapping
        assertEquals(PlaceCategory.PHARMACY, m.categoryOf("PM9", null))
        assertEquals(PlaceCategory.MART, m.categoryOf("MT1", "가정,생활 > 대형마트"))
        assertEquals(PlaceCategory.CONVENIENCE_STORE, m.categoryOf("CS2", null))
        assertEquals(PlaceCategory.BANK, m.categoryOf("BK9", null))
        assertEquals(PlaceCategory.GAS_STATION, m.categoryOf("OL7", "교통,수송 > 자동차 > 주유소"))
        assertEquals(PlaceCategory.EV_CHARGING, m.categoryOf("OL7", "교통,수송 > 자동차 > 전기차 충전소"))
        assertEquals(PlaceCategory.BAKERY, m.categoryOf("CE7", "음식점 > 간식 > 제과,베이커리"))
        assertEquals(PlaceCategory.CAFE, m.categoryOf("CE7", "음식점 > 카페"))
        assertEquals(PlaceCategory.MART, m.categoryOf(null, "가정,생활 > 슈퍼마켓"))
        assertEquals(PlaceCategory.REPAIR_SHOP, m.categoryOf(null, "교통,수송 > 자동차 > 자동차정비"))
        assertEquals(PlaceCategory.TIRE_SHOP, m.categoryOf(null, "교통,수송 > 자동차 > 타이어"))
        assertEquals(PlaceCategory.CAR_WASH, m.categoryOf(null, "교통,수송 > 자동차 > 세차장"))
        assertEquals(PlaceCategory.POST_OFFICE, m.categoryOf("PO3", "사회,공공기관 > 우체국"))
        assertNull(m.categoryOf("FD6", "음식점 > 한식"))
    }

    @Test
    fun `HTTP 오류는 상태 코드와 함께 실패하고 키를 메시지에 넣지 않는다`() = runTest {
        // 실제로 받은 형식: 앱에서 카카오맵 서비스가 꺼져 있을 때
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"errorType":"NotAuthorizedError","message":"App(demo) disabled OPEN_MAP_AND_LOCAL service."}"""),
        )
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":-401,"msg":"wrong appKey"}"""))
        val e = assertFailsWith<PlaceSearchException> { kakao().search(PlaceQuery(PlaceCategory.PHARMACY, center)) }
        assertEquals(403, e.statusCode)
        assertTrue("카카오맵 사용 설정 ON" in e.message!!)
        assertTrue("NotAuthorizedError" in e.message!!)
        assertTrue(fakeKey !in e.message!!)
        val e401 = assertFailsWith<PlaceSearchException> { kakao().search(PlaceQuery(PlaceCategory.PHARMACY, center)) }
        assertEquals(401, e401.statusCode)
        assertTrue("REST 키" in e401.message!! && "code=-401" in e401.message!!)
    }

    @Test
    fun `깨진 응답 본문은 실패로 본다`() = runTest {
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        assertFailsWith<PlaceSearchException> { kakao().search(PlaceQuery(PlaceCategory.PHARMACY, center)) }
    }

    @Test
    fun `타임아웃이면 데모로 대신하고 이유를 남긴다`() = runTest {
        server.enqueue(MockResponse().setBody(pharmacyBody).setBodyDelay(2, TimeUnit.SECONDS))
        val shortClient = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        val search = FallbackPlaceSearch(kakao(shortClient), DemoPlaceSearch(), CompositeHoursProvider.demoDefault())
        val result = search.search(PlaceQuery(PlaceCategory.PHARMACY, center))
        assertEquals(PlaceDataSource.DEMO, result.source)
        assertNotNull(result.fallbackReason)
        assertTrue(result.places.isNotEmpty())
        assertTrue(result.places.all { it.openingHours != null })
    }

    @Test
    fun `연결 끊김과 500도 데모로 대신한다`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(500))
        val noRetry = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val search = FallbackPlaceSearch(kakao(noRetry), DemoPlaceSearch())
        repeat(2) {
            assertEquals(PlaceDataSource.DEMO, search.search(PlaceQuery(PlaceCategory.MART, center)).source)
        }
    }

    @Test
    fun `카카오 성공 결과는 그대로 쓰고 영업시간은 업종 기본값으로 채운다`() = runTest {
        server.enqueue(MockResponse().setBody(pharmacyBody))
        val search = FallbackPlaceSearch(kakao(), DemoPlaceSearch(), CompositeHoursProvider.demoDefault())
        val result = search.search(PlaceQuery(PlaceCategory.PHARMACY, center))
        assertEquals(PlaceDataSource.KAKAO, result.source)
        assertNull(result.fallbackReason)
        assertEquals(
            ai.deulleulcar.core.model.HoursSource.CATEGORY_DEFAULT,
            result.places.first().openingHours!!.source,
        )
    }

    @Test
    fun `키가 없으면 데모, 있으면 카카오를 쓴다`() = runTest {
        assertEquals(
            PlaceDataSource.DEMO,
            PlaceSearchFactory.create(null).search(PlaceQuery(PlaceCategory.PHARMACY, center)).source,
        )
        assertEquals(
            PlaceDataSource.DEMO,
            PlaceSearchFactory.create("  ").search(PlaceQuery(PlaceCategory.PHARMACY, center)).source,
        )
        server.enqueue(MockResponse().setBody(pharmacyBody))
        val viaKakao = PlaceSearchFactory.create(fakeKey, server.url("/").toString())
            .search(PlaceQuery(PlaceCategory.PHARMACY, center))
        assertEquals(PlaceDataSource.KAKAO, viaKakao.source)
    }
}
