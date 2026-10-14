package com.shixiaoshi0417.lunexsu.data.themestore

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Completed revisions only. Download staging is outside this tree and never appears installed. */
internal class ThemeLibrary(private val root: File) {
    @Synchronized
    fun scan(): List<LocalTheme> = records().sortedByDescending { it.second }
        .distinctBy { it.first.id }.map { it.first }

    @Synchronized
    fun publish(stage: File, theme: RemoteTheme): LocalTheme {
        val old = records()
        val file = stage.listFiles()?.singleOrNull { it.isFile && it.name.endsWith(".fpt") }
            ?: throw IOException("Missing downloaded theme")
        val sequence = maxOf(System.currentTimeMillis(), (old.maxOfOrNull { it.second } ?: 0L) + 1L)
        val json = JSONObject().apply {
            put("id", theme.id)
            put("name", theme.name)
            put("author", theme.author)
            put("description", theme.description)
            put("version", theme.version)
            put("type", theme.type)
            put("source", theme.source)
            put("previewUrl", theme.previewUrl)
            put("downloadUrl", theme.downloadUrl)
            put("fileName", file.name)
            put("publishedAt", sequence)
        }
        File(stage, META_FILE_NAME).writeText(json.toString())
        val bucket = File(root, MessageDigest.getInstance("SHA-256")
            .digest(theme.id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
        if (!bucket.isDirectory && !bucket.mkdirs()) throw IOException("Cannot create theme directory")
        val target = File(bucket, UUID.randomUUID().toString())
        // All three files become visible together; failure leaves the old revision untouched.
        if (!stage.renameTo(target)) throw IOException("Cannot publish downloaded theme")
        val local = requireNotNull(read(target)).first
        // A killed process between rename and cleanup is handled by scan's sequence/ID deduplication.
        old.filter { it.first.id == theme.id }.forEach { remove(it.first) }
        return local
    }

    @Synchronized
    fun remove(theme: LocalTheme) {
        val directory = File(theme.localPath).parentFile?.canonicalFile ?: return
        if (directory.parentFile?.parentFile == root.canonicalFile) {
            directory.deleteRecursively()
            directory.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun records(): List<Pair<LocalTheme, Long>> = root.listFiles().orEmpty()
        .filter { it.isDirectory }.flatMap { bucket ->
            bucket.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull(::read)
        }

    private fun read(directory: File): Pair<LocalTheme, Long>? = runCatching {
        // No fallback identity: a missing, incomplete or corrupt marker is not an installed theme.
        val json = JSONObject(File(directory, META_FILE_NAME).readText())
        val id = json.getString("id").takeIf { it.isNotBlank() } ?: return null
        val fileName = json.optString("fileName", "")
        val file = if (fileName.isNotBlank()) {
            if (File(fileName).name != fileName || '/' in fileName || '\\' in fileName) return null
            File(directory, fileName)
        } else {
            // Read completed libraries created by the first store release.
            directory.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".fpt") } ?: return null
        }
        if (!file.isFile || file.length() == 0L) return null
        val preview = File(directory, PREVIEW_FILE_NAME)
        LocalTheme(
            id = id,
            name = json.optString("name", file.nameWithoutExtension),
            author = json.optString("author", ""),
            description = json.optString("description", ""),
            version = json.optString("version", ""),
            previewUrl = normalizeStoreUrl(json.optString("previewUrl", "")),
            downloadUrl = normalizeStoreUrl(json.optString("downloadUrl", "")),
            type = json.optString("type", THEME_TYPE_PHONE),
            source = json.optString("source", THEME_SOURCE_THIRD_PARTY),
            localPath = file.absolutePath,
            previewImagePath = preview.takeIf { it.isFile }?.absolutePath.orEmpty(),
        ) to json.optLong("publishedAt", 0L)
    }.getOrNull()

    companion object {
        const val META_FILE_NAME = "theme_meta.json"
        const val PREVIEW_FILE_NAME = "Theme.webp"
    }
}
