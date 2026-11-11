package ai.deulleulcar.core.model

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 장소 업종. 사람의 볼일(약국, 마트…)과 차의 할 일 해결 장소(주유소, 정비소…)가 같은 분류를 쓴다.
 * 장소 검색(T7)은 이 값을 검색 키워드·카테고리 코드로 바꿔서 사용한다.
 */
enum class PlaceCategory(val label: String) {
    PHARMACY("약국"),
    MART("마트"),
    CONVENIENCE_STORE("편의점"),
    LAUNDRY("세탁소"),
    CAFE("카페"),
    BAKERY("빵집"),
    BANK("은행"),
    POST_OFFICE("우체국"),
    GAS_STATION("주유소"),
    EV_CHARGING("충전소"),
    REPAIR_SHOP("정비소"),
    TIRE_SHOP("타이어 전문점"),
    CAR_WASH("세차장"),
    OTHER("장소"),
}

/** 장소 검색 결과 한 건. 좌표는 WGS84 위경도. */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val category: PlaceCategory,
    val address: String? = null,
    val openingHours: OpeningHours? = null,
) {
    /** 좌표(이름 포함). */
    val point: GeoPoint get() = GeoPoint(lat, lon, name)
}

/** 하루 안의 영업 구간. [close]가 [open]보다 이르거나 같으면 자정을 넘겨 다음 날 [close]까지 영업한다. */
data class TimeRange(val open: LocalTime, val close: LocalTime) {
    val crossesMidnight: Boolean get() = !close.isAfter(open)
}

/**
 * 주간 영업시간.
 * @property source 데이터 출처. [HoursSource.CATEGORY_DEFAULT]이면 문구에서 단정하지 않는다("보통 ~시까지예요").
 */
data class OpeningHours(
    val weekly: Map<DayOfWeek, List<TimeRange>>,
    val source: HoursSource = HoursSource.CATEGORY_DEFAULT,
) {
    fun isOpenAt(at: LocalDateTime): Boolean = closingTimeAt(at) != null

    /** [at] 시점에 영업 중이면 그 구간이 끝나는 시각, 아니면 null. */
    fun closingTimeAt(at: LocalDateTime): LocalDateTime? {
        val today = at.toLocalDate()
        val time = at.toLocalTime()
        for (range in weekly[today.dayOfWeek].orEmpty()) {
            if (range.crossesMidnight) {
                if (!time.isBefore(range.open)) return today.plusDays(1).atTime(range.close)
            } else if (!time.isBefore(range.open) && time.isBefore(range.close)) {
                return today.atTime(range.close)
            }
        }
        // 전날 시작해서 자정을 넘긴 구간
        val yesterday = today.minusDays(1)
        for (range in weekly[yesterday.dayOfWeek].orEmpty()) {
            if (range.crossesMidnight && time.isBefore(range.close)) return today.atTime(range.close)
        }
        return null
    }

    companion object {
        /** 매일 같은 시간에 여는 영업시간. */
        fun everyDay(open: LocalTime, close: LocalTime, source: HoursSource = HoursSource.CATEGORY_DEFAULT) =
            OpeningHours(DayOfWeek.entries.associateWith { listOf(TimeRange(open, close)) }, source)

        /** 24시간 영업. */
        fun always(source: HoursSource = HoursSource.CATEGORY_DEFAULT) =
            everyDay(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, source)
    }
}

enum class HoursSource { CATEGORY_DEFAULT, CURATED }
