package ai.deulleulcar.core.parser

import ai.deulleulcar.core.model.Errand
import ai.deulleulcar.core.model.PlaceCategory

/**
 * 규칙 기반 볼일 파서. API 키가 없거나 LLM 호출이 실패해도 데모가 돌아가게 하는 fallback.
 *
 * 방식:
 * 1. 문장부호를 공백으로 바꾼 뒤, 업종 동의어 사전을 긴 것부터 문장 안에서 찾는다(조사·접속어는 자연히 무시됨).
 * 2. 업종 이름 바로 앞에 붙은 말이 있으면 상호로 본다 ("햇살약국" → placeName). 접속어·수식어는 제외.
 * 3. 사전에 없는 말이 "들러/들렀다" 바로 앞에 오면 [PlaceCategory.OTHER] 볼일로 남긴다 ("꽃집 들러").
 * 4. 같은 (업종, 상호)는 한 번만, 말한 순서대로.
 * 5. 목적지: "집/우리집", "회사/사무실/직장" 단어 > "들렀다 ○○" / "○○ 가는 길" > 퇴근길·출근길 암시.
 */
class RuleBasedErrandParser : ErrandParser {

    private data class Synonym(val text: String, val category: PlaceCategory, val nameable: Boolean)

    private val synonyms: List<Synonym> = buildList {
        fun add(category: PlaceCategory, nameable: List<String>, phrases: List<String> = emptyList()) {
            nameable.forEach { add(Synonym(it, category, nameable = true)) }
            phrases.forEach { add(Synonym(it, category, nameable = false)) }
        }
        add(PlaceCategory.PHARMACY, listOf("약국", "약방"), listOf("약 사", "약 받"))
        add(PlaceCategory.MART, listOf("마트", "슈퍼마켓", "슈퍼", "생활용품점", "식자재", "할인점"), listOf("장 보", "장보"))
        add(PlaceCategory.CONVENIENCE_STORE, listOf("편의점"))
        add(PlaceCategory.LAUNDRY, listOf("세탁소", "빨래방", "코인세탁"), listOf("세탁물", "드라이클리닝"))
        add(PlaceCategory.CAFE, listOf("카페", "커피숍", "커피전문점"), listOf("커피"))
        add(PlaceCategory.BAKERY, listOf("빵집", "베이커리", "제과점"), listOf("빵 사"))
        add(PlaceCategory.BANK, listOf("은행", "ATM", "atm", "현금인출기"), listOf("현금 뽑", "현금 인출", "돈 뽑"))
        add(PlaceCategory.POST_OFFICE, listOf("우체국"), listOf("택배 부치", "택배 보내", "등기"))
        add(PlaceCategory.GAS_STATION, listOf("주유소"), listOf("기름 넣", "주유", "기름"))
        add(PlaceCategory.EV_CHARGING, listOf("충전소"), listOf("충전하", "전기 충전"))
        add(PlaceCategory.REPAIR_SHOP, listOf("정비소", "카센터", "서비스센터"))
        add(PlaceCategory.TIRE_SHOP, listOf("타이어 가게", "타이어가게", "타이어 전문점", "타이어전문점", "타이어샵"))
        add(PlaceCategory.CAR_WASH, listOf("세차장"), listOf("세차"))
    }.sortedByDescending { it.text.length }

    /** 업종 이름 앞에 붙어도 상호가 아닌 말. */
    private val nonNamePrefixes = setOf(
        "이랑", "랑", "하고", "와", "과", "이나", "나", "및", "그리고", "또", "근처", "가까운", "동네", "큰", "작은",
        "대형", "24시", "근처에", "앞", "옆", "전기차", "셀프", "주변",
    )

    /** 단어 끝에 붙는 조사·어미 (긴 것부터 떼어 낸다). */
    private val particles = listOf(
        "으로", "에서", "까지", "이랑", "하고", "에다", "에", "로", "랑", "와", "과", "도", "만", "은", "는", "을", "를",
    )

    private val stopWords = setOf("좀", "잠깐", "잠시", "꼭", "먼저", "한번", "같이", "거기", "여기", "또", "빨리", "그리고", "다", "모두", "전부")

    private val visitVerb = Regex("^들(러|렀|르|를|려|렸|른)")

    private val homeWords = listOf("우리집", "집")
    private val workWords = listOf("회사", "사무실", "직장")
    private val goVerb =
        Regex("^(가|가자|가줘|갈래|갈게|가야|가야지|가야돼|가요|갑시다|가려고|가는|간다|가고|가면서|가다가)$")

    override fun parse(utterance: String): ParsedRequest {
        val text = normalize(utterance)
        if (text.isEmpty()) return ParsedRequest(emptyList())

        val tokens = text.split(' ')
        // 원문 위치 기준으로 합쳐 말한 순서를 지킨다
        val ordered = (findKnownErrands(text) + findUnknownErrands(tokens))
            .sortedBy { it.second }
            .map { it.first }
            .distinctBy { Triple(it.category, it.placeName, if (it.category == PlaceCategory.OTHER) it.keyword else null) }

        val timeHint = timeHint(text)
        val destination = explicitDestination(tokens)
            ?: namedDestination(tokens)
            ?: when (timeHint) {
                TimeHint.COMMUTE_HOME -> DestinationHint.Home
                TimeHint.COMMUTE_WORK -> DestinationHint.Work
                else -> null
            }
        return ParsedRequest(ordered, destination, timeHint)
    }

