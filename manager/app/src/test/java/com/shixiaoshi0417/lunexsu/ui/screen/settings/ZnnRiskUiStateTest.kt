package com.shixiaoshi0417.lunexsu.ui.screen.settings

import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskBackend
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZnnRiskUiStateTest {
    private val ready = SettingsUiState(
        zygiskStatus = ZygiskStatus(configured = ZygiskBackend.Re, active = ZygiskBackend.Re),
        zygiskBackends = ZygiskBackend.entries,
        showZnnRiskDialog = true,
        znnRiskSecondsRemaining = 0,
        znnRecoveryConfirmed = true,
    )

    @Test
    fun defaultStateCannotConfirmAnything() {
        assertFalse(SettingsUiState().canConfirmZnnRisk)
    }

    @Test
    fun eligibleConfirmedStateWorksForBothBackends() {
        ZygiskBackend.entries.forEach { backend ->
            assertTrue(ready.copy(zygiskStatus = ZygiskStatus(configured = backend)).canConfirmZnnRisk)
        }
    }

    @Test
    fun evenOneSecondRemainingDisablesContinuation() {
        assertFalse(ready.copy(znnRiskSecondsRemaining = 1).canConfirmZnnRisk)
    }

    @Test
    fun recoveryMustBeChecked() {
        assertFalse(ready.copy(znnRecoveryConfirmed = false).canConfirmZnnRisk)
    }

    @Test
    fun aHiddenDialogCannotConfirm() {
        assertFalse(ready.copy(showZnnRiskDialog = false).canConfirmZnnRisk)
    }

    @Test
    fun busyStateCannotConfirm() {
        assertFalse(ready.copy(zygiskBusy = true).canConfirmZnnRisk)
    }

    @Test
    fun lateLoadingCannotConfirm() {
        assertFalse(ready.copy(isLateLoadMode = true).canConfirmZnnRisk)
    }

    @Test
    fun occupiedCompanionCannotConfirm() {
        assertFalse(ready.copy(zygiskStatus = ready.zygiskStatus!!.copy(conflictId = "zygisknextsu")).canConfirmZnnRisk)
    }

    @Test
    fun differentIdInjectorDoesNotBypassTheExistingRiskRequirements() {
        val state = ready.copy(zygiskStatus = ready.zygiskStatus!!.copy(conflictId = "zygisksu"))
        assertTrue(state.canConfirmZnnRisk)
        assertFalse(state.copy(znnRecoveryConfirmed = false).canConfirmZnnRisk)
        assertFalse(state.copy(znnRiskSecondsRemaining = 1).canConfirmZnnRisk)
    }

    @Test
    fun legacyVexCannotConfirm() {
        assertFalse(ready.copy(zygiskStatus = ready.zygiskStatus!!.copy(legacyVex = true)).canConfirmZnnRisk)
    }

    @Test
    fun aDisabledBackendCannotConfirm() {
        assertFalse(ready.copy(zygiskStatus = ZygiskStatus()).canConfirmZnnRisk)
    }

    @Test
    fun alreadyConfiguredZnnCannotReplayConfirmation() {
        assertFalse(ready.copy(zygiskStatus = ready.zygiskStatus!!.copy(nextNextConfigured = true)).canConfirmZnnRisk)
    }

    @Test
    fun disablingZnnIsNotBlockedByTheWarningRequirements() {
        val state = ready.copy(
            zygiskStatus = ready.zygiskStatus!!.copy(nextNextConfigured = true),
            znnRiskSecondsRemaining = 10,
            znnRecoveryConfirmed = false,
        )
        assertTrue(state.zygiskNextNextEnabled)
        assertFalse(state.canConfirmZnnRisk)
    }
}
