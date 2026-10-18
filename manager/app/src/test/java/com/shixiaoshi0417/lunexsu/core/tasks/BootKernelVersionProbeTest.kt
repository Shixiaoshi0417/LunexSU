package com.shixiaoshi0417.lunexsu.core.tasks

import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class BootKernelVersionProbeTest {
    private fun boot(kernel: ByteArray, declaredSize: Int = kernel.size): ByteArray {
        val header = ByteArray(4096)
        "ANDROID!".toByteArray().copyInto(header)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(8, declaredSize).putInt(40, 3)
        return header + kernel
    }

    private fun compress(kernel: ByteArray, xz: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        if (xz) XZCompressorOutputStream(output).use { it.write(kernel) }
        else GZIPOutputStream(output).use { it.write(kernel) }
        return output.toByteArray()
    }

    private fun withLargeLocalKernel(kernel: ByteArray, test: (File) -> Unit) {
        val file = File.createTempFile("lunexsu-kmi-probe-", ".img")
        try {
            RandomAccessFile(file, "rw").use {
                it.write(boot(kernel, RAW_PROBE_LIMIT + 4096))
                // A padded compressed kernel block larger than the raw probe cap.
                it.setLength(4096L + RAW_PROBE_LIMIT + 4096)
            }
            test(file)
        } finally {
            assertTrue("Temporary boot fixture was not removed", file.delete())
        }
    }

    @Test fun localFileProbesCompressedPrefixesBeforeRawSizeCap() {
        val kernel = ByteArray(128 * 1024)
        "6.6.1-android15-test".toByteArray().copyInto(kernel, 64 * 1024 - 4)
        for (xz in listOf(false, true)) {
            val compressed = compress(kernel, xz)
            assertNull("The banner must not be visible in raw compressed bytes", BootKernelVersion.scanKmi(compressed))
            withLargeLocalKernel(compressed) { file ->
                assertEquals("android15-6.6", file.inputStream().use { BootKernelVersion.parseKmiFromBoot(it) })
                assertEquals("android15-6.6", BootKernelVersion.parseKmiFromBoot(file))
            }
        }
    }

    @Test fun localFileGrowsCompressedPrefixUntilBannerIsAvailable() {
        val kernel = Random(42).nextBytes(2 * 1024 * 1024)
        kernel.fill(0, 1200 * 1024, 1500 * 1024)
        "6.6.1-android15-test".toByteArray().copyInto(kernel, 1400 * 1024)
        for (xz in listOf(false, true)) {
            val compressed = compress(kernel, xz)
            assertTrue(compressed.size > 1024 * 1024)
            assertNull(BootKernelVersion.scanKmi(compressed))
            assertNull(BootKernelVersion.parseKmiFromBootData(boot(compressed.copyOf(1024 * 1024), RAW_PROBE_LIMIT + 4096)))
            withLargeLocalKernel(compressed) { file ->
                assertEquals("android15-6.6", BootKernelVersion.parseKmiFromBoot(file))
            }
        }
    }

    @Test fun localRawKernelStillStopsAtRawSizeCap() {
        withLargeLocalKernel(byteArrayOf(0)) { file ->
            RandomAccessFile(file, "rw").use {
                it.seek(4096L + RAW_PROBE_LIMIT)
                it.write("6.6.1-android15-test".toByteArray())
            }
            assertNull(BootKernelVersion.parseKmiFromBoot(file))
        }
    }

    @Test fun streamsCompressedKernelBannersAcrossReadBoundaries() {
        for (offset in listOf(64 * 1024 - 4, 64 * 1024 - 13)) {
            val kernel = ByteArray(128 * 1024)
            "6.6.1-android15-test".toByteArray().copyInto(kernel, offset)
            for (xz in listOf(false, true)) {
                val output = ByteArrayOutputStream()
                if (xz) XZCompressorOutputStream(output).use { it.write(kernel) }
                else GZIPOutputStream(output).use { it.write(kernel) }
                assertEquals("android15-6.6", BootKernelVersion.parseKmiFromBootData(boot(output.toByteArray())))
            }
        }
    }

    @Test fun compressedKernelWithoutBannerIsUnknown() {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(ByteArray(128 * 1024)) }
        assertNull(BootKernelVersion.parseKmiFromBootData(boot(output.toByteArray())))
    }

    @Test fun rejectsBootHeaderIndexNarrowingAndEndOverflowOnTinyBuffers() {
        val data = boot(byteArrayOf(0))
        val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(8, -1)
        assertNull(BootKernelVersion.bootKernelBlock(data))
        assertNull(BootKernelVersion.parseKmiFromBootData(data))
        header.putInt(8, Int.MAX_VALUE)
        assertNull(BootKernelVersion.bootKernelBlock(data))
        header.putInt(8, 1).putInt(40, 0).putInt(36, -1)
        assertNull(BootKernelVersion.bootKernelBlock(data))
        assertNull(BootKernelVersion.parseKmiFromBootData(data))
    }

    companion object {
        private const val RAW_PROBE_LIMIT = 64 * 1024 * 1024
    }
}
