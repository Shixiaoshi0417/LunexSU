package com.shixiaoshi0417.lunexsu.data

import java.io.File
import java.nio.file.Files

internal object LkmFilePreparation {
    fun requireKmi(selected: String?, detected: String?): String {
        val kmi = selected ?: detected
        require(kmi != null && Regex("android[0-9]+-[0-9]+\\.[0-9]+").matches(kmi)) {
            "Missing or invalid KMI"
        }
        return kmi
    }

    /** The extractor may replace its source as root. Only read it; write to a new App-owned file. */
    fun prepare(cacheDir: File, record: ByteArray?, populate: (source: File) -> Unit): File {
        val source = Files.createTempFile(cacheDir.toPath(), "kernelsu-source-", ".ko").toFile()
        var output: File? = null
        var complete = false
        try {
            populate(source)
            val prepared = Files.createTempFile(cacheDir.toPath(), "kernelsu-lkm-", ".ko").toFile()
            output = prepared
            if (record == null) {
                source.inputStream().use { input ->
                    prepared.outputStream().use { destination -> input.copyTo(destination) }
                }
            } else {
                prepared.writeBytes(Superkey.patch(source.readBytes(), record))
            }
            complete = true
            return prepared
        } finally {
            source.delete()
            if (!complete) output?.delete()
        }
    }
}
