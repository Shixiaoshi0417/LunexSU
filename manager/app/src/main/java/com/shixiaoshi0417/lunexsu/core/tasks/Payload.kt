package com.shixiaoshi0417.lunexsu.core.tasks

import chromeos_update_engine.UpdateMetadata.DeltaArchiveManifest
import chromeos_update_engine.UpdateMetadata.InstallOperation
import chromeos_update_engine.UpdateMetadata.PartitionUpdate
import com.shixiaoshi0417.lunexsu.core.utils.DataSourceChannel
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class Payload(private val channel: DataSourceChannel) {
    private val reader = PayloadReader(channel.size()) { buffer, position -> channel.read(buffer, position) }
    private val manifest: DeltaArchiveManifest
    private var dataBase = 0L

    init {
        manifest = readPayloadHeader()
    }

    fun extract(
        outputFile: File,
        partitionName: String,
        console: (String) -> Unit,
        logger: (String) -> Unit,
    ) {
        val partition = findPartition(partitionName)
        console("- Found partition ${partition.partitionName}")

        val actualHash = extractPartition(outputFile, partition, console)

        if (!partition.newPartitionInfo.hasHash()) {
            logger("Hash verification skipped")
            return
        }

        fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        val expectedHash = partition.newPartitionInfo.hash.toByteArray()
        if (!expectedHash.contentEquals(actualHash)) {
            throw IOException(
                "Hash mismatch, expected ${toHex(expectedHash)}, but got ${toHex(actualHash)}"
            )
        }
        logger("Hash verification passed")
    }

    fun partitionNames(): List<String> = manifest.partitionsList.map { it.partitionName }

    /**
     * Extracts the KMI from the payload's boot partition with minimal reads.
     * A miss means "unknown": the install page asks for a KMI manually and
     * remote downloads pass an explicit --kmi to ksud.
     */
    fun kernelKmi(onProgress: ((String) -> Unit)? = null): String? {
        try {
            for (name in BOOT_PARTITION_NAMES) {
                val partition = manifest.partitionsList.find { it.partitionName == name } ?: continue
                kernelKmi(partition, onProgress)?.let { return it }
            }
        } catch (_: IOException) {
            // A malformed range or short remote response is an unknown KMI, never padded data.
        }
        return null
    }

    private fun kernelKmi(
        partition: PartitionUpdate,
        onProgress: ((String) -> Unit)? = null,
    ): String? {
        // The banner can sit deep (EFI PE GKI, ~45-55% into the kernel), so
        // probe the middle op first, then fall back to the sequential walk.
        probeKernelMiddle(partition, onProgress)?.let { return it }
        return walkKernelPrefix(partition, onProgress)
    }

    /**
     * Ops map to uncompressed ranges via dst extents, so only the op covering
     * the kernel middle needs to be fetched.
     */
    private fun probeKernelMiddle(
        partition: PartitionUpdate,
        onProgress: ((String) -> Unit)?,
    ): String? {
        val ranges = opRanges(partition)
        val firstIndex = ranges.indexOfFirst { it.first == 0L }
        if (firstIndex < 0) return null
        val first = partition.operationsList[firstIndex]
        if (first.dataLength <= 0 || first.getType() !in KMI_OPERATION_TYPES) return null

        // Locate the kernel block from the first op's header.
        val header = reader.probe(dataBase, first.dataOffset, first.dataLength, HEADER_PROBE_LIMIT)
        val headerChunk = decodeChunk(header, first.getType()) ?: return null
        val (kernelOffset, kernelSize) = BootKernelVersion.bootKernelBlock(headerChunk) ?: return null
        if (kernelSize <= 0) return null

        val target = kernelOffset.toLong() + kernelSize / 2L
        val middleIndex = ranges.indexOfFirst { it.first <= target && target < it.second }
        if (middleIndex < 0) return null
        val count = partition.operationsCount
        // The banner can sit just left of the middle op boundary; also cover the
        // previous op.
        for (index in middleIndex downTo maxOf(0, middleIndex - 1)) {
            val operation = partition.operationsList[index]
            if (operation.dataLength <= 0 || operation.getType() !in KMI_OPERATION_TYPES) continue
            onProgress?.invoke("- Analyzing boot partition ${index + 1}/$count")
            val data = reader.probe(dataBase, operation.dataOffset, operation.dataLength, KMI_PROBE_LIMIT)
            val chunk = decodeChunk(data, operation.getType()) ?: continue
            BootKernelVersion.scanKmi(chunk)?.let { return it }
        }
        return null
    }

    private fun walkKernelPrefix(
        partition: PartitionUpdate,
        onProgress: ((String) -> Unit)?,
    ): String? {
        val prefix = ByteArrayOutputStream()
        var scannedPrefix = 0
        val count = partition.operationsCount
        for ((index, operation) in partition.operationsList.withIndex()) {
            if (onProgress != null && (index % 5 == 0 || index == count - 1)) {
                onProgress("- Analyzing boot partition ${index + 1}/$count")
            }
            if (operation.dataLength <= 0 || operation.dstExtentsCount == 0) continue
            val type = operation.getType()
            if (type !in KMI_OPERATION_TYPES) continue

            val remaining = KMI_PROBE_LIMIT - prefix.size()
            val data = reader.probe(dataBase, operation.dataOffset, operation.dataLength, remaining)
            val chunk = decodeChunk(data, type) ?: return null
            prefix.write(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
            BootKernelVersion.parseKmiFromBootData(prefix.toByteArray(), scannedPrefix)
                ?.let { return it }
            scannedPrefix = prefix.size()
            if (prefix.size() >= KMI_PROBE_LIMIT) return null
        }
        return null
    }

    private fun decodeChunk(data: ByteArray, type: InstallOperation.Type): ByteArray? {
        return when (type) {
            InstallOperation.Type.REPLACE -> data
            InstallOperation.Type.REPLACE_BZ, InstallOperation.Type.REPLACE_XZ ->
                decompressPrefix(data, type)

            else -> null
        }
    }

    private fun opRanges(partition: PartitionUpdate): List<Pair<Long, Long>> {
        PayloadReader.boundedLength(partition.newPartitionInfo.size, PayloadReader.MAX_PARTITION_BYTES)
        return partition.operationsList.map { operation ->
            val ranges = destinationRanges(operation, partition.newPartitionInfo.size)
            ranges.minOf { it.first } to ranges.maxOf { it.first + it.second }
        }
    }

    private fun decompressPrefix(data: ByteArray, type: InstallOperation.Type): ByteArray? {
        return try {
            val stream = when (type) {
                InstallOperation.Type.REPLACE_BZ -> BZip2CompressorInputStream(ByteArrayInputStream(data))
                InstallOperation.Type.REPLACE_XZ -> xzStream(ByteArrayInputStream(data))
                else -> return null
            }
            stream.use { decompressPrefix(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun decompressPrefix(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (output.size() < KMI_PROBE_LIMIT) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) throw IOException("Payload operation read made no progress")
                output.write(buffer, 0, minOf(read, (KMI_PROBE_LIMIT - output.size()).toInt()))
            }
        } catch (_: IOException) {
            // Truncated stream: keep the prefix decompressed so far.
        }
        return if (output.size() > 0) output.toByteArray() else null
    }

    @Throws(IOException::class)
    private fun readPayloadHeader(): DeltaArchiveManifest {
        val header = reader.header()
        val bytes = reader.bytes(PayloadReader.HEADER_SIZE, header.manifestLength, PayloadReader.MAX_MANIFEST_BYTES)
        dataBase = header.dataBase
        return DeltaArchiveManifest.parseFrom(bytes)
    }

    @Throws(IOException::class)
    private fun findPartition(partitionName: String): PartitionUpdate {
        return manifest.partitionsList.find { it.partitionName == partitionName }
            ?: throw IOException("partition $partitionName not found in payload")
    }

    @Throws(IOException::class)
    private fun extractPartition(
        outputFile: File,
        partition: PartitionUpdate,
        console: (String) -> Unit,
    ): ByteArray {
        val size = partition.newPartitionInfo.size
        PayloadReader.boundedLength(size, PayloadReader.MAX_PARTITION_BYTES)
        // Validate all ranges before creating/truncating the destination.
        partition.operationsList.forEach { operation ->
            destinationRanges(operation, size)
            if (operation.getType() != InstallOperation.Type.ZERO) {
                if (operation.getType() !in KMI_OPERATION_TYPES) {
                    throw IOException("Unsupported operation type: ${operation.getType()}")
                }
                reader.operationPosition(dataBase, operation.dataOffset, operation.dataLength)
            }
        }
        FileChannel.open(
            outputFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ,
            StandardOpenOption.TRUNCATE_EXISTING
        ).use { outChannel ->
            writeFully(outChannel, ByteBuffer.allocate(1), size - 1)

            val count = partition.operationsCount
            partition.operationsList.forEachIndexed { index, operation ->
                if (index % 5 == 0 || index == count - 1) {
                    console("- Downloading ${index + 1}/$count")
                }
                processOperation(outChannel, operation, size)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(64 * 1024)
            var position = 0L
            while (position < size) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), size - position).toInt())
                val count = outChannel.read(buffer, position)
                if (count <= 0) throw IOException("Cannot read extracted partition")
                position += count
                buffer.flip()
                digest.update(buffer)
            }
            return digest.digest()
        }
    }

    @Throws(IOException::class)
    private fun processOperation(outChannel: FileChannel, operation: InstallOperation, partitionSize: Long) {
        val dataType = operation.getType()
        if (dataType == InstallOperation.Type.ZERO) {
            return
        }

        val position = reader.operationPosition(dataBase, operation.dataOffset, operation.dataLength)
        channel.streamRead(position, operation.dataLength).use { source ->
            val encoded = PayloadOperationInputStream(source, operation.dataLength)
            val decoded = when (dataType) {
                InstallOperation.Type.REPLACE -> encoded
                InstallOperation.Type.REPLACE_BZ -> BZip2CompressorInputStream(encoded)
                InstallOperation.Type.REPLACE_XZ -> xzStream(encoded)
                else -> throw IOException("Unsupported operation type: $dataType")
            }
            decoded.use { input ->
                val ranges = destinationRanges(operation, partitionSize)
                ranges.forEachIndexed { index, (offset, length) ->
                    var written = 0L
                    val padding = if (index == ranges.lastIndex) manifest.blockSize - 1L else 0L
                    PayloadReader.copyToExtent(input, length, padding) { buffer ->
                        val count = buffer.remaining()
                        writeFully(outChannel, buffer, offset + written)
                        written += count
                    }
                }
                if (input.read() != -1) throw IOException("Payload operation exceeds destination extents")
                encoded.finish()
            }
        }
    }

    private fun destinationRanges(operation: InstallOperation, size: Long): List<Pair<Long, Long>> {
        if (operation.dstExtentsCount == 0) throw IOException("Payload operation has no destination extents")
        var total = 0L
        return operation.dstExtentsList.map { extent ->
            val offset = PayloadReader.blockBytes(extent.startBlock, manifest.blockSize)
            val length = PayloadReader.blockBytes(extent.numBlocks, manifest.blockSize)
            PayloadReader.boundedLength(length, size)
            PayloadReader.range(offset, length, size)
            PayloadReader.range(total, length, size)
            total += length
            offset to length
        }
    }

    private fun writeFully(output: FileChannel, buffer: ByteBuffer, offset: Long) {
        var position = offset
        while (buffer.hasRemaining()) {
            val count = output.write(buffer, position)
            if (count <= 0) throw IOException("Cannot write extracted partition")
            position += count
        }
    }

    @Suppress("DEPRECATION")
    private fun xzStream(input: InputStream) = XZCompressorInputStream(input, false, 64 * 1024)

    companion object {
        // KMI comes from the kernel block; init_boot only carries the ramdisk.
        private val BOOT_PARTITION_NAMES = listOf("boot", "vendor_kernel_boot")
        private val KMI_OPERATION_TYPES = setOf(
            InstallOperation.Type.REPLACE,
            InstallOperation.Type.REPLACE_BZ,
            InstallOperation.Type.REPLACE_XZ,
        )
        private const val KMI_PROBE_LIMIT = 24L * 1024 * 1024
        private const val HEADER_PROBE_LIMIT = 64L * 1024
    }
}
