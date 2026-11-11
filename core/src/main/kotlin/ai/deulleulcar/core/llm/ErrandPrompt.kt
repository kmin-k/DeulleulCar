package ai.deulleulcar.core.llm

import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.parser.TimeHint
import kotlinx.serialization.json.JsonObject

/** 볼일 발화 구조화 프롬프트와 응답 스키마. 업종·시간 힌트 목록은 enum에서 만든다. */
internal object ErrandPrompt {

    const val DESTINATION_NONE = "NONE"
    const val TIME_HINT_NONE = "NONE"
    val destinationTypes = listOf("HOME", "WORK", "NAMED", DESTINATION_NONE)

    val categoryNames: List<String> = PlaceCategory.entries.map { it.name }
    val timeHintNames: List<String> = TimeHint.entries.map { it.name } + TIME_HINT_NONE

    val systemInstruction: String = buildString {
        appendLine("너는 운전 중인 한국어 사용자의 음성 발화를 구조화하는 파서다. 발화는 음성 인식 결과라 띄어쓰기·맞춤법이 틀릴 수 있다.")
        appendLine("발화에서 들를 곳(볼일)과 최종 목적지, 시간 힌트를 뽑아 지정된 JSON 스키마로만 답한다.")
        appendLine()
        appendLine("errands: 들를 곳 목록. 말한 순서를 지키고 같은 곳은 한 번만 넣는다. 최종 목적지(집·회사 등)는 넣지 않는다.")
        appendLine("- keyword: 발화에 나온 원문 표현 (예: \"약국\", \"슈퍼\", \"햇살약국\", \"꽃집\").")
        appendLine("- category: 아래 업종 코드 중 하나. 맞는 것이 없으면 OTHER.")
        PlaceCategory.entries.forEach { appendLine("  - ${it.name}: ${it.label}") }
        appendLine("  예: \"약 사야 돼\"→PHARMACY, \"장 보고\"→MART, \"기름 넣고\"→GAS_STATION, \"택배 부치고\"→POST_OFFICE, \"현금 뽑고\"→BANK.")
        appendLine("- placeName: 사용자가 특정 상호를 말했을 때만 그 상호 (예: \"햇살약국\"). 업종만 말했으면 null.")
        appendLine()
        appendLine("destination: 최종 목적지.")
        appendLine("- type: HOME(집·우리집·귀가), WORK(회사·사무실·직장), NAMED(그 밖의 이름 있는 목적지), NONE(말하지 않음).")
        appendLine("- name: NAMED일 때 목적지 이름, 그 밖에는 null.")
        appendLine("- 목적지를 직접 말하지 않았어도 \"퇴근길\"이면 HOME, \"출근길\"이면 WORK.")
        appendLine()
        appendLine("timeHint: COMMUTE_HOME(퇴근길·퇴근하면서), COMMUTE_WORK(출근길), ON_THE_WAY(가는 길에·가다가), NONE.")
        appendLine()
        appendLine("발화에 없는 내용은 지어내지 않는다. 볼일이 없으면 errands는 빈 배열.")
    }

    val responseSchema: JsonObject = Schema.obj(
        "errands" to Schema.array(
            Schema.obj(
                "keyword" to Schema.string("발화 원문 표현"),
                "category" to Schema.enum(categoryNames, "업종 코드"),
                "placeName" to Schema.nullableString("특정 상호. 없으면 null"),
            ),
            maxItems = MAX_ERRANDS,
        ),
        "destination" to Schema.obj(
            "type" to Schema.enum(destinationTypes),
            "name" to Schema.nullableString("NAMED일 때 목적지 이름"),
        ),
        "timeHint" to Schema.enum(timeHintNames),
    )

    const val MAX_ERRANDS = 8
}
