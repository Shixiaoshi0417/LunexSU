package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SuiRequestAdmissionTest {
    private val request = SuiRequest(1, 10123, 1234, "com.example.client", 1, 0, "verified")

    @Test fun everyDuplicateCallbackIsRejectedWithoutLosingTheOriginal() {
        val queue = SuiRequestQueue()
        queue.requireOffer(request)
        repeat(32) {
            assertThrows(IllegalStateException::class.java) { queue.requireOffer(request.copy(receivedAt = it.toLong())) }
        }
        assertEquals(listOf(request), queue.snapshot())
        val sent = AtomicInteger()
        assertTrue(queue.respond(request) { beforeDispatch -> beforeDispatch(); sent.incrementAndGet() })
        assertEquals(1, sent.get())
        assertFalse(queue.respond(request) { error("original already consumed") })
        queue.requireOffer(request.copy(receivedAt = 100))
        assertEquals(1, queue.snapshot().size) // Same key can be requested again after completion.
    }

    @Test fun duplicateDuringAnUnfinishedSentReplyIsRejectedIndependently() {
        val queue = SuiRequestQueue()
        queue.requireOffer(request)
        assertThrows(IllegalStateException::class.java) {
            queue.respond(request) { beforeDispatch ->
                beforeDispatch()
                assertThrows(IllegalStateException::class.java) { queue.requireOffer(request.copy()) }
                error("RPC may already have been sent")
            }
        }
        assertTrue(queue.snapshot().isEmpty())
        assertFalse(queue.respond(request) { error("never retry a possibly sent grant") })
    }

    @Test fun fullAndUnverifiedCallbacksRejectWhileTheIpcWorkerIsBusy() {
        val queue = SuiRequestQueue(capacity = 1)
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
            queue.requireOffer(request)
            assertThrows(IllegalStateException::class.java) {
                queue.requireOffer(request.copy(requestCode = 2))
            }
            assertThrows(IllegalStateException::class.java) {
                queue.requireOffer(request.copy(requestCode = 3, identity = ""))
            }
            assertEquals(1L, release.count) // Rejection did not need the occupied IPC worker.
            assertThrows(IllegalStateException::class.java) {
                queue.respond(request) { beforeDispatch -> executor.call { beforeDispatch() } }
            }
            assertEquals(listOf(request), queue.snapshot())
            release.countDown()
            finished.get(3, TimeUnit.SECONDS)
            assertTrue(queue.respond(request) { beforeDispatch -> executor.call { beforeDispatch() } })
            assertTrue(queue.snapshot().isEmpty())
        } finally { release.countDown(); caller.join(3_000); executor.close() }
    }

    @Test fun rejectedTrafficIsBoundedAndDoesNotNeedASeparateDenialQueue() {
        val queue = SuiRequestQueue(capacity = 2)
        val other = request.copy(requestCode = 2)
        queue.requireOffer(request)
        queue.requireOffer(other)
        repeat(1_000) {
            assertThrows(IllegalStateException::class.java) { queue.requireOffer(request.copy(requestCode = 3 + it)) }
            assertThrows(IllegalStateException::class.java) { queue.requireOffer(request.copy()) }
        }
        assertEquals(listOf(request, other), queue.snapshot())
        assertEquals(listOf(request, other), queue.clear())
        assertTrue(queue.expire(60_000).isEmpty())
        queue.requireOffer(request.copy(connection = 2))
        assertEquals(1, queue.snapshot().size)
    }

    @Test fun unverifiedAndInvalidRequestsCannotReachAGrant() {
        val queue = SuiRequestQueue()
        val invalid = listOf(request.copy(identity = ""), request.copy(uid = -1), request.copy(pid = 0),
            request.copy(packageName = "../outside"))
        invalid.forEach {
            assertThrows(IllegalStateException::class.java) { queue.requireOffer(it) }
            assertFalse(queue.respond(it) { error("rejected request must not be granted") })
        }
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test fun unsentFailureRetainsOnlyTheOriginalAndItsExpiryDenial() {
        val queue = SuiRequestQueue()
        queue.requireOffer(request)
        assertThrows(IllegalStateException::class.java) {
            queue.respond(request) { error("failure before dispatch") }
        }
        assertThrows(IllegalStateException::class.java) { queue.requireOffer(request.copy()) }
        assertEquals(listOf(request), queue.expire(60_000))
        assertTrue(queue.respond(request) { beforeDispatch -> beforeDispatch() })
        assertTrue(queue.expire(61_000).isEmpty())
    }
}
