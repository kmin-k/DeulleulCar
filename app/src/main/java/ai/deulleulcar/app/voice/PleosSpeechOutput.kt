package ai.deulleulcar.app.voice

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.voice.SpeechPriority
import ai.deulleulcar.core.voice.SpeechQueue
import ai.deulleulcar.core.voice.SpeechWatchdog
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos TextToSpeech 3.3.1 (ON_DEVICE) 구현.
 *
 * SDK에는 큐·발화 id가 없고 `speak(text, isFinal)` + 전역 `onDone/onStop/onError`만 있다(javap·문서 확인).
 * 그래서 앱이 [SpeechQueue]로 한 문장씩 넣고, 끝 이벤트가 오면 다음 문장을 넣는다.
 * 모든 상태 변경은 메인 스레드에서 한다(SDK 콜백도 메인으로 옮김).
 */
class PleosSpeechOutput(context: Context, private val locale: Locale = Locale.KOREA) : SpeechOutput {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(FeatureState.UNKNOWN)
    override val state: StateFlow<FeatureState> = _state.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val queue = SpeechQueue<QueuedSpeech> { it.priority }
    private val watchdog = SpeechWatchdog()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var listenerAdded = false

    private val listener = object : EventListener {
        override fun onReady() = Unit

        override fun onStart() {
            main.post {
                Log.i(TAG, "onStart: ${queue.current?.text}")
                armWatchdog() // 실제 발화 시작 시점부터 다시 잰다
            }
        }

        override fun onDone() {
            main.post { finishCurrent(completed = true, why = "onDone") }
        }

        override fun onStop() {
            main.post { finishCurrent(completed = false, why = "onStop") }
        }

        override fun onError(errorType: ErrorType) {
            main.post { finishCurrent(completed = false, why = "onError $errorType") }
        }

        override fun onUpdatedRms(rms: Double) = Unit
    }

    override fun start() = onMain {
        if (tts != null) return@onMain
        val engine = try {
            TextToSpeech(appContext, Mode.ON_DEVICE)
        } catch (e: Exception) {
            Log.w(TAG, "TTS 생성 실패", e)
            _state.value = FeatureState.FAILED
            return@onMain
        }
        tts = engine
        // addEventListener는 initialize 완료 전에 부르면 SDK 내부 lateinit 접근으로 크래시한다(에뮬레이터 실측).
        // → 초기화 성공 콜백에서 등록한다.
        engine.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                main.post {
                    if (tts !== engine) return@post
                    try {
                        engine.addEventListener(listener)
                        listenerAdded = true
                    } catch (e: Exception) {
                        Log.w(TAG, "addEventListener 실패", e)
                        _state.value = FeatureState.FAILED
                        return@post
                    }
                    runCatching { engine.setLocale(locale) }.onFailure { Log.w(TAG, "setLocale 실패", it) }
                    ready = true
                    _state.value = FeatureState.ACTIVE
                    Log.i(TAG, "TTS 초기화 완료")
                    pump()
                }
            }

            override fun onFailure(errorType: ErrorType) {
                main.post {
                    Log.w(TAG, "TTS 초기화 실패: $errorType")
                    _state.value = if (errorType == ErrorType.PERMISSION_DENIED) FeatureState.PERMISSION_DENIED else FeatureState.FAILED
                    queue.clear().forEach { it.onDone?.invoke(false) }
                }
            }
        })
    }

    override fun speak(text: String, priority: SpeechPriority, onDone: ((Boolean) -> Unit)?) = onMain {
        if (_state.value == FeatureState.FAILED || _state.value == FeatureState.PERMISSION_DENIED) {
            Log.w(TAG, "TTS 사용 불가, 버림: $text")
            onDone?.invoke(false)
            return@onMain
        }
        val interrupt = queue.enqueue(QueuedSpeech(text, priority, onDone))
        if (interrupt) {
            Log.i(TAG, "긴급 문장으로 현재 발화 중지")
            runCatching { tts?.stop() }.onFailure { finishCurrent(false, "stop 실패") }
        } else {
            pump()
        }
    }

    override fun stopAll() = onMain {
        val dropped = queue.clear()
        watchdog.cancel()
        if (dropped.isNotEmpty()) runCatching { tts?.stop() }
        _speaking.value = false
        dropped.forEach { it.onDone?.invoke(false) }
    }

    override fun release() = onMain {
        val engine = tts ?: return@onMain
        queue.clear().forEach { it.onDone?.invoke(false) }
        watchdog.cancel()
        tts = null
        ready = false
        runCatching { engine.stop() }
        if (listenerAdded) runCatching { engine.removeEventListener(listener) }
        listenerAdded = false
        runCatching { engine.release() }
        _speaking.value = false
        _state.value = FeatureState.UNKNOWN
        Log.i(TAG, "TTS release")
    }

    private fun armWatchdog() {
        val text = queue.current?.text ?: return
        val token = watchdog.begin()
        main.postDelayed({
            if (watchdog.onTimeout(token)) {
                Log.w(TAG, "watchdog done: $text")
                finishCurrent(completed = true, why = "watchdog", fromWatchdog = true)
            }
        }, watchdog.estimateMs(text))
    }

    private fun finishCurrent(completed: Boolean, why: String, fromWatchdog: Boolean = false) {
        if (!fromWatchdog && !watchdog.onRealFinish()) {
            Log.i(TAG, "$why 무시(감시 타이머가 이미 처리함)")
            return
        }
        val done = queue.onCurrentFinished()
        Log.i(TAG, "$why: ${done?.text ?: "(끊김 → 다시 대기)"}")
        _speaking.value = false
        done?.onDone?.invoke(completed)
        pump()
    }

    private fun pump() {
        val engine = tts ?: return
        if (!ready) return
        val item = queue.next() ?: return
        _speaking.value = true
        Log.i(TAG, "speak(${item.priority}): ${item.text}")
        armWatchdog()
        try {
            engine.speak(item.text, true)
        } catch (e: Exception) {
            Log.w(TAG, "speak 실패", e)
            finishCurrent(false, "speak 예외")
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    companion object {
        const val TAG = "DC/TTS"
    }
}
