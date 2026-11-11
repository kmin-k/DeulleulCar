package ai.deulleulcar.app.vehicle

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.model.CarSignal
import ai.deulleulcar.core.model.TelltaleState
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.ErrorCode
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType as SdkTelltaleType

/**
 * Pleos Vehicle SDK 2.3.2 구현.
 *
 * 사용하는 API (Gradle 캐시의 AAR을 javap로 확인):
 * - 경고등: `Safety.checkTelltalesCapabilities` → `registerTelltales(TelltaleInfoListener)` + `getTelltalesInfo`
 *   (SDK 어노테이션상 권한은 `PRIVATE_CAR_SAFETY`, 문서상 `CAR_SAFETY` → 매니페스트에 둘 다 선언)
 * - 주행 가능 거리: `Odometer.getRangeRemainingDistance` + `registerRangeRemainingListener` (CAR_DRIVE, capability 확인 API 없음, 값은 미터)
 * - 기어: `Gear.getCurrentGearSelectionState` + `registerGearSelection` (CAR_DRIVE, capability 확인 API 없음)
 * - 누적 주행거리: `Odometer.checkCurrentDistanceDrivenCapabilities` → `registerDistanceDriven` + `getCurrentDistanceDriven`
 *
 * 모든 호출은 비동기 IPC다. 콜백은 SDK 스레드에서 올 수 있으므로 상태는 [lock]으로 보호하고,
 * [stop] 뒤 늦게 도착한 콜백은 세대 번호([generation])로 걸러 낸다.
 * `release()`는 리스너를 모두 해제하고 진행 중인 요청이 끝난 뒤(최대 [RELEASE_GRACE_MS]) 호출한다.
 */
class PleosVehicleSignalSource(context: Context) : VehicleSignalSource {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val _signals = MutableSharedFlow<CarSignal>(extraBufferCapacity = 64)
    override val signals: SharedFlow<CarSignal> = _signals.asSharedFlow()

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    override val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val _features = MutableStateFlow(VehicleFeature.entries.associateWith { FeatureState.UNKNOWN })
    override val features: StateFlow<Map<VehicleFeature, FeatureState>> = _features.asStateFlow()

    private var vehicle: Vehicle? = null
    private var generation = 0
    private var inFlight = 0
    private val unregisters = mutableListOf<() -> Unit>()

    // 경고등은 목록 전체가 오므로 직전 상태와 비교해 바뀐 것만 내보낸다.
    private val lastTelltales = mutableMapOf<SdkTelltaleType, TelltaleState>()
    private var lastRange: Double? = null
    private var lastOdometer: Double? = null

    override fun start() {
        synchronized(lock) {
            if (vehicle != null) return
            val v = try {
                Vehicle(appContext).also { it.initialize() }
            } catch (e: Exception) {
                Log.w(TAG, "Vehicle 초기화 실패", e)
                _features.value = VehicleFeature.entries.associateWith { FeatureState.FAILED }
                return
            }
            vehicle = v
            generation++
            lastTelltales.clear()
            lastRange = null
            lastOdometer = null
            _features.value = VehicleFeature.entries.associateWith { FeatureState.UNKNOWN }
            Log.i(TAG, "Vehicle initialize (gen=$generation)")
        }
        val gen = synchronized(lock) { generation }
        val v = synchronized(lock) { vehicle } ?: return
        subscribeTelltales(v, gen, 0)
        subscribeRange(v, gen, 0)
        subscribeGear(v, gen, 0)
        subscribeOdometer(v, gen, 0)
    }

    override fun stop() {
        val v: Vehicle
        val actions: List<() -> Unit>
        synchronized(lock) {
            v = vehicle ?: return
            vehicle = null
            generation++
            actions = unregisters.toList()
            unregisters.clear()
        }
        actions.forEach { runCatching(it).onFailure { e -> Log.w(TAG, "unregister 실패", e) } }
        releaseWhenIdle(v, waitedMs = 0)
    }

    private fun releaseWhenIdle(v: Vehicle, waitedMs: Long) {
        val pending = synchronized(lock) { inFlight }
        if (pending > 0 && waitedMs < RELEASE_GRACE_MS) {
            main.postDelayed({ releaseWhenIdle(v, waitedMs + 100) }, 100)
            return
        }
        runCatching { v.release() }.onFailure { Log.w(TAG, "release 실패", it) }
        Log.i(TAG, "Vehicle release (pending=$pending)")
    }

    // ---- 구독 ----

