package ai.deulleulcar.app.voice

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.app.sdk.RuntimePermissions
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos SpeechToText 3.3.1 ON_DEVICE 구현(앱 등록 불필요, 한국어).
 *
 * SDK API(javap 확인): `SpeechToText(context, Mode.ON_DEVICE)`, `initialize(OnInitializeListener)`, `setLocale`,
 * `addListener(ResultListener)`, `request()`, `stop()`, `release()`.
 * ResultListener: `onReady`, `onStartedRecognition`, `onUpdated(text, completed)`, `onEndedRecognition`, `onError(ErrorType)`.
 * 상태 변경은 메인 스레드에서만 한다.
 */
class PleosSpeechInput(context: Context, private val locale: Locale = Locale.KOREA) : SpeechInput {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(FeatureState.UNKNOWN)
    override val state: StateFlow<FeatureState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<SttEvent> = _events.asSharedFlow()

    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var stt: SpeechToText? = null
    private var ready = false
    private var gotFinal = false
    private var lastPartial: String? = null
    private val timeout = Runnable {
        if (_listening.value) {
            Log.i(TAG, "타임아웃 → stop")
            if (!gotFinal) emitResultEnd(SttEvent.NoResult("timeout"))
            runCatching { stt?.stop() }
            // onEndedRecognition이 안 오는 경우를 대비해 1초 뒤 강제로 닫는다.
            main.postDelayed({ if (_listening.value) ended() }, 1_000)
        }
    }

    private val listener = object : ResultListener {
        override fun onReady() {
            main.post { Log.i(TAG, "onReady") }
        }

        override fun onStartedRecognition() {
            main.post {
                Log.i(TAG, "onStartedRecognition")
                emit(SttEvent.Listening)
            }
        }

        override fun onUpdated(text: String, completed: Boolean) {
            main.post {
                if (!_listening.value) return@post
                Log.i(TAG, "onUpdated(completed=$completed): $text")
                if (completed) {
                    if (!gotFinal) {
                        if (text.isBlank()) emitResultEnd(SttEvent.NoResult("empty")) else emitResultEnd(SttEvent.Final(text))
                    }
                } else {
                    lastPartial = text
                    emit(SttEvent.Partial(text))
                }
            }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) = Unit

        override fun onUpdatedRms(rms: Double) = Unit

        override fun onEndedRecognition() {
            main.post {
                Log.i(TAG, "onEndedRecognition")
                if (_listening.value) ended()
            }
        }

        override fun onError(errorType: ErrorType) {
            main.post {
                Log.w(TAG, "onError: $errorType")
                if (errorType == ErrorType.PERMISSION_DENIED) _state.value = FeatureState.PERMISSION_DENIED
                if (_listening.value) {
                    if (!gotFinal) {
                        emitResultEnd(if (errorType == ErrorType.TIMEOUT) SttEvent.NoResult("timeout") else SttEvent.Error(errorType.name))
                    }
                    ended()
                }
            }
        }
    }

    override fun start() = onMain {
        if (stt != null) return@onMain
        val engine = try {
            SpeechToText(appContext, Mode.ON_DEVICE)
        } catch (e: Exception) {
            Log.w(TAG, "STT 생성 실패", e)
            _state.value = FeatureState.FAILED
            return@onMain
        }
        stt = engine
        engine.addListener(listener)
        engine.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                main.post {
                    if (stt !== engine) return@post
                    runCatching { engine.setLocale(locale) }.onFailure { Log.w(TAG, "setLocale 실패", it) }
                    ready = true
                    _state.value = FeatureState.ACTIVE
                    Log.i(TAG, "STT 초기화 완료 (ON_DEVICE, $locale)")
                }
            }

            override fun onFailure(errorType: ErrorType) {
                main.post {
                    Log.w(TAG, "STT 초기화 실패: $errorType")
                    _state.value = if (errorType == ErrorType.PERMISSION_DENIED) FeatureState.PERMISSION_DENIED else FeatureState.FAILED
                }
            }
        })
    }

    override fun listen(timeoutMs: Long) = onMain {
        val engine = stt
        when {
            engine == null || !ready -> {
                Log.w(TAG, "listen: 초기화 전 (state=${_state.value})")
                emit(SttEvent.Error("not_ready"))
                return@onMain
            }
            appContext.checkSelfPermission(RuntimePermissions.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                Log.w(TAG, "listen: RECORD_AUDIO 미허용")
                emit(SttEvent.Error("record_audio_denied"))
                return@onMain
            }
            _listening.value -> return@onMain
        }
        gotFinal = false
        lastPartial = null
        _listening.value = true
        main.postDelayed(timeout, timeoutMs)
        try {
            engine!!.request()
            Log.i(TAG, "request() 호출 (timeout=${timeoutMs}ms)")
        } catch (e: Exception) {
            Log.w(TAG, "request 실패", e)
            emitResultEnd(SttEvent.Error(e.javaClass.simpleName))
            ended()
        }
    }

    override fun stopListening() = onMain {
        if (!_listening.value) return@onMain
        runCatching { stt?.stop() }
        main.postDelayed({ if (_listening.value) ended() }, 1_000)
    }

    override fun release() = onMain {
        val engine = stt ?: return@onMain
        if (_listening.value) {
            runCatching { engine.stop() }
            ended()
        }
        stt = null
        ready = false
        runCatching { engine.removeListener(listener) }
        runCatching { engine.release() }
        _state.value = FeatureState.UNKNOWN
        Log.i(TAG, "STT release")
    }

    private fun emitResultEnd(event: SttEvent) {
        gotFinal = true
        emit(event)
    }

    private fun ended() {
        main.removeCallbacks(timeout)
        if (!gotFinal) {
            // 최종 결과 없이 끝났으면 마지막 중간 결과라도 쓴다.
            val partial = lastPartial
            emitResultEnd(if (!partial.isNullOrBlank()) SttEvent.Final(partial) else SttEvent.NoResult("ended"))
        }
        _listening.value = false
        emit(SttEvent.Ended)
    }

    private fun emit(event: SttEvent) {
        _events.tryEmit(event)
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    companion object {
        const val TAG = "DC/STT"
    }
}
