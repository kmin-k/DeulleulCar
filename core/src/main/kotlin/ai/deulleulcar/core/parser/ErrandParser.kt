package ai.deulleulcar.core.parser

import ai.deulleulcar.core.model.Errand

/** 목적지 힌트. 좌표 해석은 설정(집·회사 위치)과 장소 검색이 맡는다. */
sealed interface DestinationHint {
    data object Home : DestinationHint
    data object Work : DestinationHint
    data class Named(val name: String) : DestinationHint
}

/** 발화에 담긴 시간·상황 힌트. */
enum class TimeHint {
    /** "퇴근길에", "퇴근하면서" */
    COMMUTE_HOME,

    /** "출근길에" */
    COMMUTE_WORK,

    /** "가는 길에", "가다가" */
    ON_THE_WAY,
}

/** 볼일 발화 하나를 구조화한 결과. [errands]는 말한 순서를 지키고 중복이 없다. */
data class ParsedRequest(
    val errands: List<Errand>,
    val destination: DestinationHint? = null,
    val timeHint: TimeHint? = null,
)

/**
 * 한국어 볼일 발화 → [ParsedRequest].
 * 구현: LLM 기반(T6)과 규칙 기반 fallback([RuleBasedErrandParser]). LLM이 실패하면 fallback을 쓴다.
 */
fun interface ErrandParser {
    fun parse(utterance: String): ParsedRequest
}
