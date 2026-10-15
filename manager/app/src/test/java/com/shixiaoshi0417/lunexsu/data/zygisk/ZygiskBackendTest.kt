package com.shixiaoshi0417.lunexsu.data.zygisk

import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskBackend.Neo
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskBackend.Vex
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

    @Test
    fun defaultsToOffWithNothingInstalled() {
        val status = zygiskStatusOf(emptyMap(), monitorRunning = false)
        assertNull(status.configured)
        assertNull(status.active)
        assertFalse(status.pendingReboot)
    }

    @Test
    fun vexIsTheDefaultOnArm64AndNeoElsewhere() {
        assertEquals(Vex, ZygiskBackend.Default)
        assertEquals(listOf(Vex, Neo), ZygiskBackend.supportedOn("arm64-v8a"))
        assertEquals(listOf(Neo), ZygiskBackend.supportedOn("x86_64"))
        assertEquals(emptyList<ZygiskBackend>(), ZygiskBackend.supportedOn("riscv64"))
    }

    @Test
    fun stagedInstallOnlyRunsAfterReboot() {
        val status = zygiskStatusOf(mapOf(Vex to staged), monitorRunning = false)
        assertEquals(Vex, status.configured)
        assertNull(status.active)
        assertTrue(status.pendingReboot)
    }

    @Test
    fun pendingRemovalKeepsRunningUntilReboot() {
        val status = zygiskStatusOf(mapOf(Neo to removing), monitorRunning = true)
        assertNull(status.configured)
        assertEquals(Neo, status.active)
        assertTrue(status.pendingReboot)
    }

    @Test
    fun switchIsPendingUntilReboot() {
        val status = zygiskStatusOf(mapOf(Vex to staged, Neo to removing), monitorRunning = true)
        assertEquals(Vex, status.configured)
        assertEquals(Neo, status.active)
        assertTrue(status.pendingReboot)
    }

    @Test
    fun userInstalledModuleIsAConflictNotABackend() {
        val status = zygiskStatusOf(mapOf(Neo to external), monitorRunning = true)
        assertNull(status.configured)
        assertNull(status.active)
        assertEquals("zygisksu", status.conflictId)
    }

    @Test
    fun safeModeDisableTurnsTheBackendOff() {
        val status = zygiskStatusOf(mapOf(Vex to owned.copy(disabled = true)), monitorRunning = false)
        assertNull(status.configured)
        assertNull(status.active)
    }

    @Test
    fun enablingInstallsTheTarget() {
        assertEquals(listOf(ZygiskStep.Install(Vex)), planZygiskChange(emptyMap(), Vex))
    }

    @Test
    fun switchingStagesTheNewBackendBeforeRemovingTheOld() {
        assertEquals(
            listOf(ZygiskStep.Install(Neo), ZygiskStep.Uninstall(Vex)),
            planZygiskChange(mapOf(Vex to owned), Neo),
        )
    }

    @Test
    fun disablingMarksEveryOwnedBackendForRemoval() {
        assertEquals(listOf(ZygiskStep.Uninstall(Vex)), planZygiskChange(mapOf(Vex to staged), null))
        assertEquals(emptyList<ZygiskStep>(), planZygiskChange(mapOf(Vex to removing), null))
    }

    @Test
    fun reEnablingBeforeRebootRestoresInsteadOfReinstalling() {
        // Reinstalling would rerun the installer, which stops the monitor that is running now.
        assertEquals(listOf(ZygiskStep.Restore(Vex)), planZygiskChange(mapOf(Vex to removing), Vex))
        assertEquals(
            listOf(ZygiskStep.Restore(Vex)),
            planZygiskChange(mapOf(Vex to owned.copy(disabled = true)), Vex),
        )
    }

    @Test
    fun selectingTheConfiguredBackendDoesNothing() {
        assertEquals(emptyList<ZygiskStep>(), planZygiskChange(mapOf(Vex to owned), Vex))
    }

    @Test
    fun failedSwitchRemovesTheStagedBackend() {
        // Leaving Neo staged while Vex stays configured would start both injectors after reboot.
        assertEquals(
            listOf("module uninstall zygisksu"),
            zygiskRollback(
                mapOf(Vex to owned),
                done = listOf(ZygiskStep.Install(Neo)),
                failed = ZygiskStep.Uninstall(Vex),
                staged = false,
            ),
        )
    }

    @Test
    fun failedInstallOnlyCleansUpWhatItStaged() {
        val install = ZygiskStep.Install(Vex)
        assertEquals(emptyList<String>(), zygiskRollback(emptyMap(), emptyList(), install, staged = false))
        assertEquals(
            listOf("module uninstall rezygisk"),
            zygiskRollback(emptyMap(), emptyList(), install, staged = true),
        )
    }

    @Test
    fun failedRestoreReappliesTheOriginalMarks() {
        val before = mapOf(Neo to removing.copy(disabled = true), Vex to owned)
        val restore = ZygiskStep.Restore(Neo)
        val marks = listOf("module uninstall zygisksu", "module disable zygisksu")
        assertEquals(marks, zygiskRollback(before, emptyList(), restore, staged = false))
        assertEquals(marks, zygiskRollback(before, listOf(restore), ZygiskStep.Uninstall(Vex), staged = false))
    }

    @Test
    fun parsesTheStatusScriptOutput() {
        val (flags, monitor) = parseZygiskFlags(
            listOf("module rezygisk 1 1 0 1 0", "module zygisksu 0 0 0 0 0", "monitor 1", "noise"),
        )
        assertEquals(removing, flags[Vex])
        assertEquals(ZygiskModuleFlags(), flags[Neo])
        assertTrue(monitor)
    }

    @Test
    fun statusScriptChecksBothModuleDirectories() {
        val script = zygiskStatusScript()
        ZygiskBackend.entries.forEach {
            assertTrue(script.contains("/data/adb/modules/${it.moduleId}"))
            assertTrue(script.contains("/data/adb/modules_update/${it.moduleId}"))
        }
        assertTrue(script.contains(ZYGISK_OWNER_MARKER))
    }
}
