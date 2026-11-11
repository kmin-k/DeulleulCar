package ai.deulleulcar.app.debug

import ai.deulleulcar.app.appContainer
import ai.deulleulcar.app.sdk.RuntimePermissions
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 디버그 빌드 전용 SDK 점검 화면(화면 없음, logcat 태그 `DC/Probe`).
 *
 * adb로 명령을 보낸다:
 * ```
 * adb shell am start -n ai.deulleulcar.app/.debug.ProbeActivity --es cmd vehicle_start
 * ```
 * 명령 목록은 [handle] 참고.
 */
class ProbeActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        Log.i(TAG, "permission result: $result")
    }
    private val jobs = mutableListOf<Job>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = RuntimePermissions.missing(this)
        Log.i(TAG, "missing runtime permissions: $missing")
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd") ?: return
        Log.i(TAG, "cmd=$cmd")
        val c = appContainer
        when (cmd) {
            "perms" -> RuntimePermissions.ALL.forEach { p -> Log.i(TAG, "perm $p granted=${checkSelfPermission(p) == 0}") }
            "vehicle_start" -> {
                c.vehicle.start()
                collect { c.vehicle.signals.collect { Log.i(TAG, "signal: $it") } }
                collect { c.vehicle.features.collect { Log.i(TAG, "vehicle features: $it") } }
            }
            "vehicle_snapshot" -> Log.i(TAG, "snapshot: ${c.vehicle.snapshot.value}")
            "vehicle_stop" -> c.vehicle.stop()
            else -> ProbeCommands.handle(this, cmd, intent, ::collect)
        }
    }

    private fun collect(block: suspend () -> Unit) {
        jobs += lifecycleScope.launch { block() }
    }

    companion object {
        const val TAG = "DC/Probe"
    }
}
