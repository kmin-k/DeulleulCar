package ai.deulleulcar.app.voice

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.voice.SpeechPriority
import kotlinx.coroutines.flow.StateFlow

/**
 * TTS 출력. 한 번에 한 문장씩 말하고 나머지는 큐에 쌓는다.
 * - [SpeechPriority.URGENT]는 큐 맨 앞으로 가고, 일반 문장을 말하는 중이면 끊고 바로 말한다(끊긴 문장은 긴급 문장 뒤에 다시 말함).
 * - 말할 타이밍(회전 직전 보류)은 여기서 판단하지 않는다 → [VoiceAnnouncer]가 `VoiceTurnScheduler`로 거른 뒤 넣는다.
 */
interface SpeechOutput {
    val state: StateFlow<FeatureState>

    /** 지금 말하는 중인지. */
    val speaking: StateFlow<Boolean>

    fun start()

    /**
     * @param onDone 이 문장을 다 말했거나(true) 실패·취소됐을 때(false) 한 번 불린다.
     */
    fun speak(text: String, priority: SpeechPriority = SpeechPriority.NORMAL, onDone: ((completed: Boolean) -> Unit)? = null)

    /** 말하는 중인 문장과 큐를 모두 버린다. */
    fun stopAll()

    fun release()
}

/** 큐에 들어간 문장 하나. 구현체 공용. */
internal data class QueuedSpeech(
    val text: String,
    val priority: SpeechPriority,
    val onDone: ((Boolean) -> Unit)?,
)
