package ai.deulleulcar.app.debug

import android.content.Context
import android.content.Intent
import android.util.Log

/** 음성·내비·위치 점검 명령(T12, T13에서 추가). */
internal object ProbeCommands {
    fun handle(context: Context, cmd: String, intent: Intent, collect: (suspend () -> Unit) -> Unit) {
        Log.w(ProbeActivity.TAG, "unknown cmd: $cmd")
    }
}
