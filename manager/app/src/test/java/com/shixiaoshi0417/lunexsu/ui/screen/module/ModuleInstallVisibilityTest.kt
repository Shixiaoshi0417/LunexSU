package com.shixiaoshi0417.lunexsu.ui.screen.module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleInstallVisibilityTest {
    @Test
    fun initialStateNeverShowsInstallButton() {
        assertFalse(ModuleUiState().installButtonVisible)
    }

    @Test
    fun unknownSafeModeStateIsHidden() {
        assertFalse(ModuleUiState(isSafeMode = true).installButtonVisible)
    }

    @Test
    fun unknownConflictStateIsHidden() {
        assertFalse(ModuleUiState(magiskInstalled = true).installButtonVisible)
    }

    @Test
    fun unknownStateWithBothRestrictionsIsHidden() {
        assertFalse(ModuleUiState(isSafeMode = true, magiskInstalled = true).installButtonVisible)
    }

    @Test
    fun completedNormalProbeShowsInstallButton() {
        assertTrue(ModuleUiState(environmentChecked = true).installButtonVisible)
    }

    @Test
    fun completedSafeModeProbeKeepsInstallButtonHidden() {
        assertFalse(ModuleUiState(environmentChecked = true, isSafeMode = true).installButtonVisible)
    }

    @Test
    fun completedConflictProbeKeepsInstallButtonHidden() {
        assertFalse(ModuleUiState(environmentChecked = true, magiskInstalled = true).installButtonVisible)
    }

    @Test
    fun completedProbeWithBothRestrictionsKeepsInstallButtonHidden() {
        assertFalse(
            ModuleUiState(environmentChecked = true, isSafeMode = true, magiskInstalled = true).installButtonVisible
        )
    }

    @Test
    fun loadedModuleListDoesNotStandInForEnvironmentCheck() {
        assertFalse(ModuleUiState(hasLoaded = true).installButtonVisible)
    }

    @Test
    fun refreshingTheListDoesNotRevealAnUnknownEnvironment() {
        assertFalse(ModuleUiState(isRefreshing = true).installButtonVisible)
    }

    @Test
    fun invalidationHidesPreviouslyVisibleButtonImmediately() {
        val checked = ModuleUiState(environmentChecked = true)
        assertTrue(checked.installButtonVisible)
        assertFalse(checked.copy(environmentChecked = false).installButtonVisible)
    }

    @Test
    fun probePublishesRestrictionsAndReadinessTogether() {
        val unknown = ModuleUiState()
        val restricted = unknown.copy(environmentChecked = true, isSafeMode = true)
        assertFalse(unknown.installButtonVisible)
        assertFalse(restricted.installButtonVisible)
        assertTrue(restricted.copy(isSafeMode = false).installButtonVisible)
    }
}
