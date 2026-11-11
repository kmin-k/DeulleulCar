package ai.deulleulcar.core.cartask

import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.TelltaleType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CopyTemplatesTest {
    private fun task(kind: CarTaskKind, severity: Severity, rangeKm: Double? = null) =
        CarTask("car-1", kind, severity, Instant.EPOCH, rangeKm = rangeKm)

    @Test
    fun `권장 TPMS는 3단 구조`() {
        val copy = CopyTemplates.describe(task(CarTaskKind.Warning(TelltaleType.TPMS), Severity.RECOMMEND))
        assertEquals("타이어 공기압이 낮아요.", copy.what)
        assertEquals("당장 위험하진 않지만", copy.risk)
        assertEquals("오늘 안에 공기압을 채우는 게 좋아요.", copy.deadline)
        assertEquals("가까운 주유소에 들를까요?", copy.question)
        assertEquals(
            "타이어 공기압이 낮아요. 당장 위험하진 않지만 오늘 안에 공기압을 채우는 게 좋아요. 가까운 주유소에 들를까요?",
            copy.spoken,
        )
    }

    @Test
    fun `장소와 우회 시간이 있으면 경로상 +N분`() {
        val copy = CopyTemplates.describe(
            task(CarTaskKind.Warning(TelltaleType.TPMS), Severity.RECOMMEND),
            placeName = "OO주유소",
            detourMinutes = 2.6,
        )
        assertEquals("OO주유소, 경로상 +3분. 들를까요?", copy.question)
    }

    @Test
    fun `긴급 주행 가능 거리는 반올림한 km`() {
        val copy = CopyTemplates.describe(task(CarTaskKind.LowRange, Severity.URGENT, rangeKm = 24.2))
        assertEquals("주행 가능 거리가 25km 정도 남았어요.", copy.what)
        assertTrue(copy.deadline.startsWith("지금 바로"))
        assertEquals("가장 가까운 주유소에 들를까요?", copy.question)
    }

    @Test
    fun `강도 상향이면 문구가 강해진다`() {
        val normal = CopyTemplates.describe(task(CarTaskKind.Warning(TelltaleType.ENGINE), Severity.RECOMMEND))
        val strong = CopyTemplates.describe(
            task(CarTaskKind.Warning(TelltaleType.ENGINE), Severity.RECOMMEND),
            escalated = true,
        )
        assertTrue(normal.risk != strong.risk)
        assertTrue(strong.deadline.startsWith("오늘은 꼭"))
    }

    @Test
    fun `보류 정기 점검`() {
        val copy = CopyTemplates.describe(task(CarTaskKind.PeriodicInspection, Severity.HOLD))
        assertEquals("정기 점검 시기예요.", copy.what)
        assertEquals("급하진 않아요.", copy.risk)
        assertEquals("가까운 정비소에 들를까요?", copy.question)
    }

    @Test
    fun `모든 종류와 등급의 질문은 들를까요로 끝난다`() {
        val kinds = TelltaleType.entries.filter { it.isActionable }.map { CarTaskKind.Warning(it) } +
            listOf(CarTaskKind.LowRange, CarTaskKind.PeriodicInspection)
        for (kind in kinds) for (severity in Severity.entries) for (escalated in listOf(false, true)) {
            val copy = CopyTemplates.describe(task(kind, severity, rangeKm = 50.0), escalated = escalated)
            assertTrue(copy.question.endsWith("들를까요?"), "$kind $severity")
            assertTrue(copy.what.isNotBlank() && copy.risk.isNotBlank() && copy.deadline.isNotBlank())
        }
    }

    @Test
    fun `반올림 규칙`() {
        assertEquals(25, CopyTemplates.roundKm(24.2))
        assertEquals(30, CopyTemplates.roundKm(28.0))
        assertEquals(7, CopyTemplates.roundKm(6.6))
        assertEquals(1, CopyTemplates.roundKm(0.2))
        assertEquals(1, CopyTemplates.roundMinutes(0.3))
        assertEquals(9, CopyTemplates.roundMinutes(8.5))
    }
}
