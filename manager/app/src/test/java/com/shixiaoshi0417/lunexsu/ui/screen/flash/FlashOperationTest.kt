package com.shixiaoshi0417.lunexsu.ui.screen.flash

import com.shixiaoshi0417.lunexsu.ui.util.FlashResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FlashOperationTest {
    @Test fun rebindingBeforeAndAfterOutputRetainsOneExecutionAndCompletion() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val firstOutput = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val operation = FlashOperation(scope)
            var calls = 0
            operation.start(false) { out, err ->
                calls++
                firstOutput.await()
                out("installing")
                err("stderr detail")
                finish.await()
                FlashResult(0, "", true)
            }
            operation.start(true) { _, _ -> error("Replayed before output") }
            assertEquals(1, calls)
            assertEquals("", operation.state.value.text)
            firstOutput.complete(Unit)
            operation.start(true) { _, _ -> error("Replayed after output") }
            assertEquals("installing\n", operation.state.value.text)
            finish.complete(Unit)
            assertEquals(FlashingStatus.SUCCESS, operation.state.value.status)
            assertTrue(operation.state.value.showReboot)
            assertEquals("installing\nstderr detail\n", operation.logSnapshot())
            operation.start(true) { _, _ -> error("Replayed completed operation") }
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun restoredProcessWithNoOwnerNeverReplaysUnknownOperation() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val operation = FlashOperation(scope)
            repeat(2) { operation.start(true) { _, _ -> error("Unknown operation was retried") } }
            assertEquals(FlashingStatus.FAILED, operation.state.value.status)
            assertFalse(operation.state.value.showReboot)
            assertTrue(operation.state.value.text.contains("result is unknown"))
            assertTrue(operation.logSnapshot().contains("not be retried"))
        } finally { scope.cancel() }
    }

    @Test fun aNewVisitCanExecuteTheSameRequestAgain() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var calls = 0
            repeat(2) {
                val visit = FlashOperation(scope)
                visit.start(false) { _, _ -> calls++; FlashResult(0, "", false) }
                assertEquals(FlashingStatus.SUCCESS, visit.state.value.status)
            }
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun clearOnlyClearsDisplayAndFailureRetainsRebootAndFullLog() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val operation = FlashOperation(scope)
            operation.start(false) { out, err ->
                out("old output")
                out("\u001b[H\u001b[Jnew output")
                err("error detail")
                FlashResult(7, "failure", true)
            }
            assertFalse(operation.state.value.text.contains("old output"))
            assertTrue(operation.state.value.text.startsWith("new output\n"))
            assertEquals(FlashingStatus.FAILED, operation.state.value.status)
            assertTrue(operation.state.value.showReboot)
            assertTrue(operation.logSnapshot().contains("old output"))
            assertTrue(operation.logSnapshot().contains("error detail"))
            assertTrue(operation.logSnapshot().contains("Error code: 7"))
        } finally { scope.cancel() }
    }

    @Test fun exceptionIsTerminalAndNeverRetried() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val operation = FlashOperation(scope)
            operation.start(false) { _, _ -> throw IllegalStateException("lost response") }
            operation.start(false) { _, _ -> error("Retried after exception") }
            assertEquals(FlashingStatus.FAILED, operation.state.value.status)
            assertTrue(operation.logSnapshot().contains("lost response"))
        } finally { scope.cancel() }
    }

    @Test fun ownerCancellationDoesNotLeaveAnUnfinishableSpinner() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val operation = FlashOperation(scope)
        operation.start(false) { _, _ -> CompletableDeferred<FlashResult>().await() }
        scope.cancel()
        assertEquals(FlashingStatus.FAILED, operation.state.value.status)
        operation.start(false) { _, _ -> error("Cancelled operation replayed") }
    }

    @Test fun leavingReleasesFinishedLogsWithoutPermittingReplay() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            val operation = FlashOperation(scope)
            operation.start(false) { out, _ -> out("log"); FlashResult(0, "", false) }
            operation.releaseFinishedLog()
            assertEquals("", operation.logSnapshot())
            assertEquals("", operation.state.value.text)
            assertEquals(FlashingStatus.SUCCESS, operation.state.value.status)
            operation.start(false) { _, _ -> error("Released visit replayed") }
        } finally { scope.cancel() }
    }
}