    private fun subscribeTelltales(v: Vehicle, gen: Int, attempt: Int) {
        val safety = v.getSafety()
        call(VehicleFeature.TELLTALES, gen, attempt, { subscribeTelltales(v, gen, it) }) { done, fail ->
            safety.checkTelltalesCapabilities({ cfg ->
                done()
                Log.i(TAG, "TELLTALES capability: $cfg")
                val listener = object : TelltaleInfoListener {
                    override fun onTelltaleInfoUpdated(telltales: Telltales?) {
                        if (telltales != null && isCurrent(gen)) onTelltales(telltales)
                    }

                    override fun onFailed(e: Exception) = onListenerFailed(VehicleFeature.TELLTALES, gen, e)
                }
                if (register(gen, { safety.registerTelltales(listener) }, { safety.unregisterTelltales(listener) })) {
                    markActive(VehicleFeature.TELLTALES)
                    getOnce(gen) { ok, err -> safety.getTelltalesInfo({ if (isCurrent(gen)) onTelltales(it); ok() }, err) }
                }
            }, fail)
        }
    }

    private fun subscribeRange(v: Vehicle, gen: Int, attempt: Int) {
        val odometer = v.getOdometer()
        val listener = object : RangeRemainingListener {
            override fun onRangeRemainingChanged(value: Float?) {
                if (isCurrent(gen)) onRange(value)
            }

            override fun onFailed(e: Exception) = onListenerFailed(VehicleFeature.RANGE, gen, e)
        }
        // capability 확인 API가 없으므로 첫 get 결과로 지원 여부를 판정한다.
        call(VehicleFeature.RANGE, gen, attempt, { subscribeRange(v, gen, it) }) { done, fail ->
            odometer.getRangeRemainingDistance({ value ->
                done()
                if (isCurrent(gen)) onRange(value)
                if (register(gen, { odometer.registerRangeRemainingListener(listener) }, { odometer.unregisterRangeRemainingListener(listener) })) {
                    markActive(VehicleFeature.RANGE)
                }
            }, fail)
        }
    }

    private fun subscribeGear(v: Vehicle, gen: Int, attempt: Int) {
        val gear = v.getGear()
        val listener = object : GearSelectionListener {
            override fun onSelected(value: VehicleGear?) {
                if (isCurrent(gen)) onGear(value)
            }

            override fun onFailed(e: Exception) = onListenerFailed(VehicleFeature.GEAR, gen, e)
        }
        call(VehicleFeature.GEAR, gen, attempt, { subscribeGear(v, gen, it) }) { done, fail ->
            gear.getCurrentGearSelectionState({ value ->
                done()
                if (isCurrent(gen)) onGear(value)
                if (register(gen, { gear.registerGearSelection(listener) }, { gear.unregisterGearSelection(listener) })) {
                    markActive(VehicleFeature.GEAR)
                }
            }, fail)
        }
    }

    private fun subscribeOdometer(v: Vehicle, gen: Int, attempt: Int) {
        val odometer = v.getOdometer()
        call(VehicleFeature.ODOMETER, gen, attempt, { subscribeOdometer(v, gen, it) }) { done, fail ->
            odometer.checkCurrentDistanceDrivenCapabilities({ cfg ->
                done()
                Log.i(TAG, "ODOMETER capability: $cfg")
                val listener = object : DistanceDrivenListener {
                    override fun onDistanceDrivenUpdated(value: Float?) {
                        if (isCurrent(gen)) onOdometer(value)
                    }

                    override fun onFailed(e: Exception) = onListenerFailed(VehicleFeature.ODOMETER, gen, e)
                }
                if (register(gen, { odometer.registerDistanceDriven(listener) }, { odometer.unregisterDistanceDriven(listener) })) {
                    markActive(VehicleFeature.ODOMETER)
                    getOnce(gen) { ok, err -> odometer.getCurrentDistanceDriven({ if (isCurrent(gen)) onOdometer(it); ok() }, err) }
                }
            }, fail)
        }
    }

    // ---- 값 처리 ----

    private fun onTelltales(telltales: Telltales) {
        val changes = mutableListOf<CarSignal.Telltale>()
        synchronized(lock) {
            val now = telltales.telltales.associate { it.type to VehicleMappers.telltaleState(it.state) }
            // 목록에서 빠진 경고등은 꺼진 것으로 본다.
            for (sdkType in lastTelltales.keys + now.keys) {
                val before = lastTelltales[sdkType] ?: TelltaleState.OFF
                val after = now[sdkType] ?: TelltaleState.OFF
                if (before != after) {
                    Log.d(TAG, "telltale $sdkType: $before -> $after")
                    changes += CarSignal.Telltale(VehicleMappers.telltaleType(sdkType), after)
                }
            }
            lastTelltales.clear()
            lastTelltales.putAll(now.filterValues { it != TelltaleState.OFF })
        }
        changes.forEach(::emit)
    }

