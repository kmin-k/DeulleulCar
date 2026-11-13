package ai.deulleulcar.core.cartask

import ai.deulleulcar.core.model.CarSignal
import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.CarTaskStatus
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.TelltaleState
import java.time.Clock
import java.time.Duration

/** 주행 가능 거리 등급 경계. 값 미만이면 해당 등급. */
data class RangePolicy(val urgentBelowKm: Double = 30.0, val recommendBelowKm: Double = 80.0) {
    init {
        require(urgentBelowKm < recommendBelowKm)
    }
}

/** 정기 점검 기준: 마지막 점검 시 누적 주행거리 + 주기. */
data class InspectionPolicy(val lastInspectionKm: Double = 0.0, val intervalKm: Double = 10_000.0) {
    fun isDue(odometerKm: Double): Boolean = odometerKm - lastInspectionKm >= intervalKm
}

/** "나중에"를 이만큼 고르거나 감지 후 이만큼 지나면 권장 문구를 한 단계 강하게 한다. */
data class EscalationPolicy(val declines: Int = 3, val age: Duration = Duration.ofDays(3))

/** 신호 하나를 규칙으로 판정한 결과 (상태 없음). */
sealed interface Assessment {
    /** 이 종류의 할 일이 [severity] 등급으로 있어야 한다. */
    data class Raise(
        val kind: CarTaskKind,
        val severity: Severity,
        val rangeKm: Double? = null,
        val odometerKm: Double? = null,
    ) : Assessment

    /** 이 종류의 할 일 원인이 사라졌다. */
    data class Clear(val kind: CarTaskKind) : Assessment

    /** 할 일과 관계없는 신호. */
    data object Ignore : Assessment
}

/** [CarTaskClassifier.onSignal]이 할 일 목록에 일으킨 변화. UI·TTS는 이 값을 보고 알릴지 정한다. */
sealed interface CarTaskUpdate {
    data class Created(val task: CarTask) : CarTaskUpdate

    /** 같은 종류의 할 일이 이미 있어 새로 만들지 않고 등급만 바꿨다. */
    data class SeverityChanged(val before: CarTask, val after: CarTask) : CarTaskUpdate

    data class Resolved(val task: CarTask) : CarTaskUpdate

    /** 변화 없음 (중복 신호, 무시 대상 신호 등). */
    data object None : CarTaskUpdate
}

/**
 * 차량 신호 → 차의 할 일 등급 분류기 + 할 일 목록.
 *
 * 규칙 (docs/telltale-severity.md):
 * - 경고등 ON은 [ai.deulleulcar.core.model.TelltaleType.baseSeverity], BLINK는 한 단계 위(보류→권장→긴급)
 * - 경고등 OFF면 같은 종류 할 일을 해소
 * - 주행 가능 거리 < 30km 긴급, < 80km 권장, 그 이상이면 해소
 * - 누적 주행거리가 마지막 점검 + 주기 이상이면 정기 점검(보류)
 * - 같은 종류는 하나만 유지 (중복 생성 방지). 등급이 오르면 "나중에" 상태를 풀고 다시 알린다.
 *
 * 이 클래스는 할 일을 계획(경로)에 넣지 않는다. 계획 반영은 사용자가 [accept]한 뒤 병합 로직(T10)이 한다.
 * 스레드 안전하지 않음: 한 스레드(또는 직렬화된 코루틴)에서만 호출한다.
 */
