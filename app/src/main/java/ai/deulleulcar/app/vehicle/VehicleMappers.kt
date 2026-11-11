package ai.deulleulcar.app.vehicle

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.core.model.Gear
import ai.deulleulcar.core.model.TelltaleState
import ai.deulleulcar.core.model.TelltaleType
import ai.pleos.playground.vehicle.constant.ErrorCode
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState as SdkTelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType as SdkTelltaleType

/** Pleos Vehicle SDK 값 → core 모델 변환. */
internal object VehicleMappers {
    /** docs/telltale-severity.md "무시" 목록. 그 밖에 core에 없는 SDK 값은 [TelltaleType.UNKNOWN]. */
    private val statusIndicators = setOf(
        "PARKING_BRAKE", "SEAT_BELT", "DOOR_OPEN", "ESC_OFF", "AUTO_HOLD", "AUTO_HIGH_BEAM",
        "HEADLAMP_LOW", "HEADLAMP_HIGH", "POSITION_LAMP", "FRONT_FOG_LAMP", "REAR_FOG_LAMP",
        "PASSENGER_AIRBAG_OFF", "PASSENGER_AIRBAG_ON", "TURN_SIGNAL_LEFT", "TURN_SIGNAL_RIGHT",
        "PREHEAT", "IMMOBILIZER",
    )

    private val coreTypes = TelltaleType.entries
        .filter { it != TelltaleType.STATUS_INDICATOR && it != TelltaleType.UNKNOWN }
        .associateBy { it.name }

    fun telltaleType(sdk: SdkTelltaleType): TelltaleType = coreTypes[sdk.name]
        ?: if (sdk.name in statusIndicators) TelltaleType.STATUS_INDICATOR else TelltaleType.UNKNOWN

    fun telltaleState(sdk: SdkTelltaleState): TelltaleState = when (sdk) {
        SdkTelltaleState.OFF -> TelltaleState.OFF
        SdkTelltaleState.ON -> TelltaleState.ON
        SdkTelltaleState.BLINK -> TelltaleState.BLINK
    }

    /** P=정차, R, N, 그 외 전진 기어(D, 1~9단)=D. 모르는 값은 null. */
    fun gear(sdk: VehicleGear?): Gear? = when (sdk) {
        null, VehicleGear.GEAR_UNKNOWN -> null
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        else -> Gear.D
    }

    /**
     * SDK 콜백 예외에서 오류 코드를 꺼낸다.
     * 2.3.2의 예외 클래스는 난독화되어 있지만 `message`가 [ErrorCode] 이름이다(바이트코드로 확인).
     */
    fun errorCode(e: Throwable): ErrorCode = when {
        e is SecurityException -> ErrorCode.ERROR_SECURITY_PERMISSION
        else -> ErrorCode.entries.firstOrNull { it.name == e.message } ?: ErrorCode.ERROR_UNDEFINED
    }

    fun featureState(code: ErrorCode): FeatureState = when (code) {
        ErrorCode.ERROR_NOT_SUPPORTED_PROPERTY,
        ErrorCode.ERROR_NOT_SUPPORTED_VALUE_TYPE,
        ErrorCode.ERROR_NOT_SUPPORTED_AREA,
        -> FeatureState.NOT_SUPPORTED
        ErrorCode.ERROR_SECURITY_PERMISSION -> FeatureState.PERMISSION_DENIED
        else -> FeatureState.FAILED
    }
}
