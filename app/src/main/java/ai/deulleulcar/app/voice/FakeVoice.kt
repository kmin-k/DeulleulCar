package ai.deulleulcar.app.voice

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.voice.SpeechPriority
import ai.deulleulcar.core.voice.SpeechQueue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 데모·테스트용 TTS. 실제로 소리를 내지 않고 [spoken]에 기록한다.
 * [autoComplete]가 true면 넣자마자 다 말한 것으로 처리하고, false면 [finishCurrent]로 직접 끝낸다.
 */
class FakeSpeechOutput(private val autoComplete: Boolean = true) : SpeechOutput {
    private val _state = MutableStateFlow(FeatureState.UNKNOWN)
    override val state: StateFlow<FeatureState> = _state.asStateFlow()
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val queue = SpeechQueue<QueuedSpeech> { it.priority }
    val spoken = mutableListOf<String>()

    override fun start() {
        _state.value = FeatureState.ACTIVE
    }

    @Synchronized
    override fun speak(text: String, priority: SpeechPriority, onDone: ((Boolean) -> Unit)?) {
        if (queue.enqueue(QueuedSpeech(text, priority, onDone))) finish(completed = false)
        pump()
    }

    /** [autoComplete]가 false일 때 지금 문장을 끝낸다. */
    @Synchronized
    fun finishCurrent() {
        finish(completed = true)
        pump()
    }

    @Synchronized
    override fun stopAll() {
        queue.clear().forEach { it.onDone?.invoke(false) }
        _speaking.value = false
    }

    override fun release() = stopAll()

    private fun finish(completed: Boolean) {
        val done = queue.onCurrentFinished()
        _speaking.value = false
        done?.onDone?.invoke(completed)
    }

    private fun pump() {
        while (true) {
            val item = queue.next() ?: return
            spoken += item.text
            _speaking.value = true
            if (!autoComplete) return
            finish(completed = true)
        }
    }
}

/** 데모·테스트용 STT. [listen]하면 [scriptedUtterances]를 하나씩 최종 결과로 돌려준다. */
class FakeSpeechInput(private val scriptedUtterances: ArrayDeque<String> = ArrayDeque()) : SpeechInput {
    private val _state = MutableStateFlow(FeatureState.UNKNOWN)
    override val state: StateFlow<FeatureState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<SttEvent> = _events.asSharedFlow()
    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()

    override fun start() {
        _state.value = FeatureState.ACTIVE
    }

    override fun listen(timeoutMs: Long) {
        _listening.value = true
        _events.tryEmit(SttEvent.Listening)
        val text = scriptedUtterances.removeFirstOrNull()
        _events.tryEmit(if (text != null) SttEvent.Final(text) else SttEvent.NoResult("no_script"))
        _listening.value = false
        _events.tryEmit(SttEvent.Ended)
    }

    /** 다음 [listen] 때 돌려줄 발화를 넣는다. */
    fun enqueue(text: String) = scriptedUtterances.addLast(text)

    override fun stopListening() {
        _listening.value = false
    }

    override fun release() = Unit
}
