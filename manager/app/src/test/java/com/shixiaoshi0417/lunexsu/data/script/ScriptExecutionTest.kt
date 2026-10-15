package com.shixiaoshi0417.lunexsu.data.script

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptExecutionTest {
    private class Run {
        val events = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        var code: Int? = null

        suspend fun execute(failAt: String? = null, cancellation: Boolean = false) = executeScriptLifecycle(
            execute = {
                events += "execute"
                if (cancellation) throw CancellationException("cancelled")
                check(failAt != "execute") { "shell creation failed" }
                7
            },
            record = { events += "record:$it"; check(failAt != "record") },
            cleanup = { events += "cleanup"; check(failAt != "cleanup") },
            release = { events += "release"; check(failAt != "release") },
            finished = { events += "finished"; code = it },
            onFailure = { failures += it },
            dispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun successfulRunRecordsAndCleansBeforeFinishing() = runBlocking {
        val run = Run()
        run.execute()
        assertEquals(listOf("execute", "record:7", "cleanup", "release", "finished"), run.events)
        assertEquals(7, run.code)
        assertTrue(run.failures.isEmpty())
    }

    @Test
    fun shellExceptionRecordsFailureAndUnlocksPage() = runBlocking {
        val run = Run()
        run.execute(failAt = "execute")
        assertEquals(listOf("execute", "record:-1", "cleanup", "release", "finished"), run.events)
        assertEquals(-1, run.code)
        assertEquals(1, run.failures.size)
    }

    @Test
    fun historyCleanupOrReleaseFailureCannotLeavePageRunning() = runBlocking {
        for (step in listOf("record", "cleanup", "release")) {
            val run = Run()
            run.execute(failAt = step)
            assertEquals(listOf("execute", "record:7", "cleanup", "release", "finished"), run.events)
            assertEquals(7, run.code)
            assertEquals(1, run.failures.size)
        }
    }

    @Test
    fun cancellationStillCleansAndFinishesButIsNotSwallowed() = runBlocking {
        val run = Run()
        var cancelled = false
        try {
            run.execute(cancellation = true)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(listOf("execute", "record:-1", "cleanup", "release", "finished"), run.events)
        assertEquals(-1, run.code)
    }

    @Test
    fun alreadyCancelledScopeStillReleasesAnUndispatchedRun() = runBlocking {
        val run = Run()
        val parent = Job().apply { cancel() }
        CoroutineScope(coroutineContext + parent).launch(start = CoroutineStart.UNDISPATCHED) { run.execute() }.join()
        assertEquals(listOf("record:-1", "cleanup", "release", "finished"), run.events)
        assertEquals(-1, run.code)
    }

    @Test
    fun brokenErrorReporterCannotPreventRemainingCleanup() = runBlocking {
        val events = mutableListOf<String>()
        executeScriptLifecycle(
            execute = { error("shell failed") },
            record = { events += "record:$it"; error("storage failed") },
            cleanup = { events += "cleanup"; error("remove failed") },
            release = { events += "release" },
            finished = { events += "finished:$it" },
            onFailure = { error("report failed") },
            dispatcher = Dispatchers.Unconfined,
        )
        assertEquals(listOf("record:-1", "cleanup", "release", "finished:-1"), events)
    }
}
