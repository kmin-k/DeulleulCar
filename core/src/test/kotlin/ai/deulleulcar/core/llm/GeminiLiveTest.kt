package ai.deulleulcar.core.llm

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Properties

/**
 * 실제 Gemini API 1회 호출 확인. 기본 테스트에서는 건너뛴다.
 * 실행: `DEULLEULCAR_LIVE=1 ./gradlew :core:test --tests '*GeminiLiveTest*'`
 * 키는 루트 local.properties의 GEMINI_API_KEY에서 읽고 어디에도 출력하지 않는다.
 * 결과(파싱된 구조만)는 core/build/live/gemini-live.txt에 쓴다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "DEULLEULCAR_LIVE", matches = "1")
class GeminiLiveTest {

    private val key: String? = listOf(File("../local.properties"), File("local.properties"))
        .firstOrNull { it.isFile }
        ?.let { f -> Properties().apply { f.reader().use { load(it) } }.getProperty("GEMINI_API_KEY") }
        ?.trim()?.takeIf { it.isNotEmpty() }

    private val model = System.getenv("DEULLEULCAR_LIVE_MODEL") ?: GeminiClient.DEFAULT_MODEL

    @Test
    fun `실제 발화 파싱`() = runBlocking {
        val out = File("build/live").apply { mkdirs() }.resolve("gemini-live.txt")
        val report = StringBuilder()
        report.appendLine("model=$model keyPresent=${key != null}")
        val client = GeminiClient({ key }, model = model)
        val llm = LlmErrandParser(client)
        for (utterance in listOf("퇴근길에 약국이랑 마트 들렀다 집에 가자", "체육관 가는 길에 햇살약국이랑 꽃집 좀 들러줘")) {
            val started = System.nanoTime()
            val direct = runCatching { llm.parse(utterance) }
            val ms = (System.nanoTime() - started) / 1_000_000
            report.appendLine("utterance=$utterance")
            report.appendLine("  llm(${ms}ms)=${direct.getOrNull() ?: ("FAIL " + direct.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" })}")
            report.appendLine("  hybrid=${HybridErrandParser(llm).parse(utterance)}")
        }
        if (report.contains("FAIL")) report.appendLine("models=" + listModels())
        out.writeText(report.toString())
    }

    /** ListModels — generateContent를 지원하는 모델 이름만. */
    private fun listModels(): String = runCatching {
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
            .header("x-goog-api-key", key ?: return "no key")
            .build()
        OkHttpClient().newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            Regex("\"name\":\\s*\"models/([^\"]+)\"").findAll(body).map { it.groupValues[1] }
                .filter { it.contains("gemini") }.joinToString(", ")
                .ifEmpty { "HTTP ${resp.code}" }
        }
    }.getOrElse { it.javaClass.simpleName }
}
