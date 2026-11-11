package ai.deulleulcar.core.route

import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.model.StopSource
import java.time.LocalDateTime

/** 한국어 조사 선택. 한글이 아닌 글자로 끝나면 받침 없음으로 본다. */
object Josa {
    fun hasFinalConsonant(word: String): Boolean {
        val c = word.trimEnd().lastOrNull() ?: return false
        if (c !in '가'..'힣') return false
        return (c - '가') % 28 != 0
    }

    /** 이/가 */
    fun iGa(word: String) = word + if (hasFinalConsonant(word)) "이" else "가"

    /** 이에요/예요 */
    fun ieyo(word: String) = word + if (hasFinalConsonant(word)) "이에요" else "예요"

    /** 으로/로 (ㄹ받침은 "로") */
    fun euroRo(word: String): String {
        val c = word.trimEnd().lastOrNull()
        val rieul = c != null && c in '가'..'힣' && (c - '가') % 28 == 8
        return word + if (hasFinalConsonant(word) && !rieul) "으로" else "로"
    }

    /** 은/는 */
    fun eunNeun(word: String) = word + if (hasFinalConsonant(word)) "은" else "는"

    /** 을/를 */
    fun eulReul(word: String) = word + if (hasFinalConsonant(word)) "을" else "를"
}

/** 계획 관련 TTS 문구. 카드와 음성이 같은 문구를 쓴다. */
object RouteSpeech {
    /** 정류지를 부르는 짧은 이름: 사람의 볼일은 말한 표현("약국"), 차의 할 일은 업종("주유소"). */
    fun nameOf(stop: Stop): String = when (stop.source) {
        StopSource.HUMAN -> stop.errand?.keyword ?: stop.place.category.label
        StopSource.CAR -> stop.place.category.label
    }

    /** "약국이 7시에 닫아서 먼저 갈게요" / "빵집이 아직 안 열어서 나중에 갈게요" */
    fun reorderReason(reason: ReorderReason, name: String = nameOf(reason.stop)): String = when (reason) {
        is ReorderReason.ClosesSoon -> "${Josa.iGa(name)} ${clock(reason.closingTime)}에 닫아서 먼저 갈게요."
        is ReorderReason.OpensLater -> "${Josa.iGa(name)} 아직 안 열어서 나중에 갈게요."
    }

    /** "약국 → 마트, 직행보다 +4분" */
    fun summary(plan: RoutePlan): String {
        if (plan.stops.isEmpty()) return "바로 목적지로 갈게요."
        val names = plan.stops.joinToString(" → ") { nameOf(it.stop) }
        return "$names, 직행보다 +${plan.extraDriveMinutes}분이에요."
    }

    /** 19:00 → "7시", 19:30 → "7시 30분". 오전/오후는 말하지 않는다(맥락상 분명함). */
    fun clock(t: LocalDateTime): String {
        val h = (t.hour % 12).let { if (it == 0) 12 else it }
        return if (t.minute == 0) "${h}시" else "${h}시 ${t.minute}분"
    }
}
