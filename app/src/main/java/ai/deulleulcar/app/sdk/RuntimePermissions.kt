package ai.deulleulcar.app.sdk

import android.content.Context
import android.content.pm.PackageManager

/**
 * 런타임에 요청해야 할 수 있는 권한. Pleos 권한 중 무엇이 dangerous인지는 기기마다 정의가 달라
 * 매니페스트에 선언한 것을 모두 요청 대상으로 두고, 이미 허용됐거나 기기에 정의되지 않은 권한은 [missing]에서 걸러진다.
 */
object RuntimePermissions {
    const val RECORD_AUDIO = android.Manifest.permission.RECORD_AUDIO

    val ALL = listOf(
        RECORD_AUDIO,
        "pleos.car.permission.FUSED_LOCATION",
        "pleos.car.permission.CAR_SAFETY",
        "pleos.car.permission.PRIVATE_CAR_SAFETY",
        "pleos.car.permission.CAR_ENERGY",
        "pleos.car.permission.CAR_DRIVE",
        "pleos.car.permission.CAR_INFO",
        "pleos.car.permission.NAVI_ROUTE",
        "pleos.car.permission.NAVI_ROUTE_SEARCH",
        "pleos.car.permission.STT_SERVICE",
        "pleos.car.permission.TTS_SERVICE",
    )

    /** 아직 허용되지 않았고 기기에 정의된 권한만. */
    fun missing(context: Context, permissions: List<String> = ALL): List<String> = permissions.filter { p ->
        context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED && isDefined(context, p)
    }

    private fun isDefined(context: Context, permission: String): Boolean =
        runCatching { context.packageManager.getPermissionInfo(permission, 0) }.isSuccess
}
