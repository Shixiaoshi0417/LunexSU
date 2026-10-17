package com.shixiaoshi0417.lunexsu.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SuperkeySessionTest {
    private class Store : SuperkeyCredentialStore {
        var credential: SuperkeyCredential? = null
        var brokenWrite = false
        override fun exists() = credential != null
        override fun read() = credential?.let { SuperkeyCredential(it.salt.copyOf(), it.proof.copyOf()) }
        override fun write(credential: SuperkeyCredential) {
            check(!brokenWrite)
            clear()
            this.credential = SuperkeyCredential(credential.salt.copyOf(), credential.proof.copyOf())
        }
        override fun clear() { credential?.close(); credential = null }
        fun seed() { credential = SuperkeyCredential(ByteArray(16) { 7 }, ByteArray(32) { 8 }) }
    }

    private class Backend : SuperkeyAuthenticationBackend {
        var manager = false
        var accepted = true
        var available = true
        var afterCommit: () -> Unit = {}
        var calls = 0
        var lastProof: ByteArray? = null
        override fun isManager() = manager
        override fun salt() = if (available) ByteArray(16) { 7 } else null
        override fun authenticate(proof: ByteArray): Boolean {
            calls++
            lastProof = proof
            if (accepted) { manager = true; afterCommit() }
            return accepted
        }
    }

    private class CachedShell(val root: Boolean) : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    private fun cancelledAuthentication(automatic: Boolean) = runBlocking {
        val store = Store().apply { if (automatic) seed() }
        val backend = Backend()
        val cache = SessionResourceCache(create = { CachedShell(backend.manager) })
        val before = cache.use { it.also { shell -> assertFalse(shell.root) } }
        val committed = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        backend.afterCommit = { committed.countDown(); check(proceed.await(10, TimeUnit.SECONDS)) }
        val invalidations = AtomicInteger()
        val session = SuperkeySession(store, backend, {
            invalidations.incrementAndGet()
            cache.invalidate()
        })
        val chars = "0123456789ab".toCharArray()
        val caller = launch {
            if (automatic) session.tryAutomatic() else session.authenticate(chars, true)
            fail("The cancelled caller must not receive a successful result")
        }
        try {
            // Let the caller reach the IO dispatcher without blocking this runBlocking event loop.
            kotlinx.coroutines.yield()
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            caller.cancel()
        } finally { proceed.countDown() }
        caller.join()
        assertTrue(backend.manager)
        assertEquals(1, invalidations.get())
        assertEquals(1L, session.authenticationGeneration.value)
        assertTrue(before.closed)
        cache.use { assertTrue(it.root); assertFalse(it.closed) }
        assertTrue(session.isSaved.value)
        assertEquals(SuperkeyAuthenticationResult.Skipped, session.tryAutomatic())
        assertEquals(1, backend.calls)
        assertTrue(backend.lastProof!!.all { it == 0.toByte() })
        if (!automatic) assertTrue(chars.all { it == '\u0000' })
        cache.invalidate()
        store.clear()
    }

    @Test fun cancelledManualCallerStillInvalidatesTheSession() = cancelledAuthentication(false)
    @Test fun cancelledAutomaticCallerStillInvalidatesTheSession() = cancelledAuthentication(true)

    @Test fun acceptedManualAuthenticationInvalidatesBeforeReturning() = runBlocking {
        val backend = Backend()
        val callbacks = AtomicInteger()
        val session = SuperkeySession(Store(), backend, { assertTrue(backend.manager); callbacks.incrementAndGet() })
        val chars = "0123456789ab".toCharArray()
        assertEquals(SuperkeyAuthenticationResult.Accepted(), session.authenticate(chars, false))
        assertEquals(1, callbacks.get())
        assertEquals(1L, session.authenticationGeneration.value)
        assertFalse(session.isSaved.value)
        assertTrue(chars.all { it == '\u0000' })
    }

    @Test fun acceptedAutomaticAuthenticationInvalidatesOnlyOnce() = runBlocking {
        val store = Store().apply { seed() }
        val backend = Backend()
        val callbacks = AtomicInteger()
        val session = SuperkeySession(store, backend, { callbacks.incrementAndGet() })
        assertEquals(SuperkeyAuthenticationResult.Accepted(), session.tryAutomatic())
        assertEquals(SuperkeyAuthenticationResult.Skipped, session.tryAutomatic())
        assertEquals(1, callbacks.get())
        assertEquals(1, backend.calls)
        assertEquals(1L, session.authenticationGeneration.value)
        assertTrue(session.isSaved.value)
        store.clear()
    }

    @Test fun concurrentAutomaticAttemptWaitsForManualAuthenticationWithoutReplayingIt() = runBlocking {
        val store = Store()
        val backend = Backend()
        val committed = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        backend.afterCommit = { committed.countDown(); check(proceed.await(10, TimeUnit.SECONDS)) }
        val callbacks = AtomicInteger()
        val session = SuperkeySession(store, backend, { callbacks.incrementAndGet() })
        val manual = launch(start = CoroutineStart.UNDISPATCHED) {
            session.authenticate("0123456789ab".toCharArray(), true)
        }
        try {
            assertTrue(committed.await(10, TimeUnit.SECONDS))
            val automatic = async(start = CoroutineStart.UNDISPATCHED) { session.tryAutomatic() }
            proceed.countDown()
            manual.join()
            assertEquals(SuperkeyAuthenticationResult.Skipped, automatic.await())
            assertEquals(1, backend.calls)
            assertEquals(1, callbacks.get())
        } finally { proceed.countDown(); store.clear() }
    }

    @Test fun rejectedAuthenticationDoesNotInvalidateOrPublishAuthentication() = runBlocking {
        val backend = Backend().apply { accepted = false }
        val session = SuperkeySession(Store(), backend, { fail("Rejected authentication") })
        val chars = "0123456789ab".toCharArray()
        assertEquals(SuperkeyAuthenticationResult.Rejected, session.authenticate(chars, true))
        assertEquals(0L, session.authenticationGeneration.value)
        assertFalse(session.isSaved.value)
        assertTrue(chars.all { it == '\u0000' })
    }

    @Test fun missingCorePreservesSavedCredentialWithoutInvalidation() = runBlocking {
        val store = Store().apply { seed() }
        val backend = Backend().apply { available = false }
        val session = SuperkeySession(store, backend, { fail("No Core") })
        assertEquals(SuperkeyAuthenticationResult.Skipped, session.tryAutomatic())
        assertTrue(session.isSaved.value)
        assertEquals(0, backend.calls)
        assertEquals(0L, session.authenticationGeneration.value)
        store.clear()
    }

    @Test fun failedPersistenceStillInvalidatesAnAcceptedSession() = runBlocking {
        val store = Store().apply { brokenWrite = true }
        val callbacks = AtomicInteger()
        val session = SuperkeySession(store, Backend(), { callbacks.incrementAndGet() })
        assertEquals(SuperkeyAuthenticationResult.Accepted(storageFailed = true),
            session.authenticate("0123456789ab".toCharArray(), true))
        assertEquals(1, callbacks.get())
        assertFalse(session.isSaved.value)
    }

    @Test fun forgettingDoesNotInvalidateOrRevokeTheCurrentSession() = runBlocking {
        val backend = Backend()
        val callbacks = AtomicInteger()
        val session = SuperkeySession(Store(), backend, { callbacks.incrementAndGet() })
        session.authenticate("0123456789ab".toCharArray(), true)
        assertTrue(session.isSaved.value)
        session.forget()
        assertFalse(session.isSaved.value)
        assertTrue(backend.manager)
        assertEquals(1, callbacks.get())
        assertEquals(1L, session.authenticationGeneration.value)
    }

    @Test fun cancellationBeforeDispatchClearsInputWithoutAuthenticating() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val proceed = CountDownLatch(1)
        executor.execute { check(proceed.await(10, TimeUnit.SECONDS)) }
        executor.asCoroutineDispatcher().use { dispatcher ->
            val backend = Backend()
            val session = SuperkeySession(Store(), backend, { fail("Cancelled before dispatch") }, dispatcher)
            val chars = "0123456789ab".toCharArray()
            val caller = launch(start = CoroutineStart.UNDISPATCHED) { session.authenticate(chars, true) }
            caller.cancel()
            proceed.countDown()
            caller.join()
            assertEquals(0, backend.calls)
            assertTrue(chars.all { it == '\u0000' })
        }
    }
}
