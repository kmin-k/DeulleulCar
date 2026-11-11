package ai.deulleulcar.core.llm

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.SocketException

internal const val FAKE_KEY = "test-key-not-real"

/** 모델이 [text]를 돌려준 generateContent 응답. */
internal fun geminiResponse(text: String): MockResponse = MockResponse()
    .setResponseCode(200)
    .setHeader("Content-Type", "application/json")
    .setBody(
        buildJsonObject {
            putJsonArray("candidates") {
                addJsonObject {
                    putJsonObject("content") {
                        put("role", "model")
                        putJsonArray("parts") { addJsonObject { put("text", text) } }
                    }
                    put("finishReason", "STOP")
                }
            }
            put("modelVersion", "fake")
        }.toString(),
    )

/** Windows에서 가끔 bind가 일시 실패해서 몇 번 다시 시도한다. */
internal fun startServer(): MockWebServer {
    var last: Exception? = null
    repeat(5) {
        val s = MockWebServer()
        try {
            s.start()
            return s
        } catch (e: SocketException) {
            last = e
            Thread.sleep(100)
        }
    }
    throw last!!
}

/** 이름 해석이 항상 실패하는 주소 (RFC 2606 .invalid). */
internal const val UNREACHABLE_URL = "http://deulleulcar.invalid/"

internal const val PHARMACY_MART_HOME = """
{"errands":[{"keyword":"약국","category":"PHARMACY","placeName":null},
            {"keyword":"마트","category":"MART","placeName":null}],
 "destination":{"type":"HOME","name":null},
 "timeHint":"COMMUTE_HOME"}
"""
