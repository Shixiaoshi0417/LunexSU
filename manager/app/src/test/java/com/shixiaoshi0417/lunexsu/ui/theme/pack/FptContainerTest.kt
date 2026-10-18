package com.shixiaoshi0417.lunexsu.ui.theme.pack

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FptContainerTest {
    private val root = Files.createTempDirectory("lunexsu-fpt-test").toFile()
    @After fun cleanup() { root.deleteRecursively() }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    // Independent encoder fixture, using the protocol's published upstream key and IV layout.
    private fun encrypt(zip: ByteArray): ByteArray {
        val iv = ByteArray(16) { it.toByte() }
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256")
            .digest("FolkPatchThemeSecretKey2025".toByteArray()), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        return iv + cipher.doFinal(zip)
    }
    private fun read(bytes: ByteArray, limits: FptLimits = FptLimits()) =
        FptContainer.read(ByteArrayInputStream(bytes), File(root, "unpacked"), limits)
    private fun config() = "{\"meta_name\":\"Fixture\"}".toByteArray()

    @Test fun readsIndependentUpstreamFormatAndResources() {
        val (json, files) = read(encrypt(archive("theme.json" to config(), "background.png" to byteArrayOf(1, 2, 3))))
        assertEquals("Fixture", json.getString("meta_name"))
        assertArrayEquals(byteArrayOf(1, 2, 3), files.getValue("background.png").readBytes())
    }
    @Test fun writerRoundTripsUnicodeMetadata() {
        val json = JSONObject().put("meta_name", "LunexSU \u4e3b\u9898")
        assertEquals(json.toString(), read(FptContainer.write(json, emptyMap())).first.toString())
    }
    @Test fun rejectsUnsafePathsBeforeWritingOutsideStaging() {
        listOf("../escape", "/escape", "nested/file", "nested\\file", "C:escape", "bad\nname", "x".repeat(129)).forEach { name ->
            assertThrows(Exception::class.java) { read(encrypt(archive("theme.json" to config(), name to byteArrayOf(1)))) }
            File(root, "unpacked").deleteRecursively()
        }
        assertFalse(File(root, "escape").exists())
    }
    @Test fun rejectsDuplicateNames() {
        val zip = archive("theme.json" to config(), "aa.bin" to byteArrayOf(1), "bb.bin" to byteArrayOf(2))
        val patched = zip.toString(Charsets.ISO_8859_1).replace("bb.bin", "aa.bin").toByteArray(Charsets.ISO_8859_1)
        assertThrows(Exception::class.java) { read(encrypt(patched)) }
    }
    @Test fun rejectsDirectories() {
        assertThrows(Exception::class.java) { read(encrypt(archive("theme.json" to config(), "folder/" to byteArrayOf(1)))) }
    }
    @Test fun rejectsIncompleteCiphertextEvenAfterZipDataWasReadable() {
        val encrypted = encrypt(archive("theme.json" to config()))
        assertThrows(Exception::class.java) { read(encrypted.copyOf(encrypted.size - 1)) }
    }
    @Test fun rejectsIncompleteIv() {
        assertThrows(Exception::class.java) { read(ByteArray(15)) }
    }
    @Test fun rejectsMissingConfig() {
        assertThrows(Exception::class.java) { read(encrypt(archive("background.png" to byteArrayOf(1)))) }
    }
    @Test fun enforcesActualExpandedSize() {
        assertThrows(Exception::class.java) {
            read(encrypt(archive("theme.json" to config(), "large.bin" to ByteArray(4096))), FptLimits(totalBytes = 64))
        }
    }
    @Test fun enforcesConfigBudget() {
        assertThrows(Exception::class.java) { read(encrypt(archive("theme.json" to config())), FptLimits(configBytes = 8)) }
    }
    @Test fun enforcesCiphertextBudget() {
        assertThrows(Exception::class.java) { read(encrypt(archive("theme.json" to config())), FptLimits(containerBytes = 16)) }
    }
    @Test fun defaultLimitsMatchFinaSu() {
        // Store themes reach several hundred megabytes; FinaSU accepts 1 GiB of resources.
        assertEquals(1024L * 1024 * 1024, FptLimits().totalBytes)
        assertEquals(1024L * 1024 * 1024 + 32L * 1024 * 1024, FptLimits().containerBytes)
        assertEquals(1024L * 1024, FptLimits().configBytes)
        assertEquals(128, FptLimits().entries)
    }
    @Test fun enforcesEntryCount() {
        assertThrows(Exception::class.java) {
            read(encrypt(archive("theme.json" to config(), "a.bin" to byteArrayOf(1), "b.bin" to byteArrayOf(2))), FptLimits(entries = 2))
        }
    }
    @Test fun rejectsExcessiveJsonDepth() {
        val nested = ("{\"x\":".repeat(40) + "0" + "}".repeat(40)).toByteArray()
        assertThrows(Exception::class.java) { read(encrypt(archive("theme.json" to nested))) }
    }
    @Test fun ignoresBracesInsideEscapedJsonStrings() {
        val text = JSONObject().put("meta_description", "[{\\\"".repeat(40)).toString().toByteArray()
        assertTrue(read(encrypt(archive("theme.json" to text))).first.has("meta_description"))
    }
    @Test fun rejectsLenientAndMalformedJsonAtContainerBoundary() {
        for (text in listOf("{'meta_name':'x'}", "{/* \" */ \"meta_name\":\"x\"}",
            "{\"x\":[1,]}", "{\"x\":[}", "{}{}", "}{")) {
            assertThrows(IllegalArgumentException::class.java) {
                read(encrypt(archive("theme.json" to text.toByteArray())))
            }
            File(root, "unpacked").deleteRecursively()
        }
    }
    @Test fun acceptsExactly32LevelsOfStandardJson() {
        val text = "{\"x\":" + "[".repeat(31) + "0" + "]".repeat(31) + "}"
        assertTrue(read(encrypt(archive("theme.json" to text.toByteArray()))).first.has("x"))
    }
}
