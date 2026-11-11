package ai.deulleulcar.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreTest {
    @Test
    fun moduleName() {
        assertEquals("deulleulcar-core", Core.NAME)
    }
}
