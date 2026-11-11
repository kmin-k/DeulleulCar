package ai.deulleulcar.core.route

import ai.deulleulcar.core.model.Stop
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * 볼일 하나. 후보 장소가 여러 개일 수 있고, 최적화기가 그중 한 곳을 고른다.
 * @property id 볼일 식별자. 계획 안에서 유일해야 한다.
 */
data class StopRequest(val id: String, val candidates: List<Stop>) {
    companion object {
        /** 장소가 이미 정해진 볼일. id는 장소 id. */
        fun of(stop: Stop, id: String = stop.place.id) = StopRequest(id, listOf(stop))
    }
}

/**
 * @property pinnedFirstRequestId 이 볼일을 반드시 첫 정류지로 둔다(긴급 차의 할 일). 들를 수 없으면 제외된다.
 */
data class RouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val requests: List<StopRequest>,
    val departAt: LocalDateTime,
    val pinnedFirstRequestId: String? = null,
)

/**
 * @property maxStops 완전 탐색 정류지 상한. 넘는 볼일은 [ExclusionReason.TOO_MANY_STOPS]로 제외.
 * @property maxCandidatesPerRequest 볼일마다 검토할 후보 수 상한(조합 폭발 방지). 직행 경로 기준 우회가 작은 순.
 * @property closingMargin 영업 종료 이만큼 전까지는 도착해야 들를 수 있다고 본다. 기본 0(도착 시각에 영업 중이면 됨).
 */
data class RouteOptions(
    val maxStops: Int = 5,
    val maxCandidatesPerRequest: Int = 3,
    val closingMargin: Duration = Duration.ZERO,
) {
    init {
        require(maxStops in 1..MAX_SEARCH_STOPS) { "정류지 상한은 1..$MAX_SEARCH_STOPS" }
        require(maxCandidatesPerRequest >= 1)
        require(!closingMargin.isNegative)
    }

    companion object {
        const val MAX_SEARCH_STOPS = 6
    }
}

/**
 * 계획에 들어간 정류지 하나.
 * @property closesAt 도착 시점 영업 구간이 끝나는 시각. 영업시간 정보가 없으면 null.
 */
data class PlannedStop(
    val requestId: String,
    val stop: Stop,
    val arrival: LocalDateTime,
    val departure: LocalDateTime,
    val closesAt: LocalDateTime?,
)

/** 영업시간 때문에 "그냥 가까운 순서"와 다르게 편성한 이유. TTS: "약국이 7시에 닫아서 먼저 갈게요". */
sealed interface ReorderReason {
    val stop: Stop

    /** 일찍 닫아서 앞으로 당겼다. */
    data class ClosesSoon(val placeId: String, val closingTime: LocalDateTime, override val stop: Stop) : ReorderReason

    /** 아직 열지 않아서 뒤로 미뤘다. */
    data class OpensLater(val placeId: String, override val stop: Stop) : ReorderReason
}

enum class ExclusionReason {
    /** 후보 장소가 없다. */
    NO_CANDIDATES,

    /** 혼자 들러도 도착할 때 영업 중인 후보가 없다(영업 종료·휴무). */
    CLOSED,

    /** 혼자라면 들를 수 있지만 다른 볼일과 함께 영업시간을 맞출 수 없다. */
    CONFLICT,

    /** [RouteOptions.maxStops]를 넘었다. */
    TOO_MANY_STOPS,
}

data class ExcludedRequest(val request: StopRequest, val reason: ExclusionReason)

/**
 * 최적화 결과.
 * @property driveTime 정류지를 거치는 총 운전 시간
 * @property dwellTime 정류지 체류 시간 합
 * @property directDriveTime 출발지 → 목적지 직행 운전 시간(기준 경로)
 */
data class RoutePlan(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val departAt: LocalDateTime,
    val stops: List<PlannedStop>,
    val arrivalAtDestination: LocalDateTime,
    val driveTime: Duration,
    val dwellTime: Duration,
    val directDriveTime: Duration,
    val reorderReasons: List<ReorderReason> = emptyList(),
    val excluded: List<ExcludedRequest> = emptyList(),
) {
    val totalTime: Duration get() = driveTime.plus(dwellTime)

    val orderedStops: List<Stop> get() = stops.map { it.stop }

    /** 직행 대비 늘어난 운전 시간(분, 반올림). 내비 화면·TTS의 "경로상 +N분"은 이 값을 쓴다. */
    val extraDriveMinutes: Int get() = roundMinutes(driveTime.minus(directDriveTime))

    /** 직행 대비 늘어난 총 소요(운전 + 체류, 분, 반올림). "도착이 N분 늦어져요"에 쓴다. */
    val extraTotalMinutes: Int get() = roundMinutes(totalTime.minus(directDriveTime))

    companion object {
        fun roundMinutes(d: Duration): Int = (d.seconds / 60.0).roundToInt().coerceAtLeast(0)
    }
}
