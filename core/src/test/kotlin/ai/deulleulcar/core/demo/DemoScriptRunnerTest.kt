package ai.deulleulcar.core.demo

import ai.deulleulcar.core.model.TelltaleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoScriptRunnerTest {
    private val script = DemoScenario(
        DemoScenarioId.S1,
        listOf(
            DemoStep.Act(DemoAction.Reset, "처음"),
            DemoStep.Pause(4_000, "쉬기"),
            DemoStep.Act(DemoAction.Say("응"), "말하기"),
            DemoStep.Wait(DemoCondition.TRIP_STARTED, "기다리기", timeoutMs = 10_000),
            DemoStep.Act(DemoAction.Gear(driving = true), "출발"),
        ),
    )

    @Test
    fun `한 틱에 행동은 하나씩 내고 쉬는 시간은 배속만큼 줄어든다`() {
        val r = DemoScriptRunner(script, startMs = 0)
        val idle = DemoObservation()
        assertEquals(DemoAction.Reset, r.tick(0, idle, DemoSpeed.X1))
        assertNull(r.tick(500, idle, DemoSpeed.X4)) // 0.5초×4 = 2초 / 4초
        assertEquals("쉬기", r.caption)
        assertEquals(DemoAction.Say("응"), r.tick(1_000, idle, DemoSpeed.X4))
        assertNull(r.tick(3_000, idle, DemoSpeed.X1))
        assertEquals("기다리기", r.caption)
        assertEquals(DemoAction.Gear(true), r.tick(4_000, DemoObservation(guiding = true), DemoSpeed.X1))
        assertNull(r.tick(5_000, idle, DemoSpeed.X1))
        assertEquals(DemoRunStatus.DONE, r.status)
    }

    @Test
    fun `1x 쉬는 시간은 실제 시간만큼`() {
        val r = DemoScriptRunner(script, startMs = 0)
        r.tick(0, DemoObservation(), DemoSpeed.X1)
        assertNull(r.tick(1_000, DemoObservation(), DemoSpeed.X1))
        assertNull(r.tick(3_000, DemoObservation(), DemoSpeed.X1))
        assertEquals(DemoAction.Say("응"), r.tick(4_000, DemoObservation(), DemoSpeed.X1))
    }

    @Test
    fun `다음은 쉬는 시간과 대기를 건너뛴다`() {
        val r = DemoScriptRunner(script, startMs = 0)
        r.tick(0, DemoObservation(), DemoSpeed.X1)
        assertTrue(r.pausing)
        r.skip(100)
        assertEquals(DemoAction.Say("응"), r.tick(200, DemoObservation(), DemoSpeed.X1))
        r.tick(300, DemoObservation(), DemoSpeed.X1)
        r.skip(400) // 대기 건너뛰기(수동 진행)
        assertEquals(DemoAction.Gear(true), r.tick(500, DemoObservation(), DemoSpeed.X1))
    }

    @Test
    fun `대기가 너무 길면 멈춤으로 표시하고 조건이 되면 다시 이어 간다`() {
        val r = DemoScriptRunner(script, startMs = 0)
        r.tick(0, DemoObservation(), DemoSpeed.X1)
        r.skip(0)
        r.tick(0, DemoObservation(), DemoSpeed.X1) // Say
        r.tick(1_000, DemoObservation(), DemoSpeed.X1)
        r.tick(11_000, DemoObservation(), DemoSpeed.X1)
        assertEquals(DemoRunStatus.STUCK, r.status)
        assertTrue(r.caption.contains("다음"))
        assertEquals(DemoAction.Gear(true), r.tick(12_000, DemoObservation(guiding = true), DemoSpeed.X1))
        assertTrue(r.status != DemoRunStatus.STUCK)
    }

    @Test
    fun `시나리오 1~4는 모두 초기화로 시작하고 안내가 끝날 때까지 간다`() {
        assertEquals(DemoScenarioId.entries.toList(), DemoScenarios.all.map { it.id })
        for (s in DemoScenarios.all) {
            assertEquals(DemoAction.Reset, (s.steps.first() as DemoStep.Act).action, "${s.id}")
            assertEquals(DemoCondition.TRIP_FINISHED, (s.steps.last() as DemoStep.Wait).until, "${s.id}")
            assertTrue(s.steps.all { it.caption.isNotBlank() })
        }
        val s3 = DemoScenarios.of(DemoScenarioId.S3).steps.filterIsInstance<DemoStep.Act>().map { it.action }
        assertTrue(DemoAction.Telltale(TelltaleType.TPMS, on = true) in s3)
        assertTrue(DemoAction.Say(DemoScenarios.LATER) in s3)
        val s4 = DemoScenarios.of(DemoScenarioId.S4).steps.filterIsInstance<DemoStep.Act>().map { it.action }
        assertTrue(DemoAction.Range(25.0) in s4)
    }

    @Test
    fun `조건 판정`() {
        val o = DemoObservation(proposalReady = true, pendingSuggestions = 1)
        assertTrue(o.satisfies(DemoCondition.SUGGESTION_PENDING))
        assertTrue(!o.satisfies(DemoCondition.NO_SUGGESTION_PENDING))
        assertTrue(o.satisfies(DemoCondition.TRIP_FINISHED))
        assertTrue(!DemoObservation(pendingSuggestions = 0).satisfies(DemoCondition.NO_SUGGESTION_PENDING))
    }
}