    private fun normalize(s: String): String =
        s.replace(Regex("[,.!?~·/、，。]"), " ").replace(Regex("\\s+"), " ").trim()

    /** (볼일, 원문 위치) */
    private fun findKnownErrands(text: String): List<Pair<Errand, Int>> {
        val result = mutableListOf<Pair<Errand, Int>>()
        var i = 0
        var lastEnd = 0
        while (i < text.length) {
            val syn = synonyms.firstOrNull { text.startsWith(it.text, i) && !isFalsePositive(text, i, it) }
            if (syn == null) {
                i++
                continue
            }
            val wordStart = maxOf(text.lastIndexOf(' ', i - 1) + 1, lastEnd)
            val prefix = text.substring(wordStart, i)
            val placeName = if (syn.nameable && prefix.isNotEmpty() && prefix !in nonNamePrefixes &&
                synonyms.none { prefix.contains(it.text) }
            ) {
                prefix + syn.text
            } else {
                null
            }
            result += Errand(keyword = placeName ?: syn.text, category = syn.category, placeName = placeName) to wordStart
            i += syn.text.length
            lastEnd = i
        }
        return result
    }

    /** "스마트폰"의 "마트"처럼 단어 중간에 우연히 들어간 경우. */
    private fun isFalsePositive(text: String, at: Int, syn: Synonym): Boolean {
        if (syn.text == "마트" && at > 0 && text[at - 1] == '스') return true
        if (syn.text == "기름" && text.startsWith("기름진", at)) return true
        return false
    }

    /** 사전에 없는 말이 "들러" 바로 앞에 오면 기타 볼일. (볼일, 원문 위치) */
    private fun findUnknownErrands(tokens: List<String>): List<Pair<Errand, Int>> {
        val result = mutableListOf<Pair<Errand, Int>>()
        var offset = 0
        val offsets = tokens.map { t -> offset.also { offset += t.length + 1 } }
        for (i in tokens.indices) {
            if (!visitVerb.containsMatchIn(tokens[i])) continue
            var j = i - 1
            while (j >= 0 && tokens[j] in stopWords) j--
            if (j < 0) continue
            val word = stripParticles(tokens[j])
            if (word.isEmpty() || synonyms.any { tokens[j].contains(it.text) } || isHome(word) || isWork(word) ||
                visitVerb.containsMatchIn(tokens[j])
            ) {
                continue
            }
            result += Errand(keyword = word, category = PlaceCategory.OTHER) to offsets[j]
        }
        return result
    }

    private fun stripParticles(token: String): String {
        for (p in particles) {
            if (token.length > p.length && token.endsWith(p)) return token.dropLast(p.length)
        }
        return token
    }

    private fun isHome(word: String) = word in homeWords
    private fun isWork(word: String) = word in workWords

    private fun timeHint(text: String): TimeHint? = when {
        text.contains("퇴근") -> TimeHint.COMMUTE_HOME
        text.contains("출근") -> TimeHint.COMMUTE_WORK
        text.contains("가는 길") || text.contains("가는길") || text.contains("가다가") -> TimeHint.ON_THE_WAY
        else -> null
    }

    /** "집에", "회사로" 같은 단어. "집에서"(출발지)는 제외. 마지막에 나온 것을 목적지로 본다. */
    private fun explicitDestination(tokens: List<String>): DestinationHint? {
        var found: DestinationHint? = null
        for (t in tokens) {
            if (t.endsWith("에서")) continue
            val word = stripParticles(t).removeSuffix("에")
            when {
                isHome(word) || t == "귀가" -> found = DestinationHint.Home
                isWork(word) -> found = DestinationHint.Work
            }
        }
        return found
    }

    /** "들렀다 ○○(으로) 가자" 또는 "○○ 가는 길에"의 ○○. */
    private fun namedDestination(tokens: List<String>): DestinationHint? {
        for (i in tokens.indices) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            // "○○ 가는 길" / "○○에 가자"
            if (next != null && goVerb.matches(next)) {
                val name = stripParticles(t)
                if (isCandidateName(name, t)) return DestinationHint.Named(name)
            }
            // "들렀다 ○○" (문장 끝)
            if (visitVerb.containsMatchIn(t) && next != null && i + 1 == tokens.lastIndex) {
                val name = stripParticles(next)
                if (isCandidateName(name, next)) return DestinationHint.Named(name)
            }
        }
        return null
    }

    private fun isCandidateName(name: String, raw: String): Boolean =
        name.length >= 2 && name !in stopWords && !visitVerb.containsMatchIn(raw) &&
            synonyms.none { raw.contains(it.text) } && !goVerb.matches(raw) &&
            raw != "길에" && !raw.startsWith("퇴근") && !raw.startsWith("출근")
}
