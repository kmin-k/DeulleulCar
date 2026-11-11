package ai.deulleulcar.core.route

import ai.deulleulcar.core.model.PlaceCategory
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteSpeechTest {
    @Test
    fun `조사를 받침에 맞게 고른다`() {
        assertEquals("약국이", Josa.iGa("약국"))
        assertEquals("마트가", Josa.iGa("마트"))
        assertEquals("약국이에요", Josa.ieyo("약국"))
        assertEquals("마트예요", Josa.ieyo("마트"))
        assertEquals("세탁소를", Josa.eulReul("세탁소"))
        assertEquals("ATM가", Josa.iGa("ATM"))
    }

    @Test
    fun `시각은 12시간제로 짧게 말한다`() {
        assertEquals("7시", RouteSpeech.clock(LocalDateTime.of(2026, 11, 11, 19, 0)))
        assertEquals("12시 30분", RouteSpeech.clock(LocalDateTime.of(2026, 11, 11, 0, 30)))
    }

    @Test
    fun `영업 종료 이유 문구`() {
        val pharmacy = Fixtures.human(Fixtures.place("p", 0.05, category = PlaceCategory.PHARMACY))
        val reason = ReorderReason.ClosesSoon("p", LocalDateTime.of(2026, 11, 11, 19, 0), pharmacy)
        assertEquals("약국이 7시에 닫아서 먼저 갈게요.", RouteSpeech.reorderReason(reason))
    }
}
