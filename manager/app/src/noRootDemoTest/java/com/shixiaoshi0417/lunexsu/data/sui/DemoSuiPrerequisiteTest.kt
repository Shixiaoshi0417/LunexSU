package com.shixiaoshi0417.lunexsu.data.sui

import com.shixiaoshi0417.lunexsu.data.zygisk.BuiltinZygisk
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DemoSuiPrerequisiteTest {
    @Test fun pendingProviderChangesBlockEnableButNeverDisable() = runBlocking {
        val original = BuiltinZygisk.status()
        try {
            SuiManager.disable()
            BuiltinZygisk.apply(null)
            SuiManager.refresh(false)
            assertFalse(SuiManager.state.value.status.zygisk.ready)
            assertTrue(SuiManager.state.value.switchEnabled)
            SuiManager.enable()
            assertFalse(SuiManager.state.value.status.desiredEnabled)

            BuiltinZygisk.apply(ZygiskBackend.Re)
            SuiManager.refresh(false)
            assertTrue(SuiManager.state.value.status.zygisk.ready)
            SuiManager.enable()
            assertTrue(SuiManager.state.value.status.desiredEnabled)

            BuiltinZygisk.apply(ZygiskBackend.Neo)
            SuiManager.refresh(false)
            assertFalse(SuiManager.state.value.status.zygisk.ready)
            assertTrue(SuiManager.state.value.canSwitchTo(false))
            SuiManager.disable()
            assertFalse(SuiManager.state.value.status.desiredEnabled)
            assertFalse(SuiManager.state.value.connected)
        } finally {
            SuiManager.disable()
            BuiltinZygisk.apply(original.configured, original.nextNextConfigured)
            SuiManager.refresh(false)
        }
    }
}
