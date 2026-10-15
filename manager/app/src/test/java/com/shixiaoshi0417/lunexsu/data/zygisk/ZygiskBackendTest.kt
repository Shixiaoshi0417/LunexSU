package com.shixiaoshi0417.lunexsu.data.zygisk

import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskModule.Neo
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskModule.NextNext
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskModule.Re
import com.shixiaoshi0417.lunexsu.ui.screen.settings.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZygiskBackendTest {
    private val owned = ZygiskModuleFlags(present = true, owned = true)
    private val staged = owned.copy(update = true)
    private val removing = owned.copy(remove = true)
    private val external = ZygiskModuleFlags(present = true)

    @Test fun defaultsToOffWithNothingInstalled() {
        val status = zygiskStatusOf(emptyMap(), false)
        assertNull(status.configured)
        assertNull(status.active)
        assertFalse(status.nextNextConfigured)
        assertFalse(status.pendingReboot)
    }

    @Test fun reIsTheDefaultOnAllSupportedArchitectures() {
        assertEquals(ZygiskBackend.Re, ZygiskBackend.Default)
        listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach {
            assertEquals(listOf(ZygiskBackend.Re, ZygiskBackend.Neo), ZygiskBackend.supportedOn(it))
        }
        assertEquals(emptyList<ZygiskBackend>(), ZygiskBackend.supportedOn("riscv64"))
    }

    @Test fun stagedInstallOnlyRunsAfterReboot() {
        val status = zygiskStatusOf(mapOf(Re to staged), false)
        assertEquals(ZygiskBackend.Re, status.configured)
        assertNull(status.active)
        assertTrue(status.pendingReboot)
    }

    @Test fun pendingRemovalKeepsRunningUntilReboot() {
        val status = zygiskStatusOf(mapOf(Neo to removing), true)
        assertNull(status.configured)
        assertEquals(ZygiskBackend.Neo, status.active)
        assertTrue(status.pendingReboot)
    }

    @Test fun switchIsPendingUntilReboot() {
        val status = zygiskStatusOf(mapOf(Re to staged, Neo to removing), true)
        assertEquals(ZygiskBackend.Re, status.configured)
        assertEquals(ZygiskBackend.Neo, status.active)
        assertTrue(status.pendingReboot)
    }

    @Test fun userInstalledModuleIsAConflictNotABackend() {
        val status = zygiskStatusOf(mapOf(Neo to external), true)
        assertNull(status.configured)
        assertNull(status.active)
        assertEquals("zygisksu", status.conflictId)
    }

    @Test fun externalAddonIsNotClaimedOrRemoved() {
        val flags = mapOf(Re to owned, NextNext to external)
        val status = zygiskStatusOf(flags, true)
        assertFalse(status.nextNextConfigured)
        assertEquals("zygisknextsu", status.conflictId)
        assertEquals(listOf(ZygiskStep.Uninstall(Re)), planZygiskChange(flags, null))
    }

    @Test fun safeModeDisableTurnsEverythingOff() {
        val status = zygiskStatusOf(mapOf(Re to owned.copy(disabled = true), NextNext to owned.copy(disabled = true)), false)
        assertNull(status.configured)
        assertNull(status.active)
        assertFalse(status.nextNextConfigured)
        assertFalse(status.nextNextActive)
    }

    @Test fun enablingInstallsOnlyTheBackendByDefault() {
        assertEquals(listOf(ZygiskStep.Install(Re)), planZygiskChange(emptyMap(), ZygiskBackend.Re))
    }

    @Test fun switchingStagesTheNewBackendBeforeRemovingTheOld() {
        assertEquals(
            listOf(ZygiskStep.Install(Neo), ZygiskStep.Uninstall(Re)),
            planZygiskChange(mapOf(Re to owned), ZygiskBackend.Neo),
        )
    }

    @Test fun disablingMarksEveryOwnedBackendForRemoval() {
        assertEquals(listOf(ZygiskStep.Uninstall(Re)), planZygiskChange(mapOf(Re to staged), null))
        assertEquals(emptyList<ZygiskStep>(), planZygiskChange(mapOf(Re to removing), null))
    }

    @Test fun reEnablingBeforeRebootRestoresInsteadOfReinstalling() {
        assertEquals(listOf(ZygiskStep.Restore(Re)), planZygiskChange(mapOf(Re to removing), ZygiskBackend.Re))
        assertEquals(listOf(ZygiskStep.Restore(Re)), planZygiskChange(mapOf(Re to owned.copy(disabled = true)), ZygiskBackend.Re))
    }

    @Test fun selectingTheConfiguredBackendDoesNothing() {
        assertEquals(emptyList<ZygiskStep>(), planZygiskChange(mapOf(Re to owned), ZygiskBackend.Re))
    }

    @Test fun failedSwitchRemovesTheStagedBackend() {
        assertEquals(listOf("module uninstall zygisksu"), zygiskRollback(
            mapOf(Re to owned), listOf(ZygiskStep.Install(Neo)), ZygiskStep.Uninstall(Re), false,
        ))
    }

    @Test fun failedInstallOnlyCleansUpWhatItStaged() {
        val install = ZygiskStep.Install(Re)
        assertEquals(emptyList<String>(), zygiskRollback(emptyMap(), emptyList(), install, false))
        assertEquals(listOf("module uninstall rezygisk"), zygiskRollback(emptyMap(), emptyList(), install, true))
    }

    @Test fun failedRestoreReappliesTheOriginalMarks() {
        val before = mapOf(Neo to removing.copy(disabled = true), Re to owned)
        val restore = ZygiskStep.Restore(Neo)
        val marks = listOf("module uninstall zygisksu", "module disable zygisksu")
        assertEquals(marks, zygiskRollback(before, emptyList(), restore, false))
        assertEquals(marks, zygiskRollback(before, listOf(restore), ZygiskStep.Uninstall(Re), false))
    }

    @Test fun addonStagesAfterItsBackend() {
        assertEquals(listOf(ZygiskStep.Install(Re), ZygiskStep.Install(NextNext)), planZygiskChange(emptyMap(), ZygiskBackend.Re, true))
    }

    @Test fun addonCanBeToggledIndependently() {
        assertEquals(listOf(ZygiskStep.Install(NextNext)), planZygiskChange(mapOf(Re to owned), ZygiskBackend.Re, true))
        assertEquals(listOf(ZygiskStep.Uninstall(NextNext)), planZygiskChange(mapOf(Re to owned, NextNext to owned), ZygiskBackend.Re, false))
    }

    @Test fun disablingAlsoRemovesTheAddonBeforeTheBackend() {
        assertEquals(listOf(ZygiskStep.Uninstall(NextNext), ZygiskStep.Uninstall(Re)), planZygiskChange(mapOf(Re to owned, NextNext to owned), null))
    }

    @Test fun switchingBackendsPreservesTheAddon() {
        assertEquals(listOf(ZygiskStep.Install(Neo), ZygiskStep.Uninstall(Re)), planZygiskChange(mapOf(Re to owned, NextNext to owned), ZygiskBackend.Neo, true))
    }

    @Test(expected = IllegalArgumentException::class) fun addonCannotBeEnabledWithoutABackend() {
        planZygiskChange(emptyMap(), null, true)
    }

    @Test fun addonOnlyChangesRequireReboot() {
        val enabling = zygiskStatusOf(mapOf(Re to owned, NextNext to staged), true)
        assertEquals(enabling.configured, enabling.active)
        assertTrue(enabling.nextNextConfigured)
        assertFalse(enabling.nextNextActive)
        assertTrue(enabling.pendingReboot)
        val disabling = zygiskStatusOf(mapOf(Re to owned, NextNext to removing), true)
        assertFalse(disabling.nextNextConfigured)
        assertTrue(disabling.nextNextActive)
        assertTrue(disabling.pendingReboot)
    }

    @Test fun failedAddonInstallRollsBackTheNewBackendToo() {
        assertEquals(listOf("module uninstall zygisknextsu", "module uninstall rezygisk"), zygiskRollback(
            emptyMap(), listOf(ZygiskStep.Install(Re)), ZygiskStep.Install(NextNext), true,
        ))
    }

    @Test fun failedBackendDisableRestoresTheAddon() {
        assertEquals(listOf("module undo-uninstall zygisknextsu"), zygiskRollback(
            mapOf(Re to owned, NextNext to owned), listOf(ZygiskStep.Uninstall(NextNext)), ZygiskStep.Uninstall(Re), false,
        ))
    }

    @Test fun legacyVexMustBeRemovedBeforeInstallingRe() {
        val flags = mapOf(Re to owned.copy(legacyVex = true))
        assertTrue(zygiskStatusOf(flags, true).legacyVex)
        assertTrue(requiresVexRemoval(flags, ZygiskBackend.Re))
        assertTrue(requiresVexRemoval(mapOf(Re to removing.copy(legacyVex = true)), ZygiskBackend.Re))
        assertFalse(requiresVexRemoval(flags, ZygiskBackend.Neo))
        assertFalse(requiresVexRemoval(flags, null))
        assertEquals(listOf(ZygiskStep.Uninstall(Re)), planZygiskChange(flags, null))
    }

    @Test(expected = IllegalArgumentException::class) fun plannerDoesNotOverwriteLegacyVex() {
        planZygiskChange(mapOf(Re to owned.copy(legacyVex = true)), ZygiskBackend.Re)
    }

    @Test fun parsesTheStatusScriptOutput() {
        val (flags, monitor) = parseZygiskFlags(listOf(
            "module rezygisk 1 1 0 1 0 1", "module zygisksu 0 0 0 0 0 0",
            "module zygisknextsu 1 1 1 0 0 0", "monitor 1", "noise",
        ))
        assertEquals(removing.copy(legacyVex = true), flags[Re])
        assertEquals(ZygiskModuleFlags(), flags[Neo])
        assertEquals(staged, flags[NextNext])
        assertTrue(monitor)
    }

    @Test fun statusScriptChecksAllThreeModuleDirectories() {
        val script = zygiskStatusScript()
        ZygiskModule.entries.forEach {
            assertTrue(script.contains("/data/adb/modules/${it.moduleId}"))
            assertTrue(script.contains("/data/adb/modules_update/${it.moduleId}"))
        }
        assertTrue(script.contains(ZYGISK_OWNER_MARKER))
        assertTrue(script.contains("^name=VexZygisk"))
    }

    @Test fun addonSwitchIsGreyUntilBuiltInIsEnabled() {
        val off = SettingsUiState(zygiskStatus = ZygiskStatus(), zygiskBackends = ZygiskBackend.entries)
        assertFalse(off.zygiskNextNextEnabled)
        assertFalse(off.zygiskNextNext)
        val on = off.copy(zygiskStatus = ZygiskStatus(configured = ZygiskBackend.Re))
        assertTrue(on.zygiskNextNextEnabled)
        assertFalse(on.zygiskNextNext)
        assertFalse(on.copy(zygiskBusy = true).zygiskNextNextEnabled)
        assertFalse(on.copy(isLateLoadMode = true).zygiskNextNextEnabled)
        assertFalse(on.copy(zygiskStatus = on.zygiskStatus!!.copy(conflictId = "zygisknextsu")).zygiskNextNextEnabled)
        assertFalse(on.copy(zygiskStatus = on.zygiskStatus.copy(legacyVex = true)).zygiskNextNextEnabled)
    }
}