class CarTaskClassifier(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val rangePolicy: RangePolicy = RangePolicy(),
    var inspectionPolicy: InspectionPolicy = InspectionPolicy(),
    private val escalationPolicy: EscalationPolicy = EscalationPolicy(),
    private val idFactory: () -> String = sequentialIds(),
) {
    private val active = LinkedHashMap<CarTaskKind, CarTask>()

    /** 해소되지 않은 할 일. 감지 순서. */
    val tasks: List<CarTask> get() = active.values.toList()

    fun find(id: String): CarTask? = active.values.firstOrNull { it.id == id }

    fun find(kind: CarTaskKind): CarTask? = active[kind]

    /** 모든 할 일을 지운다(데모 시나리오 초기화). 해소 이벤트는 내지 않는다. */
    fun clear() = active.clear()

    fun assess(signal: CarSignal): Assessment = when (signal) {
        is CarSignal.Telltale -> {
            val base = signal.type.baseSeverity
            when {
                base == null -> Assessment.Ignore
                signal.state == TelltaleState.OFF -> Assessment.Clear(CarTaskKind.Warning(signal.type))
                signal.state == TelltaleState.BLINK -> Assessment.Raise(CarTaskKind.Warning(signal.type), base.raised())
                else -> Assessment.Raise(CarTaskKind.Warning(signal.type), base)
            }
        }
        is CarSignal.RemainingRange -> when {
            signal.km < rangePolicy.urgentBelowKm ->
                Assessment.Raise(CarTaskKind.LowRange, Severity.URGENT, rangeKm = signal.km)
            signal.km < rangePolicy.recommendBelowKm ->
                Assessment.Raise(CarTaskKind.LowRange, Severity.RECOMMEND, rangeKm = signal.km)
            else -> Assessment.Clear(CarTaskKind.LowRange)
        }
        is CarSignal.Odometer ->
            if (inspectionPolicy.isDue(signal.km)) {
                Assessment.Raise(CarTaskKind.PeriodicInspection, Severity.HOLD, odometerKm = signal.km)
            } else {
                Assessment.Clear(CarTaskKind.PeriodicInspection)
            }
        is CarSignal.GearChanged -> Assessment.Ignore
    }

    fun onSignal(signal: CarSignal): CarTaskUpdate = when (val a = assess(signal)) {
        Assessment.Ignore -> CarTaskUpdate.None
        is Assessment.Clear -> active.remove(a.kind)
            ?.let { CarTaskUpdate.Resolved(it.copy(status = CarTaskStatus.RESOLVED)) }
            ?: CarTaskUpdate.None
        is Assessment.Raise -> raise(a)
    }

    private fun raise(a: Assessment.Raise): CarTaskUpdate {
        val existing = active[a.kind]
        if (existing == null) {
            val task = CarTask(
                id = idFactory(),
                kind = a.kind,
                severity = a.severity,
                detectedAt = clock.instant(),
                rangeKm = a.rangeKm,
                odometerKm = a.odometerKm,
            )
            active[a.kind] = task
            return CarTaskUpdate.Created(task)
        }
        val refreshed = existing.copy(rangeKm = a.rangeKm ?: existing.rangeKm)
        if (a.severity == existing.severity) {
            active[a.kind] = refreshed
            return CarTaskUpdate.None
        }
        val raisedUp = a.severity.ordinal < existing.severity.ordinal
        val status = if (raisedUp && existing.status == CarTaskStatus.SNOOZED) CarTaskStatus.OPEN else existing.status
        val after = refreshed.copy(severity = a.severity, status = status)
        active[a.kind] = after
        return CarTaskUpdate.SeverityChanged(existing, after)
    }

    /** 사용자가 "들를게"를 골랐다. 계획 병합(T10)은 ACCEPTED 할 일만 넣는다. */
    fun accept(id: String): CarTask? = update(id) { it.copy(status = CarTaskStatus.ACCEPTED) }

    /** 사용자가 "나중에"를 골랐다. 목록에는 남는다. */
    fun decline(id: String): CarTask? =
        update(id) { it.copy(status = CarTaskStatus.SNOOZED, declineCount = it.declineCount + 1) }

    /** 수락했던 할 일을 계획에서 뺐을 때 다시 열린 상태로 돌린다. */
    fun reopen(id: String): CarTask? = update(id) { it.copy(status = CarTaskStatus.OPEN) }

    /**
     * 권장 할 일 문구를 한 단계 강하게 할지. 거절 [EscalationPolicy.declines]회 이상 또는 감지 후
     * [EscalationPolicy.age] 이상 지났으면 true. 긴급은 이미 가장 강하고, 보류는 목록 유지만 하므로 false.
     */
    fun isEscalated(task: CarTask): Boolean {
        if (task.severity != Severity.RECOMMEND || !task.status.isActive) return false
        val age = Duration.between(task.detectedAt, clock.instant())
        return task.declineCount >= escalationPolicy.declines || age >= escalationPolicy.age
    }

    private inline fun update(id: String, change: (CarTask) -> CarTask): CarTask? {
        val current = find(id) ?: return null
        return change(current).also { active[current.kind] = it }
    }

    companion object {
        fun sequentialIds(prefix: String = "car"): () -> String {
            var n = 0
            return { "$prefix-${++n}" }
        }
    }
}
