package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.*
import org.junit.Test

class SuiModelsTest {
    private fun request(code: Int = 1, connection: Long = 1, at: Long = 10) =
        SuiRequest(connection, 10123, 1234, "com.example.client", code, at)

    @Test fun unknownAndCombinedFlagsNeverLookAllowed() {
        SuiPermission.entries.forEach { assertEquals(it, SuiPermission.fromFlags(it.flag)) }
        listOf(-1, 1, 3, 6, 10, 18, 30, 32).forEach { assertEquals(SuiPermission.DENY, SuiPermission.fromFlags(it)) }
    }

    @Test fun explicitDecisionsKeepPrivilegeAndPersistenceSeparate() {
        assertTrue(SuiDecision.ROOT.allowed)
        assertFalse(SuiDecision.ROOT.shell)
        assertFalse(SuiDecision.ROOT.once)
        assertTrue(SuiDecision.SHELL.allowed && SuiDecision.SHELL.shell && !SuiDecision.SHELL.once)
        assertTrue(SuiDecision.ROOT_ONCE.allowed && SuiDecision.ROOT_ONCE.once && !SuiDecision.ROOT_ONCE.shell)
        assertFalse(SuiDecision.entries.any { it.shell && it.once })
        assertFalse(SuiDecision.DENY.allowed || SuiDecision.DENY_ONCE.allowed)
    }

    @Test fun queueBoundsAndDuplicatesDoNotOverwriteEarlierPrompts() {
        val queue = SuiRequestQueue(capacity = 2)
        assertTrue(queue.offer(request()))
        assertTrue(queue.offer(request()))
        assertEquals(1, queue.snapshot().size)
        assertTrue(queue.offer(request(2)))
        assertFalse(queue.offer(request(3)))
        assertEquals(listOf(request(), request(2)), queue.snapshot())
    }

    @Test fun epochsPreventReusingADeadConnectionDecision() {
        val queue = SuiRequestQueue()
        assertTrue(queue.offer(request(connection = 1)))
        assertTrue(queue.offer(request(connection = 2)))
        assertTrue(queue.remove(request(connection = 1)))
        assertFalse(queue.remove(request(connection = 1)))
        assertEquals(listOf(request(connection = 2)), queue.clear())
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test fun timeoutReturnsRequestsForExplicitDenialAndKeepsYoungerRequests() {
        val queue = SuiRequestQueue(timeoutMillis = 60_000)
        queue.offer(request(at = 0))
        queue.offer(request(2, at = 1_000))
        assertTrue(queue.expire(59_999).isEmpty())
        assertEquals(listOf(request(at = 0)), queue.expire(60_000))
        assertEquals(listOf(request(at = 0), request(2, at = 1_000)), queue.snapshot())
        assertEquals(listOf(request(at = 0)), queue.expire(60_000))
        assertTrue(queue.respond(request(at = 0)) { beforeDispatch -> beforeDispatch() })
        assertEquals(listOf(request(2, at = 1_000)), queue.snapshot())
        assertTrue(queue.expire(60_000).isEmpty())
    }

    @Test fun invalidRequestIdentityIsNeverQueued() {
        val queue = SuiRequestQueue()
        listOf(request().copy(uid = -1), request().copy(pid = 0), request().copy(packageName = ""),
            request().copy(packageName = "../system"), request().copy(packageName = "a\nb"),
            request().copy(packageName = "a".repeat(256))).forEach { assertFalse(queue.offer(it)) }
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test fun dispatchDeadlineAlsoRejectsDelayedAndClockInvalidRequests() {
        assertFalse(request(at = 10).expired(60_009))
        assertTrue(request(at = 10).expired(60_010))
        assertTrue(request(at = 10).expired(100_000))
        assertTrue(request(at = 10).expired(9))
        assertTrue(request(at = -1).expired(0))
    }

    @Test fun brokerHealthIsNotAStatementThatTheUiHostIsAttached() {
        val status = SuiStatus.parse("""{"schema_version":1,"state":"enabled","payload_available":true,
            "payload_version":"13.5.4.3","runtime":{"desired_enabled":true,"service_healthy":true}}""")
        assertTrue(status.serviceHealthy && status.desiredEnabled)
        assertFalse(SuiUiState(status = status).hostAttached)
    }

    @Test fun unsupportedOrOversizedDaemonResponsesAreRejected() {
        listOf("{}", """{"schema_version":2}""", """{"schema_version":1,"state":"healthy"}""",
            " ".repeat(128 * 1024 + 1)).forEach { text -> assertThrows(Exception::class.java) { SuiStatus.parse(text) } }
    }
}
