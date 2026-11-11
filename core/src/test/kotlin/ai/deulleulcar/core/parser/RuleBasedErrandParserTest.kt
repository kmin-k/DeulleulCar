package ai.deulleulcar.core.parser

import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.PlaceCategory.BAKERY
import ai.deulleulcar.core.model.PlaceCategory.BANK
import ai.deulleulcar.core.model.PlaceCategory.CAFE
import ai.deulleulcar.core.model.PlaceCategory.CAR_WASH
import ai.deulleulcar.core.model.PlaceCategory.CONVENIENCE_STORE
import ai.deulleulcar.core.model.PlaceCategory.EV_CHARGING
import ai.deulleulcar.core.model.PlaceCategory.GAS_STATION
import ai.deulleulcar.core.model.PlaceCategory.LAUNDRY
import ai.deulleulcar.core.model.PlaceCategory.MART
import ai.deulleulcar.core.model.PlaceCategory.OTHER
import ai.deulleulcar.core.model.PlaceCategory.PHARMACY
import ai.deulleulcar.core.model.PlaceCategory.POST_OFFICE
import ai.deulleulcar.core.model.PlaceCategory.REPAIR_SHOP
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource

class RuleBasedErrandParserTest {
    private val parser = RuleBasedErrandParser()

    private val home = DestinationHint.Home
    private val work = DestinationHint.Work

    @ParameterizedTest(name = "{0}")
    @MethodSource("utterances")
    fun `대표 발화`(utterance: String, categories: List<PlaceCategory>, destination: DestinationHint?, time: TimeHint?) {
        val r = parser.parse(utterance)
        assertEquals(categories, r.errands.map { it.category }, "볼일")
        assertEquals(destination, r.destination, "목적지")
        assertEquals(time, r.timeHint, "시간 힌트")
    }

    companion object {
        private val H = DestinationHint.Home
        private val W = DestinationHint.Work

        @JvmStatic
        fun utterances(): Stream<Arguments> = Stream.of(
            arguments("퇴근길에 약국이랑 마트 들렀다 집에 가자", listOf(PHARMACY, MART), H, TimeHint.COMMUTE_HOME),
            arguments("세탁소, 약국, 마트 들렀다 집", listOf(LAUNDRY, PHARMACY, MART), H, null),
            arguments("가는 길에 은행 들러", listOf(BANK), null, TimeHint.ON_THE_WAY),
            arguments("주유소 들렀다 회사", listOf(GAS_STATION), W, null),
            arguments("편의점하고 약국 좀 들러줘", listOf(CONVENIENCE_STORE, PHARMACY), null, null),
            arguments("슈퍼랑 마트 들러", listOf(MART), null, null),
            arguments("생활용품점 들렀다 집으로", listOf(MART), H, null),
            arguments("출근길에 카페 들러", listOf(CAFE), W, TimeHint.COMMUTE_WORK),
            arguments("약국과 은행 그리고 세탁소 들렀다 가자", listOf(PHARMACY, BANK, LAUNDRY), null, null),
            arguments("빵집 와 우체국 들러줘", listOf(BAKERY, POST_OFFICE), null, null),
            arguments("기름 넣고 집에 가자", listOf(GAS_STATION), H, null),
            arguments("커피 한 잔 사고 마트 들러", listOf(CAFE, MART), null, null),
            arguments("ATM 들러서 현금 좀 뽑고 집", listOf(BANK), H, null),
            arguments("빨래방 들렀다 편의점 들러", listOf(LAUNDRY, CONVENIENCE_STORE), null, null),
            arguments("시청역 가는 길에 약국 들러", listOf(PHARMACY), DestinationHint.Named("시청역"), TimeHint.ON_THE_WAY),
            arguments("정비소 들렀다 집에 가자", listOf(REPAIR_SHOP), H, null),
            arguments("세차장 들렀다가 집에 갈래", listOf(CAR_WASH), H, null),
            arguments("약국 약국 약국 들러", listOf(PHARMACY), null, null),
            arguments("집에 가자", emptyList<PlaceCategory>(), H, null),
            arguments("꽃집 들렀다 집에 가자", listOf(OTHER), H, null),
            arguments("마트에서 장 보고 약국 들러", listOf(MART, PHARMACY), null, null),
            arguments("전기차 충전소 들렀다 회사", listOf(EV_CHARGING), W, null),
            arguments("집에서 출발해서 은행 들렀다 회사", listOf(BANK), W, null),
            arguments("오늘 퇴근하면서 우체국 들러야 돼", listOf(POST_OFFICE), H, TimeHint.COMMUTE_HOME),
            arguments("퇴근길에 세탁소와 슈퍼 들렀다가 우리집으로", listOf(LAUNDRY, MART), H, TimeHint.COMMUTE_HOME),
            arguments("마트, 약국, 은행, 세탁소 다 들렀다 사무실로 가줘", listOf(MART, PHARMACY, BANK, LAUNDRY), W, null),
            arguments("스마트폰 가게 들러", listOf(OTHER), null, null),
            arguments("약국이랑 마트랑 편의점", listOf(PHARMACY, MART, CONVENIENCE_STORE), null, null),
        )
    }

    @Test
    fun `순서 보존`() {
        val r = parser.parse("마트 들르고 약국 들르고 은행")
        assertEquals(listOf(MART, PHARMACY, BANK), r.errands.map { it.category })
    }

    @Test
    fun `원문 키워드를 남긴다`() {
        val r = parser.parse("슈퍼랑 세탁소 들러")
        assertEquals(listOf("슈퍼", "세탁소"), r.errands.map { it.keyword })
        assertTrue(r.errands.all { it.placeName == null })
    }

    @Test
    fun `상호를 말하면 지정 장소명`() {
        val r = parser.parse("햇살약국 들렀다 회사로 가줘")
        assertEquals(1, r.errands.size)
        assertEquals(PHARMACY, r.errands[0].category)
        assertEquals("햇살약국", r.errands[0].placeName)
        assertEquals(work, r.destination)
    }

    @Test
    fun `상호와 업종이 다르면 둘 다 남긴다`() {
        val r = parser.parse("햇살약국이랑 약국 들러")
        assertEquals(listOf("햇살약국", null), r.errands.map { it.placeName })
    }

    @Test
    fun `접속어와 수식어는 상호가 아니다`() {
        val r = parser.parse("편의점하고약국 그리고 동네 마트 들러")
        assertEquals(listOf(CONVENIENCE_STORE, PHARMACY, MART), r.errands.map { it.category })
        assertTrue(r.errands.all { it.placeName == null })
    }

    @Test
    fun `기타 볼일은 원문 단어로`() {
        val r = parser.parse("꽃집 들렀다 집에 가자")
        assertEquals("꽃집", r.errands.single().keyword)
        assertEquals(home, r.destination)
    }

    @Test
    fun `빈 발화와 볼일 없는 발화`() {
        assertTrue(parser.parse("").errands.isEmpty())
        assertTrue(parser.parse("   ").errands.isEmpty())
        val r = parser.parse("오늘 날씨 어때")
        assertTrue(r.errands.isEmpty())
        assertNull(r.destination)
    }
}
