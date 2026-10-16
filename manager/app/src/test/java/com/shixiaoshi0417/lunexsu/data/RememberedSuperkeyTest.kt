package com.shixiaoshi0417.lunexsu.data

import org.junit.Assert.*
import org.junit.Test

class RememberedSuperkeyTest {
    private class Store : SuperkeyCredentialStore {
        var credential: SuperkeyCredential? = null
        var brokenRead = false
        var brokenWrite = false
        var writes = 0
        var clears = 0
        override fun exists() = credential != null
        override fun read(): SuperkeyCredential? {
            check(!brokenRead)
            return credential?.let { SuperkeyCredential(it.salt.copyOf(), it.proof.copyOf()) }
        }
        override fun write(credential: SuperkeyCredential) {
            check(!brokenWrite)
            writes++
            this.credential = SuperkeyCredential(credential.salt.copyOf(), credential.proof.copyOf())
        }
        override fun clear() { clears++; credential?.close(); credential = null }
        fun seed() { credential = SuperkeyCredential(ByteArray(16) { 7 }, ByteArray(32) { 8 }) }
    }
    private class Backend : SuperkeyAuthenticationBackend {
        var manager = false
        var currentSalt: ByteArray? = ByteArray(16) { 7 }
        var accepted = true
        var calls = 0
        var saltCalls = 0
        var lastProof: ByteArray? = null
        override fun isManager() = manager
        override fun salt(): ByteArray? { saltCalls++; return currentSalt?.copyOf() }
        override fun authenticate(proof: ByteArray): Boolean { calls++; lastProof = proof; return accepted }
    }

    @Test fun savesOnlyAfterSuccessfulManualAuthenticationAndClearsWorkingBuffers() {
        val store = Store(); val backend = Backend(); val auth = RememberedSuperkey(store, backend)
        val key = "0123456789ab".toCharArray()
        assertEquals(SuperkeyAuthenticationResult.Accepted(), auth.authenticate(key, true))
        assertEquals(1, store.writes)
        assertArrayEquals(ByteArray(16) { 7 }, store.credential!!.salt)
        assertFalse(store.credential!!.proof.all { it == 0.toByte() })
        assertTrue(key.all { it == '\u0000' })
        assertTrue(backend.lastProof!!.all { it == 0.toByte() })
    }
    @Test fun rejectedKeyIsNeverSaved() {
        val store = Store(); val backend = Backend().apply { accepted = false }
        assertEquals(SuperkeyAuthenticationResult.Rejected,
            RememberedSuperkey(store, backend).authenticate("0123456789ab".toCharArray(), true))
        assertEquals(0, store.writes); assertFalse(store.exists())
    }
    @Test fun noCoreDoesNotSaveAndClearsManualInput() {
        val store = Store(); val backend = Backend().apply { currentSalt = null }
        val chars = "0123456789ab".toCharArray()
        assertEquals(SuperkeyAuthenticationResult.Rejected, RememberedSuperkey(store, backend).authenticate(chars, true))
        assertTrue(chars.all { it == '\u0000' }); assertFalse(store.exists())
    }
    @Test fun uncheckedSuccessfulUnlockRemovesOldCredential() {
        val store = Store().apply { seed() }
        assertEquals(SuperkeyAuthenticationResult.Accepted(),
            RememberedSuperkey(store, Backend()).authenticate("0123456789ab".toCharArray(), false))
        assertFalse(store.exists()); assertEquals(1, store.clears)
    }
    @Test fun failedPersistenceDoesNotRejectSuccessfulAuthentication() {
        val store = Store().apply { seed(); brokenWrite = true }
        assertEquals(SuperkeyAuthenticationResult.Accepted(storageFailed = true),
            RememberedSuperkey(store, Backend()).authenticate("0123456789ab".toCharArray(), true))
        assertFalse(store.exists())
    }
    @Test fun automaticUnlockUsesSavedProofOnlyOncePerProcess() {
        val store = Store().apply { seed() }; val backend = Backend(); val auth = RememberedSuperkey(store, backend)
        assertEquals(SuperkeyAuthenticationResult.Accepted(), auth.tryAutomatic())
        assertEquals(SuperkeyAuthenticationResult.Skipped, auth.tryAutomatic())
        assertEquals(1, backend.calls)
        assertTrue(backend.lastProof!!.all { it == 0.toByte() })
        assertTrue(store.exists())
    }
    @Test fun nothingSavedDoesNotProbeKernelOrAuthenticate() {
        val backend = Backend()
        assertEquals(SuperkeyAuthenticationResult.Skipped, RememberedSuperkey(Store(), backend).tryAutomatic())
        assertEquals(0, backend.saltCalls); assertEquals(0, backend.calls)
    }
    @Test fun alreadyAuthenticatedManagerIsNotReauthenticated() {
        val store = Store().apply { seed() }; val backend = Backend().apply { manager = true }
        assertEquals(SuperkeyAuthenticationResult.Skipped, RememberedSuperkey(store, backend).tryAutomatic())
        assertEquals(0, backend.calls); assertEquals(0, backend.saltCalls); assertTrue(store.exists())
    }
    @Test fun absentCorePreservesSavedCredentialWithoutRetry() {
        val store = Store().apply { seed() }; val backend = Backend().apply { currentSalt = null }
        val auth = RememberedSuperkey(store, backend)
        assertEquals(SuperkeyAuthenticationResult.Skipped, auth.tryAutomatic())
        backend.currentSalt = ByteArray(16) { 7 }
        assertEquals(SuperkeyAuthenticationResult.Skipped, auth.tryAutomatic())
        assertTrue(store.exists()); assertEquals(0, backend.calls)
    }
    @Test fun newCoreSaltInvalidatesWithoutSendingStaleProof() {
        val store = Store().apply { seed() }; val backend = Backend().apply { currentSalt = ByteArray(16) { 1 } }
        val auth = RememberedSuperkey(store, backend)
        assertEquals(SuperkeyAuthenticationResult.Invalidated, auth.tryAutomatic())
        assertFalse(store.exists()); assertEquals(0, backend.calls)
        assertEquals(SuperkeyAuthenticationResult.Skipped, auth.tryAutomatic())
    }
    @Test fun rejectedSavedProofIsForgottenAndNotRetried() {
        val store = Store().apply { seed() }; val backend = Backend().apply { accepted = false }
        val auth = RememberedSuperkey(store, backend)
        assertEquals(SuperkeyAuthenticationResult.Invalidated, auth.tryAutomatic())
        assertFalse(store.exists())
        assertEquals(SuperkeyAuthenticationResult.Skipped, auth.tryAutomatic()); assertEquals(1, backend.calls)
    }
    @Test fun corruptedCiphertextOrLostKeyFallsBackWithoutAuthentication() {
        val store = Store().apply { seed(); brokenRead = true }; val backend = Backend()
        assertEquals(SuperkeyAuthenticationResult.Invalidated, RememberedSuperkey(store, backend).tryAutomatic())
        assertFalse(store.exists()); assertEquals(0, backend.calls)
    }
}
