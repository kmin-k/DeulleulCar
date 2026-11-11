package ai.deulleulcar.app.location

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.model.GeoPoint
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.constant.ErrorCode
import ai.pleos.playground.location.fused.exception.LocationException
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos FusedLocation 1.1.3 구현.
 * `isSupported()` → `initialize()` → `registerFusedLocationCallback`, 근접 알림은
 * `addProximityAlert(ProximityAlertRequest(lat, lon, radius, {ENTER, EXIT}), listener)` → `onRegistered(alertId)`.
 * `pleos.car.permission.FUSED_LOCATION`은 dangerous 권한이라 런타임 허용이 필요하다.
 */
class PleosLocationSource(context: Context) : LocationSource {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(FeatureState.UNKNOWN)
    override val state: StateFlow<FeatureState> = _state.asStateFlow()

    private val _location = MutableStateFlow<LocationFix?>(null)
    override val location: StateFlow<LocationFix?> = _location.asStateFlow()

    private val _proximity = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 32)
    override val proximity: SharedFlow<ProximityEvent> = _proximity.asSharedFlow()

    private var fused: FusedLocation? = null
    private var registered = false

    /** placeId → SDK alertId. 등록 응답 전이면 값이 null. */
    private val alerts = mutableMapOf<String, String?>()

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            val d = data ?: return
            val fix = LocationFix(GeoPoint(d.latitude, d.longitude), d.heading, d.confidence, d.timestampMillis)
            main.post {
                if (_location.value == null) Log.i(TAG, "첫 위치: $fix")
                _location.value = fix
            }
        }

        override fun onError(e: Exception) {
            main.post {
                val code = (e as? LocationException)?.errorCode
                Log.w(TAG, "위치 오류: $code ${e.message}")
                if (code == ErrorCode.ERROR_SECURITY_PERMISSION) _state.value = FeatureState.PERMISSION_DENIED
            }
        }
    }

    override fun start() = onMain {
        if (fused != null) return@onMain
        if (appContext.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "$PERMISSION 미허용 → 위치 기능 끔(허용 후 start 다시 호출)")
            _state.value = FeatureState.PERMISSION_DENIED
            return@onMain
        }
        val f = try {
            FusedLocation(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocation 생성 실패", e)
            _state.value = FeatureState.FAILED
            return@onMain
        }
        val supported = runCatching { f.isSupported() }.getOrElse { Log.w(TAG, "isSupported 실패", it); false }
        if (!supported) {
            Log.w(TAG, "FusedLocation 미지원")
            _state.value = FeatureState.NOT_SUPPORTED
            return@onMain
        }
        try {
            f.initialize()
            fused = f
            f.registerFusedLocationCallback(locationListener)
            registered = true
            f.getLastKnownLocation(locationListener)
            _state.value = FeatureState.ACTIVE
            Log.i(TAG, "FusedLocation 시작")
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocation 시작 실패", e)
            _state.value = if (e is SecurityException) FeatureState.PERMISSION_DENIED else FeatureState.FAILED
            runCatching { f.release() }
            fused = null
        }
    }

    override fun addProximityAlert(placeId: String, point: GeoPoint, radiusM: Float) = onMain {
        val f = fused ?: return@onMain Unit.also { Log.w(TAG, "addProximityAlert: 시작 전 ($placeId)") }
        removeAlert(f, placeId)
        alerts[placeId] = null
        val request = ProximityAlertRequest(point.lat, point.lon, radiusM, setOf(ProximityTransition.ENTER, ProximityTransition.EXIT))
        val listener = object : ProximityAlertListener {
            override fun onRegistered(alertId: String) {
                main.post {
                    if (!alerts.containsKey(placeId)) {
                        // 등록 응답 전에 지워 달라는 요청이 있었다.
                        runCatching { fused?.removeProximityAlert(alertId) }
                        return@post
                    }
                    alerts[placeId] = alertId
                    Log.i(TAG, "근접 알림 등록: $placeId → $alertId (${radiusM}m)")
                }
            }

            override fun onTransition(event: ProximityAlertEvent) {
                val at = Instant.now()
                main.post {
                    if (alerts[placeId] != event.alertId) return@post
                    val entered = event.transition == ProximityTransition.ENTER
                    Log.i(TAG, "근접 ${if (entered) "진입" else "이탈"}: $placeId")
                    _proximity.tryEmit(ProximityEvent(placeId, entered, at))
                }
            }

            override fun onError(e: Exception) {
                main.post { Log.w(TAG, "근접 알림 오류($placeId): ${(e as? LocationException)?.errorCode} ${e.message}") }
            }
        }
        try {
            f.addProximityAlert(request, listener)
        } catch (e: Exception) {
            alerts.remove(placeId)
            Log.w(TAG, "addProximityAlert 실패($placeId)", e)
        }
    }

    override fun removeProximityAlert(placeId: String) = onMain { fused?.let { removeAlert(it, placeId) } }

    override fun removeAllProximityAlerts() = onMain {
        val f = fused ?: return@onMain
        alerts.keys.toList().forEach { removeAlert(f, it) }
    }

    override fun release() = onMain {
        val f = fused ?: return@onMain
        alerts.keys.toList().forEach { removeAlert(f, it) }
        if (registered) runCatching { f.unregisterFusedLocationCallback(locationListener) }
        registered = false
        runCatching { f.release() }
        fused = null
        _state.value = FeatureState.UNKNOWN
        Log.i(TAG, "FusedLocation release")
    }

    private fun removeAlert(f: FusedLocation, placeId: String) {
        if (!alerts.containsKey(placeId)) return
        val id = alerts.remove(placeId) ?: return
        runCatching { f.removeProximityAlert(id) }.onFailure { Log.w(TAG, "removeProximityAlert 실패", it) }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    companion object {
        const val TAG = "DC/Location"
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
