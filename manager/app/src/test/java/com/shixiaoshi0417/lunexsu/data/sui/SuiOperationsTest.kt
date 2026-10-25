package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SuiOperationsTest {
    @Test fun lateEnableIsUncertainUntilCompletionAndAuthoritativeRead() {
        val command = CompletableFuture<Unit>()
        val lifecycle = SuiLifecycleCommand()
        val reads = AtomicInteger()
        lifecycle.retain(command)
        assertNull(lifecycle.reconcile { reads.incrementAndGet() })
        assertEquals(0, reads.get())
        assertTrue(lifecycle.isPending())
        assertFalse(command.isCancelled)
        assertThrows(IllegalStateException::class.java) { lifecycle.retain(CompletableFuture<Unit>()) }
        command.complete(Unit) // The daemon finishes enabling after the UI's original deadline.
        assertEquals("pending_reboot", lifecycle.reconcile { "pending_reboot" })
        assertFalse(lifecycle.isPending())
    }

    @Test fun completedErrorStillRequiresStatusAndReadFailureKeepsUncertainty() {
        val lifecycle = SuiLifecycleCommand()
        val command = CompletableFuture<Unit>()
        lifecycle.retain(command)
        command.completeExceptionally(IllegalStateException("failure after a partial mutation"))
        assertThrows(IllegalStateException::class.java) { lifecycle.reconcile<Unit> { error("status unavailable") } }
        assertTrue(lifecycle.isPending())
        assertEquals("disabled", lifecycle.reconcile { "disabled" })
        assertFalse(lifecycle.isPending())
    }

    @Test fun completingFutureAloneDoesNotClearUnknownOutcome() {
        val lifecycle = SuiLifecycleCommand()
        lifecycle.retain(CompletableFuture.completedFuture(Unit))
        assertTrue(lifecycle.isPending())
        assertEquals(false, lifecycle.reconcile { false })
        assertFalse(lifecycle.isPending())
        assertNull(lifecycle.reconcile { error("no second read without a pending command") })
    }

    private fun waitIgnoringInterrupts(latch: CountDownLatch) {
        while (true) try { latch.await(); return } catch (_: InterruptedException) { /* Like blocked Binder. */ }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(condition())
    }

    @Test fun hungWorkerIsReplacedWithoutWaitingForOldBinder() {
        val executor = SuiIpcExecutor(timeoutMillis = 250)
        val release = CountDownLatch(1)
        val oldExited = CountDownLatch(1)
        val stalePublished = AtomicBoolean()
        try {
            assertThrows(TimeoutException::class.java) {
                executor.call { lease ->
                    try {
                        waitIgnoringInterrupts(release)
                        lease.checkActive() // A late connect/grant must not publish from the old generation.
                        stalePublished.set(true)
                    } finally { oldExited.countDown() }
                }
            }
            assertEquals("new connection", executor.call { "new connection" })
            release.countDown()
            assertTrue(oldExited.await(3, TimeUnit.SECONDS))
            assertFalse(stalePublished.get())
            assertEquals("permission reply", executor.call { "permission reply" })
        } finally { release.countDown(); executor.close() }
    }

    @Test fun unresponsiveGenerationsAreCappedAndRecoveredWorkersReleaseCapacity() {
        val executor = SuiIpcExecutor(timeoutMillis = 150, maxWorkers = 2)
        val release = CountDownLatch(1)
        try {
            repeat(2) {
                assertThrows(TimeoutException::class.java) { executor.call { waitIgnoringInterrupts(release) } }
            }
            assertEquals(2, executor.workerCount())
            val executed = AtomicBoolean()
            assertThrows(IllegalStateException::class.java) { executor.call { executed.set(true) } }
            assertFalse(executed.get())
            release.countDown()
            eventually { executor.workerCount() == 0 }
            assertEquals(42, executor.call { 42 })
        } finally { release.countDown(); executor.close() }
    }

    @Test fun busyWorkerNeverQueuesCallsAndOrdinaryErrorsDoNotRetireIt() {
        val executor = SuiIpcExecutor(timeoutMillis = 3_000)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = CompletableFuture<Throwable?>()
        val caller = Thread {
            try { executor.call { started.countDown(); release.await() }; failure.complete(null) }
            catch (error: Throwable) { failure.complete(error) }
        }
        try {
            caller.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { executor.call { error("must not queue") } }
            release.countDown()
            assertNull(failure.get(3, TimeUnit.SECONDS))
            assertThrows(ExecutionException::class.java) { executor.call<Unit> { error("ordinary remote failure") } }
            assertEquals(1, executor.workerCount())
            assertEquals("reused", executor.call { "reused" })
        } finally { release.countDown(); caller.join(3_000); executor.close() }
    }
}
