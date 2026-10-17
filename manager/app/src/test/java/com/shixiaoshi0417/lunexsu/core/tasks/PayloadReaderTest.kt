package com.shixiaoshi0417.lunexsu.core.tasks

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

class PayloadReaderTest {
    private fun header(manifest: Long = 2, signature: Int = 1): ByteArray =
        ByteBuffer.allocate(24).putInt(0x43724155).putLong(2).putLong(manifest).putInt(signature).array()

    private fun source(bytes: ByteArray, declaredSize: Long = bytes.size.toLong(), maxRead: Int = Int.MAX_VALUE) =
        PayloadReader(declaredSize) { buffer, position ->
            if (position >= bytes.size) -1 else {
                val count = minOf(buffer.remaining(), bytes.size - position.toInt(), maxRead)
                buffer.put(bytes, position.toInt(), count)
                count
            }
        }

    @Test fun readsHeaderAndManifestAcrossShortReads() {
        val reader = source(header() + byteArrayOf(7, 8, 9), maxRead = 1)
        assertEquals(PayloadReader.Header(2, 27), reader.header())
        assertArrayEquals(byteArrayOf(7, 8), reader.bytes(24, 2, PayloadReader.MAX_MANIFEST_BYTES))
    }

    @Test fun rejectsZeroNegativeOverflowAndExcessiveManifestLengthsFromTinyHeaders() {
        for (length in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1, 0x100000001L,
            PayloadReader.MAX_MANIFEST_BYTES + 1, Long.MAX_VALUE)) {
            assertThrows(IOException::class.java) { source(header(length), Long.MAX_VALUE).header() }
        }
    }

    @Test fun acceptsAbsentMetadataSignature() {
        val reader = source(header(signature = 0) + byteArrayOf(7, 8), maxRead = 1)
        assertEquals(PayloadReader.Header(2, 26), reader.header())
        assertArrayEquals(byteArrayOf(7, 8), reader.bytes(24, 2, PayloadReader.MAX_MANIFEST_BYTES))
    }

    @Test fun rejectsUnsignedNegativeAndOversizeSignatureLengths() {
        for (length in listOf(-1, Int.MIN_VALUE, PayloadReader.MAX_SIGNATURE_BYTES.toInt() + 1)) {
            assertThrows(IOException::class.java) { source(header(signature = length), Long.MAX_VALUE).header() }
        }
    }

    @Test fun rejectsMetadataRangesOutsideRemainingFile() {
        assertThrows(IOException::class.java) { source(header(), 25).header() }
        assertThrows(IOException::class.java) { source(header(), 26).header() }
        assertThrows(IOException::class.java) { source(header(), 23).header() }
    }

    @Test fun reportsTruncatedHeaderAndManifestInsteadOfZeroPadding() {
        assertThrows(EOFException::class.java) { source(header().copyOf(23), 27, 1).header() }
        val reader = source(header() + byteArrayOf(7), 27, 1)
        reader.header()
        assertThrows(EOFException::class.java) { reader.bytes(24, 2, 2) }
    }

    @Test fun rejectsUntrustedLengthsBeforeCallingReadOrAllocating() {
        var calls = 0
        val reader = PayloadReader(Long.MAX_VALUE) { _, _ -> calls++; error("must not read") }
        for (length in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
            assertThrows(IOException::class.java) { reader.bytes(0, length, PayloadReader.MAX_MANIFEST_BYTES) }
            assertThrows(IOException::class.java) { reader.operationPosition(24, 0, length) }
        }
        assertEquals(0, calls)
    }

    @Test fun checksOperationOffsetAdditionAndFullEncodedRangeBeforeProbing() {
        val reader = source(byteArrayOf(1, 2, 3, 4), maxRead = 1)
        assertArrayEquals(byteArrayOf(2, 3), reader.probe(1, 0, 3, 2))
        assertThrows(IOException::class.java) { reader.probe(1, 0, 4, 2) }
        assertThrows(IOException::class.java) { reader.probe(1, -1, 1, 1) }
        assertThrows(IOException::class.java) { reader.probe(-1, 0, 1, 1) }
        assertThrows(IOException::class.java) { reader.operationPosition(1, Long.MAX_VALUE, 1) }
        assertThrows(IOException::class.java) { reader.operationPosition(1, 0, PayloadReader.MAX_OPERATION_BYTES + 1) }
        val large = PayloadReader(Long.MAX_VALUE) { _, _ -> error("must not read") }
        assertThrows(IOException::class.java) { large.operationPosition(Long.MAX_VALUE, 1, 1) }
        assertThrows(IOException::class.java) { large.operationPosition(Long.MAX_VALUE, 0, 1) }
    }

    @Test fun shortProbeDoesNotReturnPartialBytesAndZeroProgressDoesNotLoop() {
        assertThrows(EOFException::class.java) { source(byteArrayOf(1), 3).probe(0, 0, 3, 2) }
        assertThrows(IOException::class.java) { PayloadReader(24) { _, _ -> 0 }.header() }
    }

    @Test fun checksBlockMultiplicationAndPartitionBounds() {
        assertEquals(8192L, PayloadReader.blockBytes(2, 4096))
        assertThrows(IOException::class.java) { PayloadReader.blockBytes(-1, 4096) }
        assertThrows(IOException::class.java) { PayloadReader.blockBytes(Long.MAX_VALUE, 4096) }
        assertThrows(IOException::class.java) { PayloadReader.blockBytes(1, -1) }
        assertThrows(IOException::class.java) { PayloadReader.range(Long.MAX_VALUE, 1, Long.MAX_VALUE) }
        for (length in listOf(0L, -1L, PayloadReader.MAX_PARTITION_BYTES + 1)) {
            assertThrows(IOException::class.java) { PayloadReader.boundedLength(length, PayloadReader.MAX_PARTITION_BYTES) }
        }
    }

    @Test fun streamsSmallChunksWithExactOutputAndDetectsTruncation() {
        val expected = byteArrayOf(1, 2, 3)
        val output = mutableListOf<Byte>()
        PayloadReader.copyExactly(ByteArrayInputStream(expected), 3) { buffer ->
            while (buffer.hasRemaining()) output += buffer.get()
        }
        assertArrayEquals(expected, output.toByteArray())
        assertThrows(EOFException::class.java) {
            PayloadReader.copyExactly(ByteArrayInputStream(expected), 4) { }
        }
    }

    @Test fun checksUnreadEncodedTailEvenWhenDecoderStopsEarly() {
        val encoded = PayloadOperationInputStream(ByteArrayInputStream(byteArrayOf(1, 2)), 3)
        assertEquals(1, encoded.read())
        assertThrows(EOFException::class.java) { encoded.finish() }
        val complete = PayloadOperationInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2)
        assertEquals(1, complete.read())
        complete.finish()
        assertEquals(-1, complete.read())
    }
}
