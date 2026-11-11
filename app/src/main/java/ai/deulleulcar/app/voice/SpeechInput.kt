package ai.deulleulcar.app.voice

import ai.deulleulcar.app.sdk.FeatureState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 음성 인식 진행 이벤트. */
sealed interface SttEvent {
    /** 마이크가 열리고 말할 준비가 됨. */
    data object Listening : SttEvent

    /** 중간 결과(화면에 흐리게 표시용). */
    data class Partial(val text: String) : SttEvent

    /** 최종 결과. 한 번 듣기에 한 번 나온다. */
    data class Final(val text: String) : SttEvent

    /** 아무 말도 인식되지 않은 채 끝남(타임아웃 포함). */
    data class NoResult(val reason: String) : SttEvent

    data class Error(val reason: String) : SttEvent

    /** 마이크가 닫힘. [Final]/[NoResult]/[Error] 뒤에 온다. */
    data object Ended : SttEvent
}

/**
 * 음성 입력(STT). 한 번에 한 발화만 듣는다.
 * [listen] → [SttEvent.Listening] → [SttEvent.Partial]* → [SttEvent.Final] 또는 [SttEvent.NoResult]/[SttEvent.Error] → [SttEvent.Ended]
 */
interface SpeechInput {
    val state: StateFlow<FeatureState>

    val events: SharedFlow<SttEvent>

    val listening: StateFlow<Boolean>

    fun start()

    /** @param timeoutMs 이 시간 안에 최종 결과가 없으면 멈추고 [SttEvent.NoResult]. */
    fun listen(timeoutMs: Long = DEFAULT_TIMEOUT_MS)

    fun stopListening()

    fun release()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
