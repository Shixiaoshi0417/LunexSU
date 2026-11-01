package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.*
import org.junit.Test

class SuiZygiskStatusTest {
    private fun parse(zygisk: String?) = SuiStatus.parse(
        """{"schema_version":1,"state":"disabled","payload_available":true,
            "runtime":{"desired_enabled":true},"zygisk":${zygisk ?: "null"}}""",
    )

    @Test fun knownExternalIdsAreAcceptedWithoutAnOwnershipClaim() {
        for (id in listOf("rezygisk", "zygisksu")) {
            val status = parse("""{"state":"ready","providers":["$id"]}""")
            assertTrue(status.zygisk.ready)
            assertEquals(listOf(id), status.zygisk.providers)
        }
    }

    @Test fun aDisabledOtherProviderCanRemainInTheInventory() {
        assertTrue(parse("""{"state":"ready","providers":["rezygisk","zygisksu"]}""").zygisk.ready)
    }

    @Test fun nonReadyStatesNeverAllowEnable() {
        for (state in listOf("missing", "pending_reboot", "conflict", "unavailable")) {
            assertFalse(parse("""{"state":"$state","providers":["rezygisk"]}""").zygisk.ready)
        }
    }

    @Test fun unknownOrMalformedPrerequisitesFailClosedWithoutBlockingDisable() {
        for (value in listOf(null, "false", "[]", "{}", """{"state":"ready"}""",
            """{"state":"ready","providers":[]}""", """{"state":"ready","providers":"rezygisk"}""",
            """{"state":"ready","providers":[1]}""", """{"state":"ready","providers":[null]}""",
            """{"state":"ready","providers":["lsposed"]}""",
            """{"state":"ready","providers":["rezygisk","rezygisk"]}""",
            """{"state":"ready","providers":["rezygisk","zygisksu","sui"]}""",
            """{"state":"READY","providers":["rezygisk"]}""")) {
            val status = parse(value)
            assertFalse("unsafe prerequisite: $value", status.zygisk.ready)
            assertTrue(SuiUiState(status = status).canSwitchTo(false))
        }
    }

    @Test fun oldDaemonStatusCannotImplicitlySatisfyTheNewPrerequisite() {
        val status = SuiStatus.parse("""{"schema_version":1,"state":"disabled","payload_available":true}""")
        assertFalse(status.zygisk.ready)
        assertFalse(SuiUiState(status = status).canSwitchTo(true))
    }
}
