package ai.deulleulcar.core.place

import ai.deulleulcar.core.model.HoursSource
import ai.deulleulcar.core.model.OpeningHours
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.TimeRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 장소의 영업시간을 알려 준다. 카카오 로컬에는 영업시간이 없어서 검색과 분리했다.
 * 모르면 null. [OpeningHours.source]로 확실한 정보인지(큐레이션) 추정인지(업종 기본값) 구분한다.
 */
fun interface HoursProvider {
    fun hoursFor(place: Place): OpeningHours?
}

/** [Place.openingHours]가 이미 있으면 그대로, 없으면 [provider]로 채운다. */
fun Place.withHoursFrom(provider: HoursProvider): Place =
    if (openingHours != null) this else copy(openingHours = provider.hoursFor(this))

/**
 * 업종별로 흔한 영업시간. 실제와 다를 수 있으므로 [HoursSource.CATEGORY_DEFAULT]로 표시하고,
 * 문구는 "보통 ~시까지예요"처럼 단정하지 않는다. [PlaceCategory.OTHER]는 null.
 */
object CategoryDefaultHours : HoursProvider {
    private val src = HoursSource.CATEGORY_DEFAULT
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private val weekdays = DayOfWeek.entries.filter { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY }

    private fun weekly(weekday: TimeRange?, saturday: TimeRange? = null, sunday: TimeRange? = null) = OpeningHours(
        buildMap {
            weekday?.let { r -> weekdays.forEach { put(it, listOf(r)) } }
            saturday?.let { put(DayOfWeek.SATURDAY, listOf(it)) }
            sunday?.let { put(DayOfWeek.SUNDAY, listOf(it)) }
        },
        src,
    )

    private val table: Map<PlaceCategory, OpeningHours> = mapOf(
        PlaceCategory.PHARMACY to weekly(TimeRange(t(9), t(19)), saturday = TimeRange(t(9), t(15))),
        PlaceCategory.MART to OpeningHours.everyDay(t(10), t(22), src),
        PlaceCategory.CONVENIENCE_STORE to OpeningHours.always(src),
        PlaceCategory.LAUNDRY to weekly(TimeRange(t(8), t(20)), saturday = TimeRange(t(9), t(18))),
        PlaceCategory.CAFE to OpeningHours.everyDay(t(8), t(22), src),
        PlaceCategory.BAKERY to OpeningHours.everyDay(t(8), t(22), src),
        PlaceCategory.BANK to weekly(TimeRange(t(9), t(16))),
        PlaceCategory.POST_OFFICE to weekly(TimeRange(t(9), t(18))),
        PlaceCategory.GAS_STATION to OpeningHours.everyDay(t(6), t(22), src),
        PlaceCategory.EV_CHARGING to OpeningHours.always(src),
        PlaceCategory.REPAIR_SHOP to weekly(TimeRange(t(9), t(18)), saturday = TimeRange(t(9), t(13))),
        PlaceCategory.TIRE_SHOP to weekly(TimeRange(t(9), t(19)), saturday = TimeRange(t(9), t(17))),
        PlaceCategory.CAR_WASH to OpeningHours.everyDay(t(8), t(20), src),
    )

    fun forCategory(category: PlaceCategory): OpeningHours? = table[category]

    override fun hoursFor(place: Place): OpeningHours? = forCategory(place.category)
}

/**
 * 직접 확인해서 모은 영업시간(JSON). 장소 id가 맞으면 우선, 없으면 상호명이 정확히 같은 항목을 쓴다.
 *
 * 형식:
 * ```json
 * { "entries": [ { "id": "demo-pharmacy-1", "name": "역삼 행복약국",
 *     "hours": { "weekdays": ["09:00-19:00"], "sat": ["09:00-14:00"] } } ] }
 * ```
 * hours 키: everyday, weekdays, weekend, mon, tue, wed, thu, fri, sat, sun. 뒤에 나온(더 좁은) 키가 덮어쓴다
 * (everyday → weekdays/weekend → 요일 순). 빈 배열은 휴무. 언급 없는 요일도 휴무. "18:00-02:00"처럼 닫는 시각이
 * 여는 시각보다 이르면 자정을 넘긴 영업, "00:00-24:00"은 24시간.
 */
class CuratedHoursProvider(entries: List<CuratedHoursEntry>) : HoursProvider {
    private val byId: Map<String, OpeningHours> = entries.filter { it.id != null }.associate { it.id!! to it.toHours() }
    private val byName: Map<String, OpeningHours> = entries.filter { it.name != null }.associate { it.name!! to it.toHours() }

    override fun hoursFor(place: Place): OpeningHours? = byId[place.id] ?: byName[place.name]

    companion object {
        const val DEMO_RESOURCE = "/demo/curated_hours.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): CuratedHoursProvider =
            CuratedHoursProvider(json.decodeFromString(CuratedHoursFile.serializer(), text).entries)

        /** 클래스패스 리소스에서 읽는다. 기본은 데모 구역 큐레이션. */
        fun fromResource(path: String = DEMO_RESOURCE): CuratedHoursProvider {
            val stream = CuratedHoursProvider::class.java.getResourceAsStream(path)
                ?: error("리소스 없음: $path")
            return fromJson(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }
}

@Serializable
internal data class CuratedHoursFile(val entries: List<CuratedHoursEntry> = emptyList())

@Serializable
data class CuratedHoursEntry(
    val id: String? = null,
    val name: String? = null,
    val hours: Map<String, List<String>>,
) {
    init {
        require(id != null || name != null) { "큐레이션 항목에는 id나 name이 필요합니다" }
    }

    fun toHours(): OpeningHours {
        val days = mutableMapOf<DayOfWeek, List<TimeRange>>()
        for (key in KEY_ORDER) {
            val ranges = hours[key] ?: continue
            val parsed = ranges.map(::parseRange)
            for (day in daysOf(key)) days[day] = parsed
        }
        val unknown = hours.keys - KEY_ORDER.toSet()
        require(unknown.isEmpty()) { "알 수 없는 hours 키: $unknown" }
        return OpeningHours(days.filterValues { it.isNotEmpty() }, HoursSource.CURATED)
    }

    private companion object {
        val KEY_ORDER = listOf("everyday", "weekdays", "weekend", "mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val DAY_KEYS = mapOf(
            "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY,
        )

        fun daysOf(key: String): List<DayOfWeek> = when (key) {
            "everyday" -> DayOfWeek.entries
            "weekdays" -> DayOfWeek.entries.take(5)
            "weekend" -> listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            else -> listOf(DAY_KEYS.getValue(key))
        }

        fun parseRange(text: String): TimeRange {
            val parts = text.split("-").map { it.trim() }
            require(parts.size == 2) { "영업시간 형식은 HH:mm-HH:mm: $text" }
            return TimeRange(parseTime(parts[0]), parseTime(parts[1]))
        }

        fun parseTime(text: String): LocalTime = if (text == "24:00") LocalTime.MIDNIGHT else LocalTime.parse(text)
    }
}

/** 큐레이션을 먼저 보고, 없으면 업종 기본값. */
class CompositeHoursProvider(private val providers: List<HoursProvider>) : HoursProvider {
    constructor(vararg providers: HoursProvider) : this(providers.toList())

    override fun hoursFor(place: Place): OpeningHours? = providers.firstNotNullOfOrNull { it.hoursFor(place) }

    companion object {
        /** 데모 구역 큐레이션 JSON → 업종 기본값. */
        fun demoDefault(): CompositeHoursProvider =
            CompositeHoursProvider(CuratedHoursProvider.fromResource(), CategoryDefaultHours)
    }
}
