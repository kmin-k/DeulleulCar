package ai.deulleulcar.core.trip

import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.TelltaleType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplyInterpreterTest {
    private val r = ReplyInterpreter()

    @Test
    fun yes() {
        listOf("응", "어", "네", "좋아", "그래 출발하자", "들를게", "응 들러", "출발", "오케이", "넣어 줘", "좋아요!").forEach {
            assertEquals(Reply.YES, r.interpret(it), it)
        }
    }

    @Test
    fun no() {
        listOf("아니", "다음에", "나중에 할게", "안 들러", "괜찮아", "됐어", "다음에 들를게", "아니요 취소").forEach {
            assertEquals(Reply.NO, r.interpret(it), it)
        }
    }

    @Test
    fun `업종이 있으면 새 요청`() {
        listOf("약국 들러", "마트 하나 더 들러", "응 그리고 세탁소도").forEach {
            assertEquals(Reply.NEW_REQUEST, r.interpret(it), it)
        }
    }

    @Test
    fun unknown() {
        listOf("", "음", "오늘 날씨가 정말 좋네 그치 그렇지 않아").forEach {
            assertEquals(Reply.UNKNOWN, r.interpret(it), it)
        }
    }
}

class TripCopyTest {
    private fun task(kind: CarTaskKind, severity: Severity, km: Double? = null) =
        CarTask("t", kind, severity, Instant.EPOCH, rangeKm = km)

    @Test
    fun titles() {
        assertEquals("타이어 공기압이 낮아요", TripCopy.title(task(CarTaskKind.Warning(TelltaleType.TPMS), Severity.RECOMMEND)))
        assertEquals("주행 가능 거리 25km", TripCopy.title(task(CarTaskKind.LowRange, Severity.URGENT, 24.0)))
    }

    @Test
    fun `해결 방법 문장은 활용형이 맞다`() {
        assertEquals(
            "주유소·타이어 전문점에서 해결할 수 있어요. 공기압을 채우면 돼요.",
            TripCopy.howToFix(task(CarTaskKind.Warning(TelltaleType.TPMS), Severity.RECOMMEND)),
        )
        assertEquals(
            "정비소에서 해결할 수 있어요. 정비소에서 점검을 받으면 돼요.",
            TripCopy.howToFix(task(CarTaskKind.PeriodicInspection, Severity.HOLD)),
        )
    }

    @Test
    fun summary() {
        assertNull(TripCopy.remainingSummary(emptyList()))
        assertEquals(
            "차의 할 일이 1개 남아 있어요: 워셔액이 부족해요.",
            TripCopy.remainingSummary(listOf(task(CarTaskKind.Warning(TelltaleType.WASHER_FLUID_LOW), Severity.HOLD))),
        )
    }

    @Test
    fun `정차 중 권장 안내`() {
        val s = TripCopy.parkedRecommendSpeech(task(CarTaskKind.Warning(TelltaleType.TPMS), Severity.RECOMMEND), false)
        assertEquals("타이어 공기압이 낮아요. 당장 위험하진 않지만 오늘 안에 공기압을 채우는 게 좋아요. 다음 경로에 넣어 둘까요?", s)
    }
}
