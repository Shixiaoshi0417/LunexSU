package com.shixiaoshi0417.lunexsu.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LkmFilePreparationTest {
    @get:Rule val temporary = TemporaryFolder()

    private val magic = "LUNEXSU-KEY-V1!!".toByteArray(Charsets.US_ASCII)

    private fun record(marker: Int = 7) =
        magic + byteArrayOf(1, 0, 0, 0) + ByteArray(48) { (it + marker).toByte() }

    private fun module(vararg offsets: Int = intArrayOf(64)) = ByteArray(256).also {
        byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1).copyInto(it)
        offsets.forEach { offset -> magic.copyInto(it, offset) }
    }

    private fun assertEmpty(directory: File) = assertEquals(emptyList<String>(), directory.list()!!.toList())

    @Test
    fun replacedReadOnlyExtractionSourceIsPatchedIntoANewFile() {
        val cache = temporary.newFolder()
        val original = module()
        val record = record()
        lateinit var source: File
        val output = LkmFilePreparation.prepare(cache, record) {
            source = it
            // Match ensure_binary: unlink the App-created file and create a replacement.
            assertTrue(source.delete())
            source.writeBytes(original)
            if (Files.getFileStore(source.toPath()).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(source.toPath(), setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ,
                ))
            }
        }
        assertNotEquals(source, output)
        assertFalse(source.exists())
        assertArrayEquals(Superkey.patch(original, record), output.readBytes())
        assertEquals(listOf(output.name), cache.list()!!.toList())
        if (Files.getFileStore(output.toPath()).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(output.toPath()))
        }
    }

    @Test
    fun externalModuleWithoutSuperkeyIsCopiedUnchanged() {
        val cache = temporary.newFolder()
        val bytes = ByteArray(128 * 1024) { it.toByte() }
        val output = LkmFilePreparation.prepare(cache, null) { it.writeBytes(bytes) }
        assertArrayEquals(bytes, output.readBytes())
        assertEquals(listOf(output.name), cache.list()!!.toList())
    }

    @Test
    fun externalModuleWithSuperkeyKeepsInputAndRecordUnchanged() {
        val cache = temporary.newFolder()
        val original = module()
        val snapshot = original.copyOf()
        val record = record()
        val recordSnapshot = record.copyOf()
        val output = LkmFilePreparation.prepare(cache, record) { it.writeBytes(original) }
        assertArrayEquals(Superkey.patch(original, record), output.readBytes())
        assertArrayEquals(snapshot, original)
        assertArrayEquals(recordSnapshot, record)
    }

    @Test
    fun populationFailureDeletesPartialSourceAndPreservesTheError() {
        val cache = temporary.newFolder()
        val failure = IOException("Extraction failed")
        assertSame(failure, assertThrows(IOException::class.java) {
            LkmFilePreparation.prepare(cache, record()) {
                it.writeBytes(byteArrayOf(1, 2, 3))
                throw failure
            }
        })
        assertEmpty(cache)
    }

    @Test
    fun disappearedSourceDoesNotLeaveAnOutput() {
        val cache = temporary.newFolder()
        assertThrows(IOException::class.java) {
            LkmFilePreparation.prepare(cache, record()) { assertTrue(it.delete()) }
        }
        assertEmpty(cache)
    }

    @Test
    fun malformedModuleDeletesSourceAndOutput() {
        val cache = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            LkmFilePreparation.prepare(cache, record()) { it.writeBytes(byteArrayOf(1, 2, 3)) }
        }
        assertEmpty(cache)
    }

    @Test
    fun missingOrAmbiguousSuperkeySlotDeletesSourceAndOutput() {
        val cache = temporary.newFolder()
        for (bytes in listOf(module(*intArrayOf()), module(64, 160))) {
            assertThrows(IllegalArgumentException::class.java) {
                LkmFilePreparation.prepare(cache, record()) { it.writeBytes(bytes) }
            }
            assertEmpty(cache)
        }
    }

    @Test
    fun invalidRecordDeletesSourceAndOutput() {
        val cache = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            LkmFilePreparation.prepare(cache, record().also { it[16] = 2 }) { it.writeBytes(module()) }
        }
        assertEmpty(cache)
    }

    @Test
    fun aFailedPreparationDoesNotRemoveAnEarlierOutput() {
        val cache = temporary.newFolder()
        val output = LkmFilePreparation.prepare(cache, null) { it.writeBytes(module()) }
        val expected = output.readBytes()
        assertThrows(IOException::class.java) {
            LkmFilePreparation.prepare(cache, record()) { throw IOException("Failed") }
        }
        assertArrayEquals(expected, output.readBytes())
        assertEquals(listOf(output.name), cache.list()!!.toList())
    }

    @Test
    fun concurrentPreparationsUseIndependentSourcesAndOutputs() {
        val cache = temporary.newFolder()
        val sourcesReady = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { marker ->
                executor.submit<File> {
                    LkmFilePreparation.prepare(cache, record(marker)) {
                        it.writeBytes(module())
                        sourcesReady.countDown()
                        check(sourcesReady.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            val outputs = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertNotEquals(outputs[0], outputs[1])
            outputs.forEachIndexed { index, output ->
                assertArrayEquals(Superkey.patch(module(), record(index + 1)), output.readBytes())
            }
            assertEquals(outputs.map { it.name }.toSet(), cache.list()!!.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun existingLegacyTemporaryFileIsNotReusedOrModified() {
        val cache = temporary.newFolder()
        val legacy = File(cache, "kernelsu-tmp-lkm.ko").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val output = LkmFilePreparation.prepare(cache, record()) { it.writeBytes(module()) }
        assertNotEquals(legacy, output)
        assertArrayEquals(byteArrayOf(9, 8, 7), legacy.readBytes())
        assertArrayEquals(Superkey.patch(module(), record()), output.readBytes())
    }

    @Test
    fun selectedKmiTakesPrecedenceOverDetection() {
        assertEquals("android13-5.10", LkmFilePreparation.requireKmi("android13-5.10", "android14-6.1"))
    }

    @Test
    fun automaticKmiIsUsedOnlyWhenNoKmiWasSelected() {
        assertEquals("android17-6.18", LkmFilePreparation.requireKmi(null, "android17-6.18"))
    }

    @Test
    fun invalidKmiFailsBeforeExtractionAndCleansTheSource() {
        val cache = temporary.newFolder()
        var extracted = false
        for (selected in listOf("", "android14-6.1;id", "../android14-6.1", "android14-6.1\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                LkmFilePreparation.prepare(cache, record()) {
                    LkmFilePreparation.requireKmi(selected, "android14-6.1")
                    extracted = true
                }
            }
            assertEmpty(cache)
        }
        assertThrows(IllegalArgumentException::class.java) { LkmFilePreparation.requireKmi(null, null) }
        assertFalse(extracted)
    }
}
