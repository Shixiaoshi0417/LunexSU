package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.*
import org.junit.Test

class SuiModelsTest {
    @Test fun unknownAndCombinedFlagsNeverLookAllowed() {
        SuiPermission.entries.forEach { assertEquals(it, SuiPermission.fromFlags(it.flag)) }
        listOf(-1, 1, 3, 6, 10, 18, 30, 32).forEach { assertEquals(SuiPermission.DENY, SuiPermission.fromFlags(it)) }
    }

    @Test fun brokerHealthIsNotAStatementThatTheManagerIsConnected() {
        val status = SuiStatus.parse("""{"schema_version":1,"state":"enabled","payload_available":true,
            "payload_version":"13.5.4.3","runtime":{"desired_enabled":true,"service_healthy":true}}""")
        assertTrue(status.serviceHealthy && status.desiredEnabled)
        assertFalse(SuiUiState(status = status).connected)
    }

    @Test fun unsupportedOrOversizedDaemonResponsesAreRejected() {
        listOf("{}", """{"schema_version":2}""", """{"schema_version":1,"state":"healthy"}""",
            " ".repeat(128 * 1024 + 1)).forEach { text -> assertThrows(Exception::class.java) { SuiStatus.parse(text) } }
    }
}
