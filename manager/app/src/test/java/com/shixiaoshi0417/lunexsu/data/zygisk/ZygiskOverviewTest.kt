package com.shixiaoshi0417.lunexsu.data.zygisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZygiskOverviewTest {
    private val complete = listOf(
        "module rezygisk 0 0 0 0 0 0",
        "module zygisksu 0 0 0 0 0 0",
        "module zygisknextsu 0 0 0 0 0 0",
        "monitor 0",
        "lunexsu-zygisk-overview-v1",
    )

    private fun result(vararg records: String) = parseZygiskOverview(complete.dropLast(1) + records + complete.last())

    @Test fun unknownIsNeverReportedAsDisabled() {
        assertEquals(ZygiskOverviewState.Unavailable, ZygiskOverview().state(false))
    }

    @Test fun completeEmptySnapshotReportsNoConfiguredBackend() {
        val snapshot = parseZygiskOverview(complete)
        assertTrue(snapshot.available)
        assertEquals(ZygiskOverviewState.Disabled, snapshot.state(false))
    }

    @Test fun safeModeTakesPrecedenceEvenWithoutRootQuery() {
        assertEquals(ZygiskOverviewState.SafeMode, ZygiskOverview().state(true))
    }

    @Test fun incompleteShellOutputIsUnavailable() {
        complete.indices.forEach { removed ->
            assertFalse(parseZygiskOverview(complete.filterIndexed { index, _ -> index != removed }).available)
        }
    }

    @Test fun unknownRecordsAreNotAcceptedAsACompleteSnapshot() {
        assertFalse(result("arbitrary output").available)
    }

    @Test fun duplicateModuleDoesNotHideMissingModule() {
        assertFalse(parseZygiskOverview(complete.toMutableList().apply { this[1] = this[0] }).available)
    }

    @Test fun unknownModuleIdIsRejected() {
        assertFalse(parseZygiskOverview(complete.map { it.replace("rezygisk", "external") }).available)
    }

    @Test fun invalidFlagBitIsRejected() {
        assertFalse(parseZygiskOverview(complete.map { it.replace("rezygisk 0", "rezygisk invalid") }).available)
    }

    @Test fun malformedModuleWidthIsRejected() {
        assertFalse(parseZygiskOverview(complete.map { it.replace("rezygisk 0", "rezygisk") }).available)
    }

    @Test fun invalidOrDuplicateMonitorIsRejected() {
        assertFalse(parseZygiskOverview(complete.map { it.replace("monitor 0", "monitor invalid") }).available)
        assertFalse(result("monitor 1").available)
    }

    @Test fun terminatorMustBeLastAndUnique() {
        assertFalse(parseZygiskOverview(complete + "extra").available)
        assertFalse(result(complete.last()).available)
    }

    @Test fun installedAndStagedVersionsAreSeparateAndPreserveSpaces() {
        val snapshot = result("version zygisknextsu installed v1.8.3 (old)", "version zygisknextsu staged v1.11.0 (new)")
        assertTrue(snapshot.available)
        assertEquals(ZygiskModuleVersion("v1.8.3 (old)", "v1.11.0 (new)"), snapshot.versions["zygisknextsu"])
    }

    @Test fun versionsAreNotInventedFromBundledAssetPins() {
        assertTrue(parseZygiskOverview(complete).versions.isEmpty())
    }

    @Test fun missingInstalledVersionDoesNotBorrowStagedVersion() {
        val version = result("version rezygisk staged v1.0.0").versions.getValue("rezygisk")
        assertNull(version.installed)
        assertEquals("v1.0.0", version.staged)
    }

    @Test fun duplicateVersionRecordsAreRejected() {
        assertFalse(result("version rezygisk installed 1", "version rezygisk installed 2").available)
    }

    @Test fun invalidMetadataIsRejected() {
        listOf("version other installed 1", "version rezygisk current 1", "version rezygisk installed ",
            "version rezygisk installed " + "x".repeat(121), "version rezygisk installed bad\u0001value").forEach {
            assertFalse(it, result(it).available)
        }
    }

    @Test fun allSixVersionSlotsCanBeReadTogether() {
        val records = ZygiskModule.entries.flatMap { listOf("version ${it.moduleId} installed old", "version ${it.moduleId} staged new") }
        val snapshot = result(*records.toTypedArray())
        assertTrue(snapshot.available)
        assertEquals(3, snapshot.versions.size)
    }

    @Test fun oversizedOutputFailsClosed() {
        assertFalse(result(*List(20) { "version rezygisk installed 1" }.toTypedArray()).available)
    }

    @Test fun configuredNeoDoesNotRequireRePtraceMonitor() {
        val snapshot = parseZygiskOverview(complete.map { if (it.startsWith("module zygisksu ")) "module zygisksu 1 1 0 0 0 0" else it })
        assertEquals(ZygiskBackend.Neo, snapshot.status.configured)
        assertFalse(snapshot.status.monitorRunning)
        assertEquals(ZygiskOverviewState.Configured, snapshot.state(false))
    }

    @Test fun pendingRebootIsNotReportedAsRunning() {
        val snapshot = parseZygiskOverview(complete.map { if (it.startsWith("module rezygisk ")) "module rezygisk 1 1 1 0 0 0" else it })
        assertEquals(ZygiskOverviewState.PendingReboot, snapshot.state(false))
    }

    @Test fun processObservationCanExistWithoutAnOwnedBackend() {
        val snapshot = parseZygiskOverview(complete.map { it.replace("monitor 0", "monitor 1") })
        assertNull(snapshot.status.configured)
        assertEquals(ZygiskOverviewState.MonitorDetected, snapshot.state(false))
    }

    @Test fun externalConflictTakesPrecedenceOverMonitor() {
        val snapshot = parseZygiskOverview(complete.map { when {
            it.startsWith("module rezygisk ") -> "module rezygisk 1 0 0 0 0 0"
            it == "monitor 0" -> "monitor 1"
            else -> it
        } })
        assertEquals("rezygisk", snapshot.status.conflictId)
        assertEquals(ZygiskOverviewState.Conflict, snapshot.state(false))
    }

    @Test fun legacyVexIsNotClaimedAsNormalReZygisk() {
        val snapshot = parseZygiskOverview(complete.map { if (it.startsWith("module rezygisk ")) "module rezygisk 1 1 0 0 0 1" else it })
        assertEquals(ZygiskOverviewState.Conflict, snapshot.state(false))
    }

    @Test fun znnFlagsDoNotClaimItsProcessOrInjectionSucceeded() {
        val snapshot = parseZygiskOverview(complete.map { if (it.startsWith("module zygisknextsu ")) "module zygisknextsu 1 1 0 0 0 0" else it })
        assertTrue(snapshot.status.nextNextConfigured)
        assertFalse(snapshot.status.monitorRunning)
        assertEquals(ZygiskOverviewState.Disabled, snapshot.state(false))
    }

    @Test fun diagnosticScriptOnlyReadsKnownModulePaths() {
        val script = zygiskOverviewScript()
        assertTrue(script.contains("head -c 8192 /data/adb/modules/rezygisk/module.prop"))
        assertTrue(script.contains("/data/adb/modules_update/zygisknextsu/module.prop"))
        assertTrue(script.startsWith("if [ -d /data/adb/modules ]"))
        assertTrue(script.endsWith("else false; fi\n"))
        assertFalse(script.contains("exit "))
        assertTrue(script.contains("printf 'lunexsu-zygisk-overview-v1\\n'"))
        listOf("module install", "module enable", "module disable", ". /data/adb/").forEach {
            assertFalse(it, script.contains(it))
        }
        // $rm is an existing read-only removal-flag variable, not an rm command.
        assertFalse(Regex("(?m)(^|[;&|])\\s*(touch|rm|chmod|reboot|eval|source|exit)\\s").containsMatchIn(script))
    }
}
