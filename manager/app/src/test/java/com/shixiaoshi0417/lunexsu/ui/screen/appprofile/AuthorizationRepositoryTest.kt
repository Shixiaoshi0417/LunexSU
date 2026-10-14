package com.shixiaoshi0417.lunexsu.ui.screen.appprofile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import com.shixiaoshi0417.lunexsu.Natives
import org.junit.Assert.*
import org.junit.Test

class AuthorizationRepositoryTest {
    private val permanent = Natives.Profile("bin.mt.plus", 10387, allowSu = true,
        authorizationState = 1, authorizationGeneration = 7, authorizationSupported = true)

    @Test fun oncePreservesUidGenerationAndRequiresExplicitGrant() {
        val once = requireNotNull(authorizationGrant(permanent, 1, "invalid"))
        assertEquals(10387, once.currentUid)
        assertEquals(7L, once.authorizationGeneration)
        assertEquals(1, once.authorizationMode)
        assertEquals(1, once.authorizationOperation)
        assertEquals(0L, once.authorizationDurationSeconds)
    }

    @Test fun timedPresetsCustomBoundsAndMultiUser() {
        for (minutes in listOf("5", "15", "60", "123", "525600")) {
            val timed = requireNotNull(authorizationGrant(permanent.copy(currentUid = 110387), 2, minutes))
            assertEquals(110387, timed.currentUid)
            assertEquals(minutes.toLong() * 60, timed.authorizationDurationSeconds)
        }
        for (minutes in listOf("", "0", "-1", "525601", "9223372036854775807", "abc"))
            assertNull(authorizationGrant(permanent, 2, minutes))
        assertNull(authorizationGrant(permanent.copy(currentUid = 1000), 1, "5"))
    }

    @Test fun onlyAuthorizationCoresPollTheProfilePage() {
        assertTrue(shouldPollAuthorization(false, permanent))
        assertFalse(shouldPollAuthorization(true, permanent))
        // A legacy Core has no Once credit or Timed countdown that could change.
        assertFalse(shouldPollAuthorization(false, permanent.copy(authorizationSupported = false)))
    }

    @Test fun unsupportedCoreNeverWritesTemporaryOrRetriesPermanent() = runBlocking {
        var writes = 0
        val legacy = permanent.copy(authorizationSupported = false)
        val repository = AuthorizationRepository({ legacy }, { writes++; 0 })
        assertNull(authorizationGrant(legacy, 1, "5"))
        assertEquals(-95, repository.save(legacy.copy(authorizationMode = 1)).error)
        assertEquals(0, writes)
        assertEquals(0, repository.save(legacy).error)
        assertEquals(1, writes)
    }

    @Test fun rejectedSaveReloadsActualPolicyAndDoesNotRetry() = runBlocking {
        for (error in listOf(-11, -16, -2, -116, -5, -95)) {
            var writes = 0
            val repository = AuthorizationRepository({ permanent }, { writes++; error })
            val result = repository.save(requireNotNull(authorizationGrant(permanent, 1, "5")))
            assertEquals(error, result.error)
            assertEquals(permanent, result.profile)
            assertEquals(1, writes)
        }
    }

    @Test fun successfulSaveReloadsGenerationAndKeepCannotRearmConsumedOnce() = runBlocking {
        val once = requireNotNull(authorizationGrant(permanent, 1, "5"))
        val saved = once.copy(authorizationOperation = 0, authorizationGeneration = 8, authorizationState = 0)
        var written: Natives.Profile? = null
        val repository = AuthorizationRepository({ saved }, { written = it; 0 })
        assertEquals(saved, repository.save(once).profile)
        repository.save(saved)
        assertEquals(0, written?.authorizationOperation)
        assertEquals(1, written?.authorizationMode)
    }

    @Test fun successfulIoctlWithWrongReadbackIsNeverAcknowledgedOrRetried() = runBlocking {
        val once = requireNotNull(authorizationGrant(permanent, 1, "5"))
        val timed = requireNotNull(authorizationGrant(permanent, 2, "15"))
        for ((request, snapshot) in listOf(
            once to permanent,
            timed to timed.copy(authorizationDurationSeconds = 300),
            permanent.copy(allowSu = false) to permanent,
        )) {
            var writes = 0
            val repository = AuthorizationRepository({ snapshot }, { writes++; 0 })
            assertEquals(-71, repository.save(request).error)
            assertEquals(1, writes)
        }
    }

    @Test fun revokeKeepsModeAndReadFailureIsExplicit() = runBlocking {
        val timed = requireNotNull(authorizationGrant(permanent, 2, "15"))
        val revoked = timed.copy(allowSu = false, authorizationOperation = 0)
        val unreadable = revoked.copy(authorizationReadError = -5, authorizationMode = -1)
        var writes = 0
        val repository = AuthorizationRepository({ unreadable }, { writes++; 0 })
        val result = repository.save(revoked)
        assertEquals(-5, result.error)
        assertEquals(-1, result.profile.authorizationMode)
        assertNull(authorizationGrant(unreadable, 0, "5"))
        assertEquals(-5, repository.save(unreadable).error)
        assertEquals(1, writes)
    }

    @Test fun pollingCannotReadBetweenSaveAndReadback() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val repository = AuthorizationRepository(
            { calls += "read"; permanent },
            { calls += "write"; entered.complete(Unit); release.await(); 0 },
        )
        val save = async { repository.save(permanent) }
        entered.await()
        val poll = async { repository.refresh() }
        yield()
        assertEquals(listOf("write"), calls)
        release.complete(Unit)
        save.await()
        poll.await()
        assertEquals(listOf("write", "read", "read"), calls)
    }
}
