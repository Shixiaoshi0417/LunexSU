package com.shixiaoshi0417.lunexsu.core.tasks

import chromeos_update_engine.UpdateMetadata.DeltaArchiveManifest
import chromeos_update_engine.UpdateMetadata.Extent
import chromeos_update_engine.UpdateMetadata.InstallOperation
import chromeos_update_engine.UpdateMetadata.PartitionInfo
import chromeos_update_engine.UpdateMetadata.PartitionUpdate
import com.shixiaoshi0417.lunexsu.core.utils.DataSourceChannel
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Tiny local-file fixtures only: no HTTP, Android device, or large payloads. */
class PayloadExtractionTest {
    private val root = Files.createTempDirectory("payload-extraction-test").toFile()
    @After fun cleanup() { root.deleteRecursively() }

    private fun extent(start: Long = 0, blocks: Long = 1): Extent =
        Extent.newBuilder().setStartBlock(start).setNumBlocks(blocks).build()

    private fun operation(type: InstallOperation.Type = InstallOperation.Type.REPLACE,
        length: Long = 4, offset: Long = 0, extents: List<Extent> = listOf(extent())): InstallOperation =
        InstallOperation.newBuilder().setType(type).setDataLength(length).setDataOffset(offset)
            .addAllDstExtents(extents).build()

    private fun fixture(operation: InstallOperation, bytes: ByteArray, size: Long = 4): ByteArray {
        val manifest = DeltaArchiveManifest.newBuilder().setBlockSize(4).addPartitions(
            PartitionUpdate.newBuilder().setPartitionName("boot")
                .setNewPartitionInfo(PartitionInfo.newBuilder().setSize(size)).addOperations(operation)
        ).build().toByteArray()
        return ByteBuffer.allocate(24).putInt(0x43724155).putLong(2).putLong(manifest.size.toLong())
            .putInt(1).array() + manifest + byteArrayOf(0) + bytes
    }

    private fun <T> withPayload(bytes: ByteArray, block: (Payload) -> T): T {
        val file = File(root, "payload.bin").also { it.writeBytes(bytes) }
        // DataSourceChannel's local-file implementation is private; keep its production API unchanged.
        val constructor = DataSourceChannel::class.java.declaredConstructors.single { it.parameterCount == 5 }
        constructor.isAccessible = true
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { source ->
            val channel = constructor.newInstance(null, null, source, 0L, bytes.size.toLong()) as DataSourceChannel
            channel.use { return block(Payload(it)) }
        }
    }

    private fun extract(bytes: ByteArray, output: File = File(root, "result.bin")): ByteArray =
        withPayload(bytes) { payload ->
            payload.extract(output, "boot", {}, {})
            output.readBytes()
        }

    @Test fun extractsReplaceAndBothCompressionFormats() {
        val expected = byteArrayOf(1, 2, 3, 4)
        for (type in listOf(InstallOperation.Type.REPLACE, InstallOperation.Type.REPLACE_BZ, InstallOperation.Type.REPLACE_XZ)) {
            val output = ByteArrayOutputStream()
            val encoded = when (type) {
                InstallOperation.Type.REPLACE_BZ -> {
                    BZip2CompressorOutputStream(output).use { it.write(expected) }; output.toByteArray()
                }
                InstallOperation.Type.REPLACE_XZ -> {
                    XZCompressorOutputStream(output).use { it.write(expected) }; output.toByteArray()
                }
                else -> expected
            }
            assertArrayEquals(expected, extract(fixture(operation(type, encoded.size.toLong()), encoded)))
        }
    }

    @Test fun preservesFinalPartialBlockPaddingAndMultipleDestinationExtents() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 0), extract(fixture(operation(length = 3), byteArrayOf(1, 2, 3))))
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val op = operation(length = 8, extents = listOf(extent(1), extent(0)))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 1, 2, 3, 4), extract(fixture(op, bytes, 8)))
    }

    @Test fun rejectsInvalidOperationMetadataBeforeTruncatingDestination() {
        val output = File(root, "preserved.bin").also { it.writeBytes(byteArrayOf(9)) }
        for (op in listOf(operation(length = 0), operation(length = -1),
            operation(length = Int.MAX_VALUE.toLong() + 1), operation(length = 0x100000004L),
            operation(length = PayloadReader.MAX_OPERATION_BYTES + 1), operation(offset = Long.MAX_VALUE),
            operation(extents = emptyList()), operation(extents = listOf(extent(Long.MAX_VALUE))),
            operation(extents = listOf(extent(blocks = Long.MAX_VALUE))),
            operation(extents = listOf(extent(1))))) {
            assertThrows(IOException::class.java) { extract(fixture(op, byteArrayOf(1, 2, 3, 4)), output) }
            assertArrayEquals(byteArrayOf(9), output.readBytes())
        }
    }

    @Test fun rejectsInvalidPartitionSizesBeforeTruncatingDestination() {
        val output = File(root, "preserved.bin").also { it.writeBytes(byteArrayOf(9)) }
        for (size in listOf(0L, -1L, PayloadReader.MAX_PARTITION_BYTES + 1)) {
            assertThrows(IOException::class.java) { extract(fixture(operation(), byteArrayOf(1, 2, 3, 4), size), output) }
            assertArrayEquals(byteArrayOf(9), output.readBytes())
        }
    }

    @Test fun rejectsTruncatedEncodedAndExcessiveDecodedOperations() {
        assertThrows(IOException::class.java) { extract(fixture(operation(length = 5), byteArrayOf(1, 2, 3, 4))) }
        val encoded = ByteArrayOutputStream().also { output ->
            BZip2CompressorOutputStream(output).use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }
        }.toByteArray()
        assertThrows(IOException::class.java) {
            extract(fixture(operation(InstallOperation.Type.REPLACE_BZ, encoded.size.toLong()), encoded))
        }
        assertThrows(IOException::class.java) {
            extract(fixture(operation(InstallOperation.Type.REPLACE_BZ, 2), encoded.copyOf(2)))
        }
    }
}
