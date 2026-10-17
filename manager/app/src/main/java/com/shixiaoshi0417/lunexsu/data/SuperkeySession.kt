package com.shixiaoshi0417.lunexsu.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes credentials and commits session invalidation before returning to a cancellable UI. */
internal class SuperkeySession(
    private val store: SuperkeyCredentialStore,
    backend: SuperkeyAuthenticationBackend,
    onAuthenticated: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _isSaved = MutableStateFlow(false)
    val isSaved = _isSaved.asStateFlow()
    private val _authenticationGeneration = MutableStateFlow(0L)
    val authenticationGeneration = _authenticationGeneration.asStateFlow()
    private val auth = RememberedSuperkey(store, object : SuperkeyAuthenticationBackend by backend {
        override fun authenticate(proof: ByteArray): Boolean = backend.authenticate(proof).also { accepted ->
            if (accepted) {
                // No suspension between the kernel commit and cache invalidation. withContext may
                // discard the result on cancellation, but must not discard these required effects.
                onAuthenticated()
                _authenticationGeneration.value++
            }
        }
    })

    private suspend fun <T> locked(block: () -> T): T = withContext(dispatcher) {
        mutex.withLock {
            try { block() } finally { _isSaved.value = store.exists() }
        }
    }

    suspend fun refresh() = locked { Unit }
    suspend fun authenticate(key: CharArray, remember: Boolean): SuperkeyAuthenticationResult = try {
        locked { auth.authenticate(key, remember) }
    } finally { key.fill('\u0000') }
    suspend fun tryAutomatic() = locked { auth.tryAutomatic() }
    suspend fun forget() = locked { store.clear() }
}
