package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** A local disconnect must not strand requests the still-live server keeps counting as pending. */
class SuiRequestRetentionTest {
    private val server = Any()
    private val otherServer = Any()
    private fun request(code: Int, connection: Long = 1) =
        SuiRequest(connection, 10123, 1234, "com.example.client", code, 0, "verified")

    @Test fun requestsSurviveATimeoutReconnectToTheSameServer() {
        val queue = SuiRequestQueue()
        queue.requireOffer(request(1))
        queue.requireOffer(request(2))
        queue.detach(server)
        assertSame(server, queue.retained())
        assertEquals(2, queue.snapshot().size)

        val moved = queue.resume(server, connection = 2)
        assertEquals(listOf(request(1, 2), request(2, 2)), moved)
        assertEquals(moved, queue.snapshot())
        assertNull(queue.retained())
        // The moved request is answerable exactly once on the new connection.
        val sent = AtomicInteger()
        assertTrue(queue.respond(moved[0]) { beforeDispatch -> beforeDispatch(); sent.incrementAndGet() })
        assertEquals(1, sent.get())
        assertEquals(listOf(request(2, 2)), queue.snapshot())
    }

    @Test fun movedRequestsStayDeniableOnExpiry() {
        val queue = SuiRequestQueue(timeoutMillis = 60_000)
        queue.requireOffer(request(1))
        queue.detach(server)
        queue.resume(server, connection = 2)
        assertEquals(listOf(request(1, 2)), queue.expire(now = 60_000))
    }

    @Test fun aDifferentServerInstanceNeverSawTheRetainedRequests() {
        val queue = SuiRequestQueue()
        queue.requireOffer(request(1))
        queue.detach(server)
        assertTrue(queue.resume(otherServer, connection = 2).isEmpty())
        assertTrue(queue.snapshot().isEmpty())
        assertNull(queue.retained())
    }

    @Test fun onlyTheRetainedServersDeathDropsTheRequests() {
        val queue = SuiRequestQueue()
        queue.requireOffer(request(1))
        queue.detach(server)
        assertFalse(queue.dropRetained(otherServer))
        assertEquals(1, queue.snapshot().size)
        assertTrue(queue.dropRetained(server))
        assertTrue(queue.snapshot().isEmpty())
        assertNull(queue.retained())
    }

    @Test fun anInFlightAttemptCannotDispatchThroughTheDroppedConnection() {
        val queue = SuiRequestQueue()
        val original = request(1)
        queue.requireOffer(original)
        assertThrows(IllegalStateException::class.java) {
            queue.respond(original) { beforeDispatch ->
                queue.detach(server) // the IPC timeout lands while this attempt is still unsent
                beforeDispatch()
                fail("dispatched through an abandoned connection")
            }
        }
        val moved = queue.resume(server, connection = 2)
        assertEquals(listOf(request(1, 2)), moved)
        assertTrue(queue.respond(moved[0]) { beforeDispatch -> beforeDispatch() })
    }

    @Test fun aSentReplyIsNeverRetainedOrReplayed() {
        val queue = SuiRequestQueue()
        val original = request(1)
        queue.requireOffer(original)
        assertThrows(IllegalStateException::class.java) {
            queue.respond(original) { beforeDispatch ->
                beforeDispatch()
                queue.detach(server) // the reply RPC itself timed out after it may have been sent
                error("timeout")
            }
        }
        assertTrue(queue.resume(server, connection = 2).isEmpty())
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test fun duplicatesOfAMovedRequestAreStillRejected() {
        val queue = SuiRequestQueue()
        queue.requireOffer(request(1))
        queue.detach(server)
        queue.resume(server, connection = 2)
        assertThrows(IllegalStateException::class.java) { queue.requireOffer(request(1, 2)) }
        assertEquals(1, queue.snapshot().size)
    }

    @Test fun clearForgetsTheRetainedServer() {
        val queue = SuiRequestQueue()
        queue.requireOffer(request(1))
        queue.detach(server)
        queue.clear()
        assertNull(queue.retained())
        assertTrue(queue.resume(server, connection = 2).isEmpty())
    }
}
