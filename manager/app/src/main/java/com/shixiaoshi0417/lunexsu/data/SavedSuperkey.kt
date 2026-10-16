package com.shixiaoshi0417.lunexsu.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.ksuApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class AndroidSuperkeyCredentialStore(context: Context) : SuperkeyCredentialStore {
    // Android excludes noBackupFilesDir from both cloud backup and device transfer.
    private val file = AtomicFile(File(context.noBackupFilesDir, "remembered-superkey.enc"))
    private val alias = "${context.packageName}.remembered-superkey.v1"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Saved credential key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    override fun exists() = file.baseFile.exists() || File("${file.baseFile.path}.bak").exists()

    override fun read(): SuperkeyCredential? {
        if (!exists()) return null
        val encrypted = file.openRead().use { input ->
            val buffer = ByteArray(SuperkeyCredentialCipher.SIZE + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            buffer.copyOf(size)
        }
        return try { SuperkeyCredentialCipher.decrypt(key(create = false), encrypted) }
        finally { encrypted.fill(0) }
    }

    override fun write(credential: SuperkeyCredential) {
        val encrypted = SuperkeyCredentialCipher.encrypt(key(create = true), credential)
        try {
            val output = file.startWrite()
            try {
                android.system.Os.fchmod(output.fd, 0x180) // 0600, ciphertext accessible only to this UID.
                output.write(encrypted)
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
        } finally { encrypted.fill(0) }
    }

    override fun clear() {
        file.delete()
        check(!exists()) { "Could not remove saved credential" }
        keyStore().deleteEntry(alias)
    }
}

/** All disk, Keystore and kernel work stays off the UI thread and shares one lock. */
internal object SavedSuperkey {
    private val mutex = Mutex()
    private val store by lazy { AndroidSuperkeyCredentialStore(ksuApp) }
    private val auth by lazy {
        RememberedSuperkey(store, object : SuperkeyAuthenticationBackend {
            override fun isManager() = Natives.isManager
            override fun salt() = Natives.superkeySalt()
            override fun authenticate(proof: ByteArray) = Natives.authenticateSuperkey(proof)
        })
    }
    private val _isSaved = MutableStateFlow(false)
    val isSaved = _isSaved.asStateFlow()

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
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
