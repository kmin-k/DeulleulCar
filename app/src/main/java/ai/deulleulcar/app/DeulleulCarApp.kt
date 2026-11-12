package ai.deulleulcar.app

import ai.deulleulcar.app.settings.PrefsSettingsStore
import ai.deulleulcar.app.settings.SettingsStore
import android.app.Application

class DeulleulCarApp : Application() {
    lateinit var settingsStore: SettingsStore
        private set

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = PrefsSettingsStore(this)
        container = AppContainer(this, settingsStore)
    }

    /** 데모 모드를 켜거나 끈다. 어댑터 구성이 바뀌므로 컨테이너를 새로 만든다(화면은 호출한 쪽이 다시 그린다). */
    fun setDemoMode(enabled: Boolean) {
        if (enabled == container.useFakes) {
            settingsStore.update { it.copy(demoMode = enabled) }
            return
        }
        settingsStore.update { it.copy(demoMode = enabled) }
        container.release()
        container = AppContainer(this, settingsStore)
    }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as DeulleulCarApp).container

val android.content.Context.deulleulCarApp: DeulleulCarApp
    get() = applicationContext as DeulleulCarApp