    private fun onRange(value: Float?) {
        // RANGE_REMAINING은 미터 단위로 온다(에뮬레이터 실측: 500km → 500000.0, Virtual Console 필드명 remaining_range_m).
        val km = value?.let { it / 1000.0 } ?: return
        val changed = synchronized(lock) { (lastRange != km).also { lastRange = km } }
        if (changed) emit(CarSignal.RemainingRange(km))
    }

    private fun onGear(value: VehicleGear?) {
        val gear = VehicleMappers.gear(value) ?: return
        if (_snapshot.value.gear != gear) emit(CarSignal.GearChanged(gear))
    }

    private fun onOdometer(value: Float?) {
        val km = value?.toDouble() ?: return
        val changed = synchronized(lock) { (lastOdometer != km).also { lastOdometer = km } }
        if (changed) emit(CarSignal.Odometer(km))
    }

    private fun emit(signal: CarSignal) {
        Log.i(TAG, "CarSignal $signal")
        _snapshot.update { it.apply(signal) }
        _signals.tryEmit(signal)
    }

    // ---- 공통 처리 ----

    private fun isCurrent(gen: Int) = synchronized(lock) { gen == generation && vehicle != null }

    private fun beginRequest(): () -> Unit {
        synchronized(lock) { inFlight++ }
        val finished = AtomicBoolean(false)
        return { if (finished.compareAndSet(false, true)) synchronized(lock) { inFlight-- } }
    }

    /**
     * 비동기 요청 하나를 실행한다. 서비스 연결 전이라 실패하면 [MAX_RETRY]번까지 다시 시도하고,
     * 미지원·권한 오류는 기능 상태로 기록하고 끝낸다(앱은 그 기능 없이 동작).
     */
    private fun call(
        feature: VehicleFeature,
        gen: Int,
        attempt: Int,
        retry: (Int) -> Unit,
        block: (done: () -> Unit, fail: (Exception) -> Unit) -> Unit,
    ) {
        if (!isCurrent(gen)) return
        val finish = beginRequest()
        val fail: (Exception) -> Unit = { e ->
            finish()
            if (isCurrent(gen)) {
                val code = VehicleMappers.errorCode(e)
                if (code == ErrorCode.ERROR_SERVICE_CONNECTION_FAILURE && attempt < MAX_RETRY) {
                    Log.w(TAG, "$feature: 서비스 연결 전, ${RETRY_DELAY_MS}ms 뒤 재시도(${attempt + 1})")
                    main.postDelayed({ retry(attempt + 1) }, RETRY_DELAY_MS)
                } else {
                    Log.w(TAG, "$feature 사용 불가: $code (${e.javaClass.name}: ${e.message})")
                    _features.update { it + (feature to VehicleMappers.featureState(code)) }
                }
            }
        }
        try {
            block(finish, fail)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** 초기값 한 번 읽기. 실패해도 구독은 유지한다. */
    private fun getOnce(gen: Int, block: (ok: () -> Unit, err: (Exception) -> Unit) -> Unit) {
        if (!isCurrent(gen)) return
        val finish = beginRequest()
        try {
            block(finish) { e ->
                finish()
                Log.w(TAG, "초기값 읽기 실패: ${VehicleMappers.errorCode(e)}")
            }
        } catch (e: Exception) {
            finish()
            Log.w(TAG, "초기값 읽기 예외", e)
        }
    }

    /** 리스너 등록. 등록 직후 stop()이 끼어들었으면 바로 해제한다. */
    private fun register(gen: Int, doRegister: () -> Unit, doUnregister: () -> Unit): Boolean {
        try {
            doRegister()
        } catch (e: Exception) {
            Log.w(TAG, "register 실패: ${VehicleMappers.errorCode(e)}", e)
            return false
        }
        val keep = synchronized(lock) {
            if (gen == generation && vehicle != null) {
                unregisters += doUnregister
                true
            } else {
                false
            }
        }
        if (!keep) runCatching(doUnregister)
        return keep
    }

    private fun onListenerFailed(feature: VehicleFeature, gen: Int, e: Exception) {
        if (!isCurrent(gen)) return
        val code = VehicleMappers.errorCode(e)
        Log.w(TAG, "$feature listener onFailed: $code")
        _features.update { it + (feature to VehicleMappers.featureState(code)) }
    }

    private fun markActive(feature: VehicleFeature) {
        Log.i(TAG, "$feature 구독 시작")
        _features.update { it + (feature to FeatureState.ACTIVE) }
    }

    companion object {
        const val TAG = "DC/Vehicle"
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY_MS = 1_000L
        private const val RELEASE_GRACE_MS = 2_000L
    }
}
