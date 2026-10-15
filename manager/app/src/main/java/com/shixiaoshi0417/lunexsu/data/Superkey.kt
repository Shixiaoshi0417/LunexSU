package com.shixiaoshi0417.lunexsu.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** On-image verifier only. Neither this record nor the salt is a bearer credential. */
object Superkey {
    const val ITERATIONS = 210_000
    private val magic = "LUNEXSU-KEY-V1!!".toByteArray(Charsets.US_ASCII)
    const val RECORD_SIZE = 68

    // Restrict to portable ASCII so Android's PBKDF2 password encoding is unambiguous.
    fun valid(key: String): Boolean = key.length in 12..128 && key.all { it.code in 33..126 }

    fun proof(key: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == 16)
        val spec = PBEKeySpec(key, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); key.fill('\u0000') }
    }

    fun record(key: CharArray, salt: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)): ByteArray {
        val derived = proof(key, salt)
        return try {
            // The fixed 16-byte magic is followed immediately by the version.
            magic + byteArrayOf(1, 0, 0, 0) + salt + MessageDigest.getInstance("SHA-256").digest(derived)
        } finally { derived.fill(0) }
    }

    /** Only compatible little-endian ELF modules with exactly one config slot may be patched. */
    fun patch(module: ByteArray, record: ByteArray): ByteArray {
        require(record.size == RECORD_SIZE && record.take(16) == magic.toList() &&
            record.sliceArray(16..19).contentEquals(byteArrayOf(1, 0, 0, 0))) { "Invalid superkey record" }
        require(module.size >= RECORD_SIZE && module[0] == 0x7f.toByte() &&
            module[1] == 'E'.code.toByte() && module[2] == 'L'.code.toByte() &&
            module[3] == 'F'.code.toByte() && module[5] == 1.toByte()) { "Unsupported LKM format" }
        val offsets = (0..module.size - RECORD_SIZE).filter { start ->
            magic.indices.all { module[start + it] == magic[it] }
        }
        require(offsets.size == 1) { "This LKM does not support superkey configuration" }
        return module.copyOf().also { record.copyInto(it, offsets.single()) }
    }
}
