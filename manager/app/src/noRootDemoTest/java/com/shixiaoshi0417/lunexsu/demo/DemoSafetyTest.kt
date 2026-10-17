package com.shixiaoshi0417.lunexsu.demo

import com.shixiaoshi0417.lunexsu.BuildConfig
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.ui.util.*
import com.topjohnwu.superuser.Shell
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit

class DemoSafetyTest {
    @Test fun independentIdentityAndFlag() {
        assertTrue(BuildConfig.NO_ROOT_DEMO)
        assertEquals("com.shixiaoshi0417.lunexsu.demo", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.VERSION_NAME.endsWith("-no-root-demo"))
    }
    @Test fun workingPresentationDoesNotSupplyARootShell() {
        assertTrue(Natives.isManager)
        assertTrue(Natives.isFullFeatured())
        assertTrue(rootAvailable())
        createRootShell().use { assertFalse(it.isRoot) }
    }
    @Test fun noNativeMethodsOrSuperkeyAuthentication() {
        assertFalse(Natives::class.java.declaredMethods.any { Modifier.isNative(it.modifiers) })
        assertNull(Natives.superkeySalt())
        assertFalse(Natives.authenticateSuperkey(ByteArray(32)))
    }
    @Test fun arbitraryCommandsFailClosedWithoutStartingAProcess() {
        DemoShell().use { shell ->
            val result = shell.newJob().add("su", "reboot", "touch /data/adb/demo_must_not_exist").exec()
            assertEquals(126, result.code)
            assertTrue(result.err.any { it.contains("no command") })
            assertTrue(result.out.isEmpty())
        }
    }
    @Test fun commandStreamsAreNotReadOrExecuted() {
        val input = object : InputStream() { override fun read(): Int = error("Must not read scripts") }
        assertEquals(126, DemoShell().newJob().add(input).exec().code)
    }
    @Test fun rawTasksAreNeverInvoked() {
        val task = object : Shell.Task {
            override fun run(stdin: OutputStream, stdout: InputStream, stderr: InputStream) = error("Must not run raw tasks")
            override fun shellDied() = Unit
        }
        val shell = DemoShell()
        assertThrows(IOException::class.java) { shell.execTask(task) }
        shell.submitTask(task)
    }
    @Test fun queuedCommandsFailClosedToo() {
        assertEquals(126, DemoShell().newJob().add("reboot").enqueue().get().code)
    }
    @Test fun callbacksReceiveOnlySimulatedFailure() {
        var code = 0
        DemoShell().newJob().add("su").submit(null) { code = it.code }
        assertEquals(126, code)
    }
    @Test fun closingShellDoesNotWaitForOrControlAnyProcess() {
        val shell = DemoShell()
        assertTrue(shell.isAlive)
        assertTrue(shell.waitAndClose(0, TimeUnit.SECONDS))
        assertFalse(shell.isAlive)
        assertFalse(shell.isRoot)
    }
    @Test fun daemonPathIsNotExecutable() {
        assertEquals("demo://no-executable", getKsuDaemonPath())
        assertFalse(execKsud("boot-patch --flash"))
        assertFalse(execKsud("module install arbitrary.zip"))
        assertFalse(removeAsRoot("/data/adb/modules"))
    }
    @Test fun flashSimulationNeverReadsOrDownloadsInput() {
        val output = mutableListOf<String>()
        val result = downloadBoot("invalid://never-request", "init_boot", LkmSelection.KmiNone,
            false, false, false, "", output::add, { error("Unexpected stderr") })
        assertEquals(0, result.code)
        assertFalse(result.showReboot)
        assertEquals(listOf(DemoState.NOTICE), output)
    }
    @Test fun scriptsOnlyProduceAnExplicitSimulationNotice() {
        val output = mutableListOf<String>()
        assertEquals(0, runOneshotScript("su -c reboot", output::add, {}))
        assertEquals(listOf(DemoState.NOTICE), output)
    }
    @Test fun featureChangesStayInMemory() {
        assertTrue(execKsud("feature set demo_safety_feature 0"))
        assertEquals(0L, DemoState.features["demo_safety_feature"])
    }
    @Test fun profileChangesStayInMemory() {
        val profile = Natives.Profile("demo.safety", 10700, allowSu = true)
        assertTrue(Natives.setAppProfile(profile))
        assertTrue(Natives.getAppProfile(profile.name, profile.currentUid).allowSu)
        assertFalse(DemoShell().isRoot)
    }
    @Test fun moduleChangesStayInMemory() {
        assertTrue(toggleModule("demo_theme", false))
        assertFalse(DemoState.moduleRows().first { it.first == "demo_theme" }.second)
        assertTrue(toggleModule("demo_theme", true))
        assertFalse(toggleModule("not-a-demo-module", true))
    }
    @Test fun blockListValidationMatchesNormalUiRules() {
        assertTrue(isBlockModulesValid("one,two_2"))
        assertFalse(isBlockModulesValid("one two"))
        assertFalse(isBlockModulesValid("bad-name"))
        assertFalse(isBlockModulesValid("a".repeat(65)))
        assertEquals(listOf("one", "two"), parseBlockModules("one, ,two"))
    }
}
