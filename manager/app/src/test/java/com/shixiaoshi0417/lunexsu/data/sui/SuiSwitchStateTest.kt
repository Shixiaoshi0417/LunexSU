package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiSwitchStateTest {
    private val off = SuiUiState(status = SuiStatus(state = "disabled", payloadAvailable = true))
    private val on = off.copy(status = off.status.copy(state = "enabled", desiredEnabled = true))

    @Test fun offWithPayloadCanOnlyBeSwitchedOn() {
        assertFalse(off.switchChecked)
        assertTrue(off.switchEnabled)
        assertTrue(off.canSwitchTo(true))
        assertFalse(off.canSwitchTo(false))
    }

    @Test fun onCanOnlyBeSwitchedOffAndReplayIsRejected() {
        assertTrue(on.switchChecked)
        assertTrue(on.canSwitchTo(false))
        assertFalse(on.canSwitchTo(true))
    }

    @Test fun managementIsHiddenUntilSwitchedOn() {
        assertFalse(SuiUiState().managementVisible)
        assertFalse(off.managementVisible)
        assertTrue(on.managementVisible)
        assertTrue(on.copy(status = on.status.copy(pendingReboot = true)).managementVisible)
    }

    @Test fun busyOrUnconfirmedLifecycleBlocksBothDirections() {
        for (state in listOf(off, on)) {
            for (blocked in listOf(state.copy(busy = true), state.copy(reconciling = true))) {
                assertFalse(blocked.switchEnabled)
                assertFalse(blocked.canSwitchTo(true))
                assertFalse(blocked.canSwitchTo(false))
            }
        }
    }

    @Test fun missingPayloadCannotEnableButAnEnabledSuiCanStillBeDisabled() {
        val missing = off.copy(status = off.status.copy(payloadAvailable = false))
        assertFalse(missing.switchEnabled)
        assertFalse(missing.canSwitchTo(true))
        val enabledWithoutPayload = on.copy(status = on.status.copy(payloadAvailable = false))
        assertTrue(enabledWithoutPayload.canSwitchTo(false))
    }

    @Test fun unknownInitialStatusOffersNothing() {
        val initial = SuiUiState()
        assertFalse(initial.switchChecked)
        assertFalse(initial.switchEnabled)
        assertFalse(initial.canSwitchTo(true))
    }
}
