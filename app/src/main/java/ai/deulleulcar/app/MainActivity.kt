package ai.deulleulcar.app

import ai.deulleulcar.app.sdk.RuntimePermissions
import ai.deulleulcar.app.ui.DeulleulCarRoot
import ai.deulleulcar.app.ui.theme.DeulleulCarTheme
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 유일한 화면. 주행 중에도 쓰는 앱이라 매니페스트에 `distractionOptimized`로 표시하고,
 * 주행 중에는 미니 카드(S3)만 그린다(글자 입력·긴 목록 없음).
 */
class MainActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // 권한을 받은 뒤 권한 때문에 못 켠 어댑터를 다시 연결한다
        appContainer.onPermissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 마이크(STT)·위치 등 런타임 권한. 거절해도 앱은 해당 기능 없이 동작한다.
        RuntimePermissions.missing(this).takeIf { it.isNotEmpty() }?.let { permissionRequest.launch(it.toTypedArray()) }
        appContainer.start()
        setContent {
            DeulleulCarTheme {
                DeulleulCarRoot(appContainer, onDemoMode = ::switchDemoMode)
            }
        }
    }

    private fun switchDemoMode(enabled: Boolean) {
        deulleulCarApp.setDemoMode(enabled)
        appContainer.start()
        recreate()
    }
}
