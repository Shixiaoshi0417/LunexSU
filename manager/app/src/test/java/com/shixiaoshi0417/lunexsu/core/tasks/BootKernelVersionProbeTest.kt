package com.shixiaoshi0417.lunexsu.core.tasks

import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class BootKernelVersionProbeTest {
    private fun boot(kernel: ByteArray): ByteArray {
        val header = ByteArray(4096)
        "ANDROID!".toByteArray().copyInto(header)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(8, kernel.size).putInt(40, 3)
        return header + kernel
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
}
