package com.shixiaoshi0417.lunexsu.data

/** A reusable credential, not the image verifier. Never log or serialize it unencrypted. */
internal class SuperkeyCredential(val salt: ByteArray, val proof: ByteArray) : AutoCloseable {
    init { require(salt.size == 16 && proof.size == 32) }
    override fun close() { salt.fill(0); proof.fill(0) }
}

internal interface SuperkeyCredentialStore {
    fun exists(): Boolean
    fun read(): SuperkeyCredential?
    fun write(credential: SuperkeyCredential)
    fun clear()
}

internal interface SuperkeyAuthenticationBackend {
    fun isManager(): Boolean
    fun salt(): ByteArray?
    fun authenticate(proof: ByteArray): Boolean
}

internal sealed interface SuperkeyAuthenticationResult {
    data class Accepted(val storageFailed: Boolean = false) : SuperkeyAuthenticationResult
    data object Rejected : SuperkeyAuthenticationResult
    data object Skipped : SuperkeyAuthenticationResult
    data object Invalidated : SuperkeyAuthenticationResult
}

/** The caller serializes access. Only one automatic attempt is allowed per app process. */
internal class RememberedSuperkey(
    private val store: SuperkeyCredentialStore,
    private val backend: SuperkeyAuthenticationBackend,
) {
    private var autoAttempted = false

    fun authenticate(key: CharArray, remember: Boolean): SuperkeyAuthenticationResult {
        try {
            val salt = backend.salt() ?: return SuperkeyAuthenticationResult.Rejected
            val proof = Superkey.proof(key, salt)
            SuperkeyCredential(salt, proof).use { credential ->
                if (!backend.authenticate(credential.proof)) return SuperkeyAuthenticationResult.Rejected
                // A persistence error must not undo a successful kernel authentication.
                val failed = try {
                    if (remember) store.write(credential) else store.clear()
                    false
                } catch (_: Exception) {
                    runCatching { store.clear() }
                    true
                }
                return SuperkeyAuthenticationResult.Accepted(storageFailed = failed)
            }
        } finally { key.fill('\u0000') }
    }

    fun tryAutomatic(): SuperkeyAuthenticationResult {
        if (autoAttempted) return SuperkeyAuthenticationResult.Skipped
        autoAttempted = true
        if (!store.exists()) return SuperkeyAuthenticationResult.Skipped
        if (backend.isManager()) return SuperkeyAuthenticationResult.Skipped
        // No Core/locked candidate FD is not evidence that a saved credential is invalid.
        val salt = backend.salt() ?: return SuperkeyAuthenticationResult.Skipped
        try {
            val credential = try { store.read() } catch (_: Exception) {
                store.clear()
                return SuperkeyAuthenticationResult.Invalidated
            } ?: return SuperkeyAuthenticationResult.Skipped
            credential.use {
                if (salt.contentEquals(it.salt) && backend.authenticate(it.proof)) {
                    return SuperkeyAuthenticationResult.Accepted()
                }
            }
            store.clear()
            return SuperkeyAuthenticationResult.Invalidated
        } finally { salt.fill(0) }
    }
}
