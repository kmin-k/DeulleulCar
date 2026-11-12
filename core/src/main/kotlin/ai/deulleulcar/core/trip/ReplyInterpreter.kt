package ai.deulleulcar.core.trip

import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.parser.ErrandParser
import ai.deulleulcar.core.parser.RuleBasedErrandParser

/** 앱이 "들를까요?"·"출발할까요?"를 물은 뒤 들어온 말의 뜻. */
enum class Reply {
    YES,
    NO,

    /** 예/아니오가 아니라 새 볼일 요청("약국 하나 더 들러" 등). */
    NEW_REQUEST,

    /** 알아듣지 못함. */
    UNKNOWN,
}

/**
 * 짧은 대답을 예/아니오로 읽는다 (ux-flow §5: 질문은 "들를까요?", 답은 예/아니오만).
 *
 * - 부정어를 먼저 본다("안 들러", "다음에 들를게"는 아니오).
 * - 업종이 있는 볼일이 들어 있으면 새 요청으로 본다("약국 들러"는 예가 아니라 새 볼일).
 * - 다섯 어절이 넘는 긴 말은 예/아니오로 읽지 않는다.
 */
class ReplyInterpreter(private val parser: ErrandParser = RuleBasedErrandParser()) {

    fun interpret(text: String): Reply {
        val normalized = text.lowercase().replace(Regex("[,.!?~·]"), " ").replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return Reply.UNKNOWN
        val hasErrand = parser.parse(normalized).errands.any { it.category != PlaceCategory.OTHER }
        if (hasErrand) return Reply.NEW_REQUEST
        val tokens = normalized.split(' ')
        if (tokens.size > MAX_TOKENS) return Reply.UNKNOWN
        val compact = normalized.replace(" ", "")
        if (NO_PHRASES.any { compact.contains(it) } || tokens.any { it in NO_TOKENS }) return Reply.NO
        if (tokens.any { it in YES_TOKENS } || YES_PREFIXES.any { p -> tokens.any { it.startsWith(p) } }) return Reply.YES
        return Reply.UNKNOWN
    }

    private companion object {
        const val MAX_TOKENS = 5
        val NO_PHRASES = listOf(
            "아니", "아냐", "아뇨", "싫어", "다음에", "나중에", "됐어", "됐습니다", "괜찮아", "괜찮습니다", "취소", "말고",
            "안들", "안가", "안할", "그만", "필요없",
        )
        val NO_TOKENS = setOf("노", "no", "nope")
        val YES_TOKENS = setOf("응", "웅", "어", "엉", "네", "넵", "예", "콜", "ok", "okay", "yes", "그래", "좋아", "고")
        val YES_PREFIXES = listOf(
            "좋아", "좋습", "좋죠", "그래", "그러자", "그럽시다", "출발", "가자", "갑시다", "들러", "들를", "들르", "넣어", "넣자",
            "오케이", "알겠", "부탁", "당연", "물론", "고고",
        )
    }
}
