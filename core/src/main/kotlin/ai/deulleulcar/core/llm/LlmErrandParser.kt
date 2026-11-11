package ai.deulleulcar.core.llm

import ai.deulleulcar.core.model.Errand
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.parser.DestinationHint
import ai.deulleulcar.core.parser.ErrandParseException
import ai.deulleulcar.core.parser.ParsedRequest
import ai.deulleulcar.core.parser.SuspendErrandParser
import ai.deulleulcar.core.parser.TimeHint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * [LlmClient]로 발화를 구조화한다. 발화 텍스트만 보낸다 (위치·주소·개인정보 없음).
 *
 * 응답 검증:
 * - JSON이 스키마와 다르면 [ErrandParseException]
 * - 알 수 없는 업종 코드는 [PlaceCategory.OTHER]로 낮춘다 (keyword는 장소 검색에 그대로 쓸 수 있음)
 * - 빈 keyword는 버리고, 같은 (업종, 상호)는 한 번만, 최대 [ErrandPrompt.MAX_ERRANDS]개
 * - 볼일이 하나도 없으면 [ErrandParseException] (규칙 기반이 더 잘 찾을 수 있으므로)
 * - 알 수 없는 목적지·시간 힌트는 null
 *
 * LLM 호출 실패는 [LlmException]으로 그대로 전파한다.
 */
class LlmErrandParser(private val client: LlmClient) : SuspendErrandParser {

    val isConfigured: Boolean get() = client.isConfigured

    override suspend fun parse(utterance: String): ParsedRequest {
        val text = utterance.trim()
        if (text.isEmpty()) throw ErrandParseException("빈 발화")
        val raw = client.generateJson(
            JsonGenerationRequest(ErrandPrompt.systemInstruction, text, ErrandPrompt.responseSchema),
        )
        return toParsedRequest(decode(raw))
    }

    private fun decode(raw: String): Dto {
        val body = raw.trim().removeSurrounding("```json", "```").removeSurrounding("```").trim()
        return try {
            json.decodeFromString(Dto.serializer(), body)
        } catch (e: SerializationException) {
            throw ErrandParseException("LLM 응답이 스키마와 다름", e)
        } catch (e: IllegalArgumentException) {
            throw ErrandParseException("LLM 응답이 스키마와 다름", e)
        }
    }

    internal fun toParsedRequest(dto: Dto): ParsedRequest {
        val errands = dto.errands.orEmpty()
            .mapNotNull { it.toErrand() }
            .distinctBy { Triple(it.category, it.placeName, if (it.category == PlaceCategory.OTHER) it.keyword else null) }
            .take(ErrandPrompt.MAX_ERRANDS)
        if (errands.isEmpty()) throw ErrandParseException("LLM이 볼일을 찾지 못함", noErrands = true)

        val destination = dto.destination?.let { d ->
            when (d.type?.trim()?.uppercase()) {
                "HOME" -> DestinationHint.Home
                "WORK" -> DestinationHint.Work
                "NAMED" -> d.name.clean()?.let { DestinationHint.Named(it) }
                else -> null
            }
        }
        val timeHint = dto.timeHint?.trim()?.uppercase()?.let { name -> TimeHint.entries.firstOrNull { it.name == name } }
        return ParsedRequest(errands, destination, timeHint)
    }

    private fun ErrandDto.toErrand(): Errand? {
        val kw = keyword.clean() ?: return null
        val cat = category?.trim()?.uppercase()
            ?.let { name -> PlaceCategory.entries.firstOrNull { it.name == name } }
            ?: PlaceCategory.OTHER
        return Errand(keyword = kw, category = cat, placeName = placeName.clean())
    }

    private fun String?.clean(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }?.take(MAX_TEXT)

    @Serializable
    internal data class Dto(
        val errands: List<ErrandDto>? = null,
        val destination: DestinationDto? = null,
        val timeHint: String? = null,
    )

    @Serializable
    internal data class ErrandDto(
        val keyword: String? = null,
        val category: String? = null,
        val placeName: String? = null,
    )

    @Serializable
    internal data class DestinationDto(
        @SerialName("type") val type: String? = null,
        val name: String? = null,
    )

    private companion object {
        const val MAX_TEXT = 40
        val json = Json { ignoreUnknownKeys = true }
    }
}
