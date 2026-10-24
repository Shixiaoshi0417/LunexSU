package com.shixiaoshi0417.lunexsu.ui.screen.settings

import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskBackend
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZygiskEnableUiStateTest {
    private val ready = SettingsUiState(
        zygiskStatus = ZygiskStatus(),
        zygiskBackends = ZygiskBackend.entries,
        showZygiskEnableDialog = true,
    )

    @Test fun explicitVisibleConfirmationIsRequired() {
        assertTrue(ready.canConfirmZygiskEnable)
        assertFalse(ready.copy(showZygiskEnableDialog = false).canConfirmZygiskEnable)
        assertFalse(SettingsUiState().canConfirmZygiskEnable)
    }

    @Test fun sameIdCollisionAllowsShowingWarningButNotContinuing() {
        val state = ready.copy(zygiskStatus = ZygiskStatus(conflictId = "rezygisk"))
        assertTrue(state.zygiskSwitchEnabled)
        assertFalse(state.canConfirmZygiskEnable)
    }

    @Test fun differentIdProviderAllowsConfirmedEnable() {
        assertTrue(ready.copy(zygiskStatus = ZygiskStatus(conflictId = "zygisksu")).canConfirmZygiskEnable)
    }

    @Test fun foreignCompanionDoesNotPreventTheDefaultBackend() {
        assertTrue(ready.copy(zygiskStatus = ZygiskStatus(conflictId = "zygisknextsu")).canConfirmZygiskEnable)
    }

    @Test fun aSecondOccupiedIdCannotHideTheTargetCollision() {
        assertFalse(ready.copy(zygiskStatus = ZygiskStatus(
            conflictId = "zygisksu", occupiedIds = setOf("rezygisk", "zygisksu"),
        )).canConfirmZygiskEnable)
    }

    @Test fun busyLateUnknownAndUnsupportedStatesCannotConfirm() {
        assertFalse(ready.copy(zygiskBusy = true).canConfirmZygiskEnable)
        assertFalse(ready.copy(isLateLoadMode = true).canConfirmZygiskEnable)
        assertFalse(ready.copy(zygiskStatus = null).canConfirmZygiskEnable)
        assertFalse(ready.copy(zygiskBackends = emptyList()).canConfirmZygiskEnable)
    }

    @Test fun configuredBackendCannotReplayEnableConfirmation() {
        assertFalse(ready.copy(zygiskStatus = ZygiskStatus(configured = ZygiskBackend.Re)).canConfirmZygiskEnable)
    }

    @Test fun removingNeoStillConfirmsTheActualDefaultReTarget() {
        val state = ready.copy(zygiskStatus = ZygiskStatus(active = ZygiskBackend.Neo, conflictId = "rezygisk"))
        assertEquals(ZygiskBackend.Re, state.zygiskSelected)
        assertFalse(state.canConfirmZygiskEnable)
    }

    @Test fun unavailableDefaultUsesTheSameFallbackInPromptAndApply() {
        val state = ready.copy(zygiskBackends = listOf(ZygiskBackend.Neo))
        assertEquals(ZygiskBackend.Neo, state.zygiskSelected)
        assertFalse(state.copy(zygiskStatus = ZygiskStatus(conflictId = "zygisksu")).canConfirmZygiskEnable)
    }

    @Test fun legacyVexNeedsItsExistingManualMigration() {
        assertFalse(ready.copy(zygiskStatus = ZygiskStatus(legacyVex = true)).canConfirmZygiskEnable)
    }
}
