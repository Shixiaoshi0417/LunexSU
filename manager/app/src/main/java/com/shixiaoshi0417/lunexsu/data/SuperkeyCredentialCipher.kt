package com.shixiaoshi0417.lunexsu.data

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned, authenticated envelope. The only stored payload is salt + derived proof. */
internal object SuperkeyCredentialCipher {
    private const val VERSION: Byte = 1
    private const val IV_SIZE = 12
    const val SIZE = 1 + IV_SIZE + 16 + 32 + 16
    private val aad = "LunexSU remembered superkey v1".toByteArray(Charsets.US_ASCII)

    fun encrypt(key: SecretKey, credential: SuperkeyCredential): ByteArray {
        val plain = credential.salt + credential.proof
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            require(cipher.iv.size == IV_SIZE)
            cipher.updateAAD(aad)
            return byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plain)
        } finally { plain.fill(0) }
    }

    fun decrypt(key: SecretKey, envelope: ByteArray): SuperkeyCredential {
        require(envelope.size == SIZE && envelope[0] == VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope, 1, IV_SIZE))
        cipher.updateAAD(aad)
        val plain = cipher.doFinal(envelope, 1 + IV_SIZE, envelope.size - 1 - IV_SIZE)
        try {
            require(plain.size == 48)
            return SuperkeyCredential(plain.copyOfRange(0, 16), plain.copyOfRange(16, 48))
        } finally { plain.fill(0) }
    }
}
