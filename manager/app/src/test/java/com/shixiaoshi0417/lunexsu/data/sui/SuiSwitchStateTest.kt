package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuiSwitchStateTest {
    private val off = SuiUiState(status = SuiStatus(state = "disabled", payloadAvailable = true,
        zygisk = SuiZygiskStatus("ready", listOf("rezygisk"))))
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

    @Test fun missingOrUnverifiedZygiskOffersThePopupButCannotEnableSui() {
        for (prerequisite in listOf(SuiZygiskStatus(), SuiZygiskStatus("missing"),
            SuiZygiskStatus("pending_reboot", listOf("rezygisk")),
            SuiZygiskStatus("conflict", listOf("rezygisk", "zygisksu")))) {
            val blocked = off.copy(status = off.status.copy(zygisk = prerequisite))
            assertTrue(blocked.switchEnabled)
            assertFalse(blocked.switchChecked)
            assertFalse(blocked.canSwitchTo(true))
            assertFalse(blocked.managementVisible)
        }
    }

    @Test fun losingThePrerequisiteNeverPreventsDisablingSui() {
        for (state in listOf("missing", "pending_reboot", "conflict", "unavailable")) {
            val enabled = on.copy(status = on.status.copy(zygisk = SuiZygiskStatus(state)))
            assertTrue(enabled.canSwitchTo(false))
            assertFalse(enabled.canSwitchTo(true))
        }
    }

    @Test fun eitherKnownExternalProviderSatisfiesTheSameGate() {
        for (id in listOf("rezygisk", "zygisksu")) {
            assertTrue(off.copy(status = off.status.copy(zygisk = SuiZygiskStatus("ready", listOf(id))))
                .canSwitchTo(true))
        }
    }

    @Test fun aFreshProviderLossInvalidatesAnEarlierEligibleSnapshot() {
        assertTrue(off.canSwitchTo(true))
        val fresh = off.copy(status = off.status.copy(zygisk = SuiZygiskStatus("missing")))
        assertFalse(fresh.canSwitchTo(true))
        assertTrue(fresh.switchEnabled)
    }

    @Test fun directMalformedPrerequisiteObjectsCannotBypassTheGate() {
        for (ids in listOf(emptyList(), listOf("lsposed"), listOf("rezygisk", "rezygisk"),
            listOf("rezygisk", "zygisksu", "sui"))) {
            assertFalse(off.copy(status = off.status.copy(zygisk = SuiZygiskStatus("ready", ids)))
                .canSwitchTo(true))
        }
    }
}
