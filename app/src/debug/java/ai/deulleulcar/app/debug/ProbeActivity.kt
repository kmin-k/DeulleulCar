package ai.deulleulcar.app.debug

import ai.deulleulcar.app.appContainer
import ai.deulleulcar.app.sdk.RuntimePermissions
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch

/**
 * 디버그 빌드 전용 SDK 점검 화면(화면 없음, logcat 태그 `DC/Probe`).
 *
 * adb로 명령을 보낸다. 쉼표로 여러 명령을 한 번에 줄 수 있다(프로세스가 명령마다 새로 뜰 수 있으므로
 * 상태가 필요한 명령은 한 번에 보내는 것이 안전하다. 각 명령은 필요한 어댑터를 스스로 start한다):
 * ```
 * adb shell am start -n ai.deulleulcar.app/.debug.ProbeActivity --es cmd tts_start,tts_say --es text "안녕하세요"
 * ```
 * 로그 구독은 Application 스코프에서 프로세스당 한 번만 건다([ProbeLog]). 명령 목록은 [ProbeCommands].
 */
class ProbeActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        Log.i(TAG, "permission result: $result")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = RuntimePermissions.missing(this)
        Log.i(TAG, "pid=${android.os.Process.myPid()} missing runtime permissions: $missing")
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val cmds = intent?.getStringExtra("cmd") ?: return
        val scope = appContainer.appScope
        for (cmd in cmds.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            Log.i(TAG, "cmd=$cmd")
            try {
                ProbeCommands.handle(this, cmd, intent) { key, block ->
                    if (ProbeLog.once(key)) scope.launch { block() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cmd 실패: $cmd", e)
            }
        }
    }

    companion object {
        const val TAG = "DC/Probe"
    }
}

/** 프로세스당 한 번만 거는 로그 구독 표시. */
internal object ProbeLog {
    private val started = mutableSetOf<String>()

    @Synchronized
    fun once(key: String): Boolean = started.add(key)
}
