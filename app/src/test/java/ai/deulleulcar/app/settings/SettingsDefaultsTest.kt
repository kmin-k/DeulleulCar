package ai.deulleulcar.app.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsDefaultsTest {
    @Test
    fun `키가 하나도 없으면 데모 모드로 시작한다`() {
        assertTrue(PrefsSettingsStore.defaultDemoMode("", ""))
        assertTrue(PrefsSettingsStore.defaultDemoMode(" ", ""))
        assertFalse(PrefsSettingsStore.defaultDemoMode("g-key", ""))
        assertFalse(PrefsSettingsStore.defaultDemoMode("", "k-key"))
    }
}
