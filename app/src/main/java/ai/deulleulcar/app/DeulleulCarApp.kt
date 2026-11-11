package ai.deulleulcar.app

import android.app.Application

class DeulleulCarApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as DeulleulCarApp).container
