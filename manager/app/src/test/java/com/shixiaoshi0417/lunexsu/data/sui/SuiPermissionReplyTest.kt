package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class SuiPermissionReplyTest {
    private val request = SuiRequest(1, 10123, 1234, "com.example.client", 1, 0)

    private fun withBusyWorker(test: (SuiIpcExecutor, () -> Unit) -> Unit) {
        val executor = SuiIpcExecutor(timeoutMillis = 3_000)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableFuture<Unit>()
        val caller = Thread {
            try {
                executor.call { started.countDown(); release.await() }
                finished.complete(Unit)
            } catch (error: Throwable) { finished.completeExceptionally(error) }
        }
        try {
            caller.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            test(executor) { release.countDown(); finished.get(3, TimeUnit.SECONDS) }
        } finally { release.countDown(); caller.join(3_000); executor.close() }
    }

    @Test fun busyWorkerRetainsUnsentDecisionWithoutQueuingOrGrantingLater() {
        val queue = SuiRequestQueue()
        queue.offer(request)
        val sent = AtomicInteger()
        withBusyWorker { executor, release ->
            assertThrows(IllegalStateException::class.java) {
                queue.respond(request) { beforeDispatch ->
                    executor.call { beforeDispatch(); sent.incrementAndGet() }
                }
            }
            assertEquals(listOf(request), queue.snapshot())
            release()
            assertEquals(0, sent.get()) // No hidden queued grant after the busy call finishes.
            assertTrue(queue.respond(request) { beforeDispatch ->
                executor.call { beforeDispatch(); sent.incrementAndGet() }
            })
            assertEquals(1, sent.get())
            assertTrue(queue.snapshot().isEmpty())
        }
    }

    @Test fun busyExpiryDenialRemainsEligibleUntilDispatch() {
        val queue = SuiRequestQueue()
        queue.offer(request)
        val denied = AtomicInteger()
        withBusyWorker { executor, release ->
            assertEquals(listOf(request), queue.expire(60_000))
            assertThrows(IllegalStateException::class.java) {
                queue.respond(request) { beforeDispatch ->
                    executor.call { beforeDispatch(); denied.incrementAndGet() }
                }
            }
            assertEquals(listOf(request), queue.expire(61_000))
            release()
            queue.expire(62_000).forEach {
                queue.respond(it) { beforeDispatch -> executor.call { beforeDispatch(); denied.incrementAndGet() } }
            }
            assertEquals(1, denied.get())
            assertTrue(queue.expire(63_000).isEmpty())
        }
    }

    @Test fun acceptedWorkerPreflightFailureRestoresPromptAndExpiry() {
        val queue = SuiRequestQueue()
        val executor = SuiIpcExecutor()
        queue.offer(request)
        try {
            assertThrows(ExecutionException::class.java) {
                queue.respond(request) {
                    executor.call<Unit> { error("Application identity changed before dispatch") }
                }
            }
            assertEquals(listOf(request), queue.snapshot())
            assertEquals(listOf(request), queue.expire(60_000))
            assertTrue(queue.respond(request) { beforeDispatch -> executor.call { beforeDispatch() } })
            assertTrue(queue.snapshot().isEmpty())
        } finally { executor.close() }
    }

    @Test fun possiblySentFailureIsConsumedAndNeverRetried() {
        val queue = SuiRequestQueue()
        val executor = SuiIpcExecutor()
        queue.offer(request)
        val sent = AtomicInteger()
        try {
            assertThrows(ExecutionException::class.java) {
                queue.respond(request) { beforeDispatch ->
                    executor.call<Unit> { beforeDispatch(); sent.incrementAndGet(); error("RPC outcome unknown") }
                }
            }
            assertFalse(queue.respond(request) { error("Must not retry a possibly sent RPC") })
            assertTrue(queue.snapshot().isEmpty())
            assertTrue(queue.expire(60_000).isEmpty())
            assertEquals(1, sent.get())
        } finally { executor.close() }
    }

    @Test fun possiblySentTimeoutCannotTriggerAnotherReply() {
        val queue = SuiRequestQueue()
        val executor = SuiIpcExecutor(timeoutMillis = 150)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val sent = AtomicInteger()
        queue.offer(request)
        try {
            assertThrows(TimeoutException::class.java) {
                queue.respond(request) { beforeDispatch ->
                    executor.call {
                        beforeDispatch()
                        sent.incrementAndGet()
                        try {
                            while (true) try { release.await(); break } catch (_: InterruptedException) { }
                        } finally { exited.countDown() }
                    }
                }
            }
            assertFalse(queue.respond(request) { error("Must not retry a timed-out RPC") })
            assertTrue(queue.expire(60_000).isEmpty())
            release.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertEquals(1, sent.get())
        } finally { release.countDown(); executor.close() }
    }

    @Test fun activeAttemptExcludesDuplicateClicksExpiryAndOverflow() {
        val queue = SuiRequestQueue(capacity = 1)
        queue.offer(request)
        queue.respond(request) { beforeDispatch ->
            assertTrue(queue.snapshot().isEmpty())
            assertTrue(queue.expire(60_000).isEmpty())
            assertFalse(queue.respond(request) { error("duplicate click") })
            assertFalse(queue.offer(request.copy(requestCode = 2)))
            beforeDispatch()
            assertFalse(queue.offer(request)) // Duplicate callbacks must be explicitly rejected.
            assertTrue(queue.snapshot().isEmpty())
            assertFalse(queue.respond(request) { error("duplicate callback") })
        }
        assertFalse(queue.respond(request) { error("already consumed") })
    }

    @Test fun unsentAbandonedWorkerCannotDispatchDuringANewerAttempt() {
        val queue = SuiRequestQueue()
        queue.offer(request)
        lateinit var lateDispatch: () -> Unit
        assertThrows(TimeoutException::class.java) {
            queue.respond(request) { beforeDispatch ->
                lateDispatch = beforeDispatch
                throw TimeoutException("before dispatch")
            }
        }
        assertEquals(listOf(request), queue.snapshot())
        queue.respond(request) { beforeDispatch ->
            assertThrows(IllegalStateException::class.java) { lateDispatch() }
            beforeDispatch()
            assertThrows(IllegalStateException::class.java) { beforeDispatch() }
        }
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test fun disconnectInvalidatesClaimAndCannotResurrectAnOldRequest() {
        val queue = SuiRequestQueue()
        queue.offer(request)
        queue.respond(request) { beforeDispatch ->
            queue.clear()
            assertTrue(queue.offer(request.copy(connection = 2)))
            assertThrows(IllegalStateException::class.java) { beforeDispatch() }
        }
        assertEquals(listOf(request.copy(connection = 2)), queue.snapshot())
        assertFalse(queue.respond(request) { error("old connection") })
    }
}
