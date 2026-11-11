package ai.deulleulcar.core.model

/**
 * Pleos Virtual Console Safety → Telltales 목록 기준 경고등 종류 (docs/telltale-severity.md).
 * 경고등이 아닌 Safety 레벨 신호(브레이크액·엔진오일·워셔액)도 같은 방식(ON = 부족, OFF = 정상)으로 여기에 싣는다.
 * [baseSeverity]가 null이면 상태 표시등이라 차의 할 일을 만들지 않는다.
 * Vehicle 어댑터(T11)는 SDK 값을 이 enum으로 바꾸고, 모르는 값은 [UNKNOWN]으로 보낸다.
 */
enum class TelltaleType(val baseSeverity: Severity?) {
    // 긴급
    ENGINE_OIL_PRESSURE(Severity.URGENT),
    ENGINE_TEMPERATURE(Severity.URGENT),
    EV_SYSTEM(Severity.URGENT),
    POWER_DOWN(Severity.URGENT),
    MDPS(Severity.URGENT),
    H2_LEAK(Severity.URGENT),

    /** 브레이크액 부족. 경고등이 아니라 Safety `registerBrakeFluidLevelState`(LOW/CRITICALLY_LOW)에서 온다. */
    BRAKE_FLUID_LOW(Severity.URGENT),

    // 권장
    TPMS(Severity.RECOMMEND),
    ENGINE(Severity.RECOMMEND),
    ABS(Severity.RECOMMEND),
    ESC(Severity.RECOMMEND),
    AIRBAG(Severity.RECOMMEND),
    LOW_VOLT_BATTERY(Severity.RECOMMEND),
    ELECTRONIC_PARKING_BRAKE(Severity.RECOMMEND),
    RBS(Severity.RECOMMEND),
    PARTICLE_FILTER(Severity.RECOMMEND),
    H2_SENSOR(Severity.RECOMMEND),
    MASTER_WARNING(Severity.RECOMMEND),
    LOW_FUEL(Severity.RECOMMEND),
    LOW_EV_BATTERY(Severity.RECOMMEND),

    /** 엔진오일 부족. Safety `registerEngineOilLevelState`(LOW/CRITICALLY_LOW)에서 온다. */
    ENGINE_OIL_LOW(Severity.RECOMMEND),

    // 보류
    AFS(Severity.HOLD),
    LED_HEADLAMP(Severity.HOLD),
    EMERGENCY_CALL(Severity.HOLD),

    /** 워셔액 부족. Safety `registerLowWasherFluidWaringState`(true/false)에서 온다. */
    WASHER_FLUID_LOW(Severity.HOLD),

    /** 상태 표시등(방향지시등, 안전벨트, 도어, 전조등 등). 할 일 아님. */
    STATUS_INDICATOR(null),

    /** 매핑되지 않은 값. 근거 없이 할 일을 만들지 않는다. */
    UNKNOWN(null),
    ;

    val isActionable: Boolean get() = baseSeverity != null
}

enum class TelltaleState { OFF, ON, BLINK }

enum class Gear { P, R, N, D }

/** 차량에서 들어오는 신호. Vehicle 어댑터가 SDK 콜백을 이 형태로 바꿔 [ai.deulleulcar.core.cartask.CarTaskClassifier]에 넣는다. */
sealed interface CarSignal {
    data class Telltale(val type: TelltaleType, val state: TelltaleState) : CarSignal

    /** Energy 주행 가능 거리. 연료·배터리 구분 없이 km. */
    data class RemainingRange(val km: Double) : CarSignal

    data class GearChanged(val gear: Gear) : CarSignal

    /** 누적 주행거리(km). 정기 점검 판정에 쓴다. */
    data class Odometer(val km: Double) : CarSignal
}

/** 주행 상태. 기어 P = 정차, 그 외 = 주행 (spec 결정사항). */
enum class DriveState {
    PARKED, DRIVING;

    companion object {
        fun from(gear: Gear): DriveState = if (gear == Gear.P) PARKED else DRIVING
    }
}
