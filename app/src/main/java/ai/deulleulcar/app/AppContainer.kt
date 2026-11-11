package ai.deulleulcar.app

import ai.deulleulcar.app.vehicle.FakeVehicleSignalSource
import ai.deulleulcar.app.vehicle.PleosVehicleSignalSource
import ai.deulleulcar.app.vehicle.VehicleSignalSource
import android.content.Context

/**
 * 수동 DI 컨테이너. 앱 전체에서 어댑터 인스턴스를 하나씩만 만든다.
 * @param useFakes true면 SDK 대신 Fake 구현(데모 모드·SDK 없는 기기).
 */
class AppContainer(context: Context, val useFakes: Boolean = false) {
    private val appContext = context.applicationContext

    val vehicle: VehicleSignalSource by lazy {
        if (useFakes) FakeVehicleSignalSource() else PleosVehicleSignalSource(appContext)
    }
}
