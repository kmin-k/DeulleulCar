package ai.deulleulcar.core.model

import java.time.Instant

/** 차의 할 일 등급. 선언 순서가 강도 순서(강한 것부터). */
enum class Severity {
    URGENT, RECOMMEND, HOLD;

    /** 한 단계 강하게. 긴급은 그대로. */
    fun raised(): Severity = when (this) {
        URGENT, RECOMMEND -> URGENT
        HOLD -> RECOMMEND
    }
}

/** 차의 할 일 종류. 같은 종류는 동시에 하나만 열려 있다(중복 생성 방지 키). */
sealed interface CarTaskKind {
    data class Warning(val telltale: TelltaleType) : CarTaskKind {
        init {
            require(telltale.isActionable) { "할 일이 아닌 경고등: $telltale" }
        }
    }

    /** 주행 가능 거리 부족 (연료·배터리). */
    data object LowRange : CarTaskKind

    /** 누적 주행거리 기반 정기 점검. */
    data object PeriodicInspection : CarTaskKind
}

enum class CarTaskStatus {
    /** 감지됨, 아직 사용자 응답 없음. */
    OPEN,

    /** 사용자가 허락해서 계획에 넣기로 함. */
    ACCEPTED,

    /** 원인이 사라짐(경고등 OFF, 주유 등). */
    RESOLVED,

    /** 사용자가 "나중에"를 고름. 목록에는 남는다. */
    SNOOZED;

    val isActive: Boolean get() = this != RESOLVED
}

/**
 * 차의 할 일 하나.
 * @property detectedAt 처음 감지한 시각. 등급이 바뀌어도 유지한다.
 * @property declineCount "나중에"를 고른 횟수.
 * @property rangeKm [CarTaskKind.LowRange]일 때 마지막으로 받은 주행 가능 거리.
 * @property odometerKm [CarTaskKind.PeriodicInspection]일 때 감지 시점 누적 주행거리.
 */
data class CarTask(
    val id: String,
    val kind: CarTaskKind,
    val severity: Severity,
    val detectedAt: Instant,
    val status: CarTaskStatus = CarTaskStatus.OPEN,
    val declineCount: Int = 0,
    val rangeKm: Double? = null,
    val odometerKm: Double? = null,
)
