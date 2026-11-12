package ai.deulleulcar.app.settings

import ai.deulleulcar.core.model.GeoPoint
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 사용자 설정(S6). API 키는 이 기기의 앱 전용 저장소에만 둔다: APK·저장소·로그 어디에도 넣지 않는다.
 * 키가 없으면 규칙 기반 해석 + 데모 장소로 동작한다.
 */
data class Settings(
    val geminiApiKey: String = "",
    val kakaoRestApiKey: String = "",
    val demoMode: Boolean = false,
    /** 데모 모드에서 시나리오 1의 "평일 18:40"으로 시각을 고정할지. */
    val demoClock: Boolean = true,
    val home: GeoPoint? = null,
    val work: GeoPoint? = null,
) {
    val hasGemini: Boolean get() = geminiApiKey.isNotBlank()
    val hasKakao: Boolean get() = kakaoRestApiKey.isNotBlank()
}

interface SettingsStore {
    val settings: StateFlow<Settings>

    fun update(change: (Settings) -> Settings)
}

/** 테스트·미리보기용. */
class InMemorySettingsStore(initial: Settings = Settings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<Settings> = state.asStateFlow()

    override fun update(change: (Settings) -> Settings) {
        state.value = change(state.value)
    }
}

/** 앱 전용(MODE_PRIVATE) SharedPreferences. allowBackup=false라 백업으로도 나가지 않는다. */
class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    override val settings: StateFlow<Settings> = state.asStateFlow()

    @Synchronized
    override fun update(change: (Settings) -> Settings) {
        val next = change(state.value)
        prefs.edit().apply {
            putString(K_GEMINI, next.geminiApiKey.trim())
            putString(K_KAKAO, next.kakaoRestApiKey.trim())
            putBoolean(K_DEMO, next.demoMode)
            putBoolean(K_DEMO_CLOCK, next.demoClock)
            putPoint(K_HOME, next.home)
            putPoint(K_WORK, next.work)
        }.apply()
        state.value = next.copy(geminiApiKey = next.geminiApiKey.trim(), kakaoRestApiKey = next.kakaoRestApiKey.trim())
    }

    private fun read() = Settings(
        geminiApiKey = prefs.getString(K_GEMINI, "").orEmpty(),
        kakaoRestApiKey = prefs.getString(K_KAKAO, "").orEmpty(),
        demoMode = prefs.getBoolean(K_DEMO, DEFAULT_DEMO),
        demoClock = prefs.getBoolean(K_DEMO_CLOCK, true),
        home = point(K_HOME),
        work = point(K_WORK),
    )

    private fun point(key: String): GeoPoint? {
        if (!prefs.contains("$key.lat")) return null
        return GeoPoint(prefs.getFloat("$key.lat", 0f).toDouble(), prefs.getFloat("$key.lon", 0f).toDouble())
    }

    private fun android.content.SharedPreferences.Editor.putPoint(key: String, p: GeoPoint?) {
        if (p == null) {
            remove("$key.lat")
            remove("$key.lon")
        } else {
            putFloat("$key.lat", p.lat.toFloat())
            putFloat("$key.lon", p.lon.toFloat())
        }
    }

    companion object {
        private const val FILE = "deulleulcar_settings"
        private const val K_GEMINI = "gemini_api_key"
        private const val K_KAKAO = "kakao_rest_api_key"
        private const val K_DEMO = "demo_mode"
        private const val K_DEMO_CLOCK = "demo_clock"
        private const val K_HOME = "home"
        private const val K_WORK = "work"

        /** 첫 실행은 실제 SDK 모드. 데모 모드는 설정에서 켠다. */
        const val DEFAULT_DEMO = false
    }
}

/** 화면에 키를 보여 줄 때: 앞 4자리와 길이만. */
fun maskKey(key: String): String = when {
    key.isBlank() -> ""
    key.length <= 6 -> "•".repeat(key.length)
    else -> key.take(4) + "•".repeat(minOf(12, key.length - 4))
}
