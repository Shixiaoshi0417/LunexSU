package com.shixiaoshi0417.lunexsu.data

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class SuperkeyCredentialCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun credential() = SuperkeyCredential(ByteArray(16) { it.toByte() }, ByteArray(32) { (it + 30).toByte() })
    @Test fun encryptedEnvelopeRoundTripAndRandomIv() {
        val key = key()
        credential().use { source ->
            val first = SuperkeyCredentialCipher.encrypt(key, source)
            val second = SuperkeyCredentialCipher.encrypt(key, source)
            assertEquals(SuperkeyCredentialCipher.SIZE, first.size)
            assertFalse(first.contentEquals(second))
            SuperkeyCredentialCipher.decrypt(key, first).use {
                assertArrayEquals(source.salt, it.salt); assertArrayEquals(source.proof, it.proof)
            }
        }
    }
    @Test fun differentKeyCannotDecrypt() {
        credential().use { source ->
            val data = SuperkeyCredentialCipher.encrypt(key(), source)
            assertTrue(runCatching { SuperkeyCredentialCipher.decrypt(key(), data) }.isFailure)
        }
    }
    @Test fun allEnvelopeBytesAreAuthenticatedOrValidated() {
        val key = key()
        credential().use { source ->
            val data = SuperkeyCredentialCipher.encrypt(key, source)
            data.indices.forEach { index ->
                val corrupted = data.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
                assertTrue("byte $index", runCatching { SuperkeyCredentialCipher.decrypt(key, corrupted) }.isFailure)
            }
        }
    }
    @Test fun truncatedOrExtendedEnvelopeIsRejected() {
        val key = key()
        credential().use { source ->
            val data = SuperkeyCredentialCipher.encrypt(key, source)
            listOf(byteArrayOf(), data.copyOf(data.size - 1), data + byteArrayOf(0)).forEach {
                assertTrue(runCatching { SuperkeyCredentialCipher.decrypt(key, it) }.isFailure)
            }
        }
    }
    @Test fun closingCredentialClearsBothBuffers() {
        val credential = credential()
        credential.close()
        assertTrue(credential.salt.all { it == 0.toByte() })
        assertTrue(credential.proof.all { it == 0.toByte() })
    }
}
