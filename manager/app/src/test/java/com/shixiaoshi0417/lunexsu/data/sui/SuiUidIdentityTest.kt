package com.shixiaoshi0417.lunexsu.data.sui

import org.junit.Assert.*
import org.junit.Test

class SuiUidIdentityTest {
    private val uid = 10123
    private val requester = SuiPackageIdentity("com.example.client", uid, 10, 20, listOf("signer-a"))
    private val sibling = requester.copy(packageName = "com.example.sibling")

    private fun capture(packages: List<SuiPackageIdentity> = listOf(requester, sibling)): SuiUidIdentity =
        SuiUidIdentity.capture(uid, requester.packageName, { packages.map { it.packageName } },
            { name -> packages.single { it.packageName == name } })

    @Test fun consentContainsEverySharedUidPackageAndIdentity() {
        val identity = capture()
        assertEquals(listOf(requester.packageName, sibling.packageName), identity.packages)
        assertEquals(listOf(requester.fingerprint(), sibling.fingerprint()).joinToString("\n"), identity.fingerprint)
    }

    @Test fun singlePackageUidsStillWork() {
        assertEquals(listOf(requester.packageName), capture(listOf(requester)).packages)
    }

    @Test fun enumerationAndSignerOrderAreNotIdentityChanges() {
        val bothSigners = listOf(requester.copy(signers = listOf("b", "a")), sibling)
        val expected = capture(bothSigners)
        val byName = bothSigners.associateBy { it.packageName }
        assertEquals(expected, SuiUidIdentity.capture(uid, requester.packageName,
            { listOf(sibling.packageName, requester.packageName, sibling.packageName) },
            { name -> byName.getValue(name).let { it.copy(signers = it.signers.reversed()) } }))
    }

    @Test fun addingASiblingInvalidatesThePreviouslyShownConsent() {
        assertNotEquals(capture(listOf(requester)), capture())
    }

    @Test fun removingASiblingInvalidatesThePreviouslyShownConsent() {
        assertNotEquals(capture(), capture(listOf(requester)))
    }

    @Test fun replacingASiblingWithoutChangingPackageCountInvalidatesConsent() {
        assertNotEquals(capture(), capture(listOf(requester, sibling.copy(packageName = "com.example.other"))))
    }

    @Test fun reinstallUpdateAndSignerChangesOfAnyMemberInvalidateConsent() {
        val original = listOf(requester, sibling)
        original.indices.forEach { index ->
            val member = original[index]
            listOf(member.copy(firstInstallTime = 11), member.copy(lastUpdateTime = 21),
                member.copy(signers = listOf("new-signer")), member.copy(signers = listOf("signer-a", "extra")))
                .forEach { changed ->
                    val updated = original.toMutableList().apply { this[index] = changed }
                    assertNotEquals("changed member ${member.packageName}", capture(), capture(updated))
                }
        }
    }

    @Test fun absentRequesterAndEmptyUidFailClosed() {
        listOf(emptyList(), listOf(sibling)).forEach { packages ->
            assertThrows(IllegalStateException::class.java) { capture(packages) }
        }
    }

    @Test fun mismatchedUidOrPackageOfAnyMemberFailsClosed() {
        listOf(requester, sibling).forEach { member ->
            assertThrows(IllegalStateException::class.java) {
                SuiUidIdentity.capture(uid, requester.packageName, { listOf(requester.packageName, sibling.packageName) },
                    { name -> (if (name == requester.packageName) requester else sibling).let {
                        if (name == member.packageName) it.copy(uid = uid + 1) else it
                    } })
            }
        }
        assertThrows(IllegalStateException::class.java) {
            SuiUidIdentity.capture(uid, requester.packageName, { listOf(requester.packageName) }, { sibling })
        }
    }

    @Test fun unreadableSiblingCannotBeSilentlyOmitted() {
        assertThrows(IllegalStateException::class.java) {
            SuiUidIdentity.capture(uid, requester.packageName, { listOf(requester.packageName, sibling.packageName) },
                { name -> if (name == requester.packageName) requester else error("package removed or unreadable") })
        }
    }

    @Test fun incompleteSigningInformationOfAnyMemberFailsClosed() {
        listOf(emptyList(), listOf("")).forEach { signers ->
            assertThrows(IllegalStateException::class.java) {
                capture(listOf(requester, sibling.copy(signers = signers)))
            }
        }
    }

    @Test fun membershipChangesDuringCollectionFailClosed() {
        listOf(listOf(requester.packageName), listOf(requester.packageName, sibling.packageName, "com.example.new"))
            .forEach { changedNames ->
                var reads = 0
                assertThrows(IllegalStateException::class.java) {
                    SuiUidIdentity.capture(uid, requester.packageName,
                        { if (reads++ == 0) listOf(requester.packageName, sibling.packageName) else changedNames },
                        { name -> if (name == requester.packageName) requester else sibling })
                }
            }
    }

    @Test fun reconnectRetainsTheCompleteConsentSnapshot() {
        val identity = capture()
        val request = SuiRequest(1, uid, 1234, requester.packageName, 1, 0, identity.fingerprint, identity.packages)
        val queue = SuiRequestQueue()
        val server = Any()
        queue.requireOffer(request)
        queue.detach(server)
        assertEquals(listOf(request.copy(connection = 2)), queue.resume(server, 2))
        assertEquals(identity.packages, queue.snapshot().single().packages)
    }
}
