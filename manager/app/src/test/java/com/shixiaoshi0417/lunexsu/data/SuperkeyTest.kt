package com.shixiaoshi0417.lunexsu.data

import org.junit.Assert.*
import org.junit.Test

class SuperkeyTest {
    private val salt = ByteArray(16) { it.toByte() }
    private val magic = "LUNEXSU-KEY-V1!!".toByteArray(Charsets.US_ASCII)

    private fun module(vararg offsets: Int): ByteArray = ByteArray(256).also {
        byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1).copyInto(it)
        offsets.forEach { offset -> magic.copyInto(it, offset) }
    }

    @Test
    fun validatesPortableKeys() {
        assertTrue(Superkey.valid("0123456789ab"))
        assertTrue(Superkey.valid("!".repeat(128)))
        listOf("", "short", "!".repeat(129), "0123456789 a", "0123456789\nX", "0123456789密码").forEach {
            assertFalse(Superkey.valid(it))
        }
    }

    @Test
    fun proofMatchesIndependentPbkdf2VectorAndClearsInput() {
        val key = "0123456789ab".toCharArray()
        val proof = Superkey.proof(key, salt)
        assertEquals("29dccb2cc8ef84588d94cdff99ebe41380d7f694c8802c4228fbb6745f33fb6a", proof.hex())
        assertTrue(key.all { it == '\u0000' })
    }

    @Test
    fun recordStoresOnlySaltAndHashedProof() {
        val record = Superkey.record("0123456789ab".toCharArray(), salt)
        assertEquals(68, record.size)
        assertArrayEquals(magic, record.copyOfRange(0, 16))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), record.copyOfRange(16, 20))
        assertArrayEquals(salt, record.copyOfRange(20, 36))
        assertEquals("e2a5c8c5f46b3b8c1167eb64f2bc094e73ad94230b2ecae5fe88ae320b5d820b", record.copyOfRange(36, 68).hex())
    }

    @Test
    fun eachInstallGetsFreshSalt() {
        assertFalse(Superkey.record("0123456789ab".toCharArray()).contentEquals(
            Superkey.record("0123456789ab".toCharArray())))
    }

    @Test
    fun patchOnlyChangesConfigAndLeavesInputIntact() {
        val original = module(64)
        val copy = original.copyOf()
        val record = Superkey.record("0123456789ab".toCharArray(), salt)
        val patched = Superkey.patch(original, record)
        assertArrayEquals(copy, original)
        assertArrayEquals(record, patched.copyOfRange(64, 132))
        assertArrayEquals(original.copyOfRange(0, 64), patched.copyOfRange(0, 64))
        assertArrayEquals(original.copyOfRange(132, 256), patched.copyOfRange(132, 256))
    }

    @Test
    fun refusesMissingOrAmbiguousConfigSlots() {
        val record = Superkey.record("0123456789ab".toCharArray(), salt)
        assertThrows(IllegalArgumentException::class.java) { Superkey.patch(module(), record) }
        assertThrows(IllegalArgumentException::class.java) { Superkey.patch(module(64, 160), record) }
    }

    @Test
    fun refusesUnsupportedOrTruncatedModules() {
        val record = Superkey.record("0123456789ab".toCharArray(), salt)
        assertThrows(IllegalArgumentException::class.java) { Superkey.patch(byteArrayOf(), record) }
        assertThrows(IllegalArgumentException::class.java) { Superkey.patch(module(64).also { it[0] = 0 }, record) }
        assertThrows(IllegalArgumentException::class.java) { Superkey.patch(module(64).also { it[5] = 2 }, record) }
        assertThrows(IllegalArgumentException::class.java) { Superkey.patch(module(240), record) }
    }

    @Test
    fun refusesInvalidRecordVersion() {
        val record = Superkey.record("0123456789ab".toCharArray(), salt)
        assertThrows(IllegalArgumentException::class.java) { Superkey.patch(module(64), record.copyOf(67)) }
        assertThrows(IllegalArgumentException::class.java) { Superkey.patch(module(64), record.also { it[16] = 2 }) }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
