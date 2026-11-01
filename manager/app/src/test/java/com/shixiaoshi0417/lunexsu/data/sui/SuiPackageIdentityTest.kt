package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.*
import org.junit.Test

class SuiPackageIdentityTest {
    private val member = SuiPackageIdentity("com.example.client", 1010123, 10, 20, listOf("a".repeat(64)))

    @Test fun fingerprintMatchesTheRootServerConsentWireFormat() {
        assertEquals("com.example.client|1010123|10|20|${"a".repeat(64)}", member.fingerprint())
        assertEquals("com.example.client|1010123|10|20|a, b",
            member.copy(signers = listOf("b", "a")).fingerprint())
    }

    @Test fun reinstallUpdateUidAndSignerChangesAreIdentityChanges() {
        listOf(member.copy(firstInstallTime = 11), member.copy(lastUpdateTime = 21), member.copy(uid = 10123),
            member.copy(signers = listOf("b".repeat(64))), member.copy(signers = member.signers + "extra"))
            .forEach { assertNotEquals(member.fingerprint(), it.fingerprint()) }
    }

    @Test fun incompleteSigningInformationFailsClosed() {
        listOf(emptyList(), listOf("")).forEach { signers ->
            assertThrows(IllegalStateException::class.java) { member.copy(signers = signers).fingerprint() }
        }
    }
}
