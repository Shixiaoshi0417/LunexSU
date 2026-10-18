package com.shixiaoshi0417.lunexsu.core.tasks

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Validates untrusted uint64 metadata as Longs, before narrowing or allocating. */
internal class PayloadReader(
    private val size: Long,
    private val read: (ByteBuffer, Long) -> Int,
) {
    data class Header(val manifestLength: Long, val dataBase: Long)

    fun header(): Header {
        val header = ByteBuffer.wrap(bytes(0, HEADER_SIZE, HEADER_SIZE)).order(ByteOrder.BIG_ENDIAN)
        if (header.int != 0x43724155) throw IOException("Invalid payload: invalid magic")
        val version = header.long
        if (version != 2L) throw IOException("Invalid payload: unsupported version: $version")
        val manifestLength = header.long
        // The signature length is an unsigned 32-bit field, not a signed Int.
        val signatureLength = header.int.toLong() and 0xffffffffL
        boundedLength(manifestLength, MAX_MANIFEST_BYTES)
        // Unsigned payloads may omit the metadata signature; extraction does not
        // authenticate this signature. Still bound its declared size and range.
        if (signatureLength > MAX_SIGNATURE_BYTES) throw IOException("Invalid payload signature length")
        range(HEADER_SIZE, manifestLength, size)
        val signatureOffset = HEADER_SIZE + manifestLength
        range(signatureOffset, signatureLength, size)
        return Header(manifestLength, signatureOffset + signatureLength)
    }

    fun operationPosition(dataBase: Long, offset: Long, length: Long): Long {
        boundedLength(length, MAX_OPERATION_BYTES)
        range(dataBase, offset, size)
        val position = dataBase + offset
        range(position, length, size)
        return position
    }

    fun probe(dataBase: Long, offset: Long, length: Long, limit: Long): ByteArray {
        val position = operationPosition(dataBase, offset, length)
        return bytes(position, minOf(length, limit), limit)
    }

    fun bytes(position: Long, length: Long, limit: Long): ByteArray {
        boundedLength(length, minOf(limit, Int.MAX_VALUE.toLong()))
        range(position, length, size)
        val buffer = ByteBuffer.allocate(length.toInt())
        while (buffer.hasRemaining()) {
            val before = buffer.position()
            val count = read(buffer, position + before)
            if (count < 0) throw EOFException("Truncated payload")
            if (count == 0 || buffer.position() - before != count) {
                throw IOException("Payload read made no progress")
            }
        }
        return buffer.array()
    }

    companion object {
        const val HEADER_SIZE = 24L
        const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
        const val MAX_SIGNATURE_BYTES = 1024L * 1024
        // Boot-image extraction only; operation contents are streamed, not allocated at this size.
        const val MAX_OPERATION_BYTES = 256L * 1024 * 1024
        const val MAX_PARTITION_BYTES = 1024L * 1024 * 1024

        fun boundedLength(length: Long, limit: Long) {
            if (length <= 0 || length > limit) throw IOException("Invalid payload length: $length")
        }

        fun range(position: Long, length: Long, size: Long) {
            // Subtraction avoids both signed overflow and unsigned uint64 values represented as negatives.
            if (position < 0 || length < 0 || position > size || length > size - position) {
                throw IOException("Payload range is outside the file")
            }
        }

        fun blockBytes(blocks: Long, blockSize: Int): Long {
            if (blocks < 0 || blockSize <= 0 || blocks > Long.MAX_VALUE / blockSize) {
                throw IOException("Invalid payload block range")
            }
            return blocks * blockSize
        }

        fun copyExactly(input: InputStream, length: Long, write: (ByteBuffer) -> Unit) {
            copyToExtent(input, length, 0, write)
        }

        /** A valid final partial block is zero-padded in the pre-sized output file. */
        fun copyToExtent(input: InputStream, length: Long, maxPadding: Long, write: (ByteBuffer) -> Unit) {
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (count < 0) {
                    if (remaining <= maxPadding) return
                    throw EOFException("Truncated payload operation")
                }
                if (count == 0) throw IOException("Payload operation read made no progress")
                write(ByteBuffer.wrap(buffer, 0, count))
                remaining -= count
            }
        }
    }
}

/** Enforces the declared encoded length even when a decompressor stops before its source ends. */
internal class PayloadOperationInputStream(private val source: InputStream, length: Long) : InputStream() {
    private var remaining = length

    init {
        PayloadReader.boundedLength(length, PayloadReader.MAX_OPERATION_BYTES)
    }

    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = source.read()
        if (value < 0) throw EOFException("Truncated payload operation")
        remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = source.read(buffer, offset, minOf(remaining, length.toLong()).toInt())
        if (count < 0) throw EOFException("Truncated payload operation")
        if (count == 0) throw IOException("Payload operation read made no progress")
        remaining -= count
        return count
    }

    fun finish() = PayloadReader.copyExactly(this, remaining) { /* consume unused encoded bytes */ }

    override fun close() = source.close()
}
