package ai.deulleulcar.core.parser

/**
 * 네트워크를 쓸 수 있는 비동기 볼일 파서. 동기 [ErrandParser]는 [asSuspend]로 감싼다.
 * 해석할 수 없으면 [ErrandParseException]을 던진다.
 */
fun interface SuspendErrandParser {
    suspend fun parse(utterance: String): ParsedRequest
}

/** 동기 파서를 비동기 인터페이스로. */
fun ErrandParser.asSuspend(): SuspendErrandParser = SuspendErrandParser { parse(it) }

/**
 * 파서가 발화를 쓸 수 있는 구조로 만들지 못함.
 * @property noErrands 형식은 맞지만 볼일을 하나도 찾지 못한 경우 true
 */
class ErrandParseException(
    message: String,
    cause: Throwable? = null,
    val noErrands: Boolean = false,
) : Exception(message, cause)
