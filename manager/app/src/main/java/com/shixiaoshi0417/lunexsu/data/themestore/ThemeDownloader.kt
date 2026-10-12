// Theme store adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.data.themestore

import android.content.Context
import com.shixiaoshi0417.lunexsu.ksuApp
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request

/**
 * Downloads store themes into private staging, then publishes a completed revision in `themes`.
 *
 * The `.fpt` payload counts for 70% of the progress and the `Theme.webp` preview for the remaining
 * 30%; a failed preview is not fatal. Downloads can be paused, resumed (HTTP `Range`) and cancelled,
 * and every finished theme writes a `theme_meta.json` so it can be listed offline. Nothing is
 * applied here: the package goes through the regular FPT import confirmation afterwards.
 */
class ThemeDownloader(private val context: Context) {

    private val library = ThemeLibrary(File(context.filesDir, "themes"))
    internal fun scanLocalThemes(): List<LocalTheme> = library.scan()
    internal fun deleteLocalTheme(theme: LocalTheme) = library.remove(theme)

    private val client = ksuApp.okhttpClient.newBuilder()
        .cache(null)
        .connectTimeout(DOWNLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(MAX_CONCURRENT)

    private val downloadTasks = ConcurrentHashMap<String, DownloadTask>()

    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloadProgress.asStateFlow()

    fun pauseDownload(themeId: String) {
        downloadTasks[themeId]?.isPaused = true
    }

    fun cancelDownload(themeId: String) {
        val task = downloadTasks.remove(themeId)
        // Signal first so the running writer stops at its next chunk, then drop the partial files.
        task?.isCancelled = true
        if (task != null) {
            task.stage.deleteRecursively()
        }
        _downloadProgress.update { it - themeId }
    }

    fun downloadTheme(theme: RemoteTheme): Flow<DownloadProgress> = flow {
        val themeId = theme.id
        val existing = downloadTasks[themeId]
        if (existing != null && !existing.isPaused) {
            emit(DownloadProgress(themeId, 0f, 0f, 0f, DownloadStatus.PENDING))
            return@flow
        }
        val task = existing ?: DownloadTask(File(context.filesDir, "theme_downloads/${UUID.randomUUID()}"))
            .also { downloadTasks[themeId] = it }
        task.isPaused = false

        semaphore.withPermit {
            try {
                if (!isSafeDownloadUrl(theme.downloadUrl)) {
                    fail(themeId, "Invalid download URL")
                    emit(current(themeId))
                    return@withPermit
                }

                updateProgress(themeId, 0f, 0f, 0f, DownloadStatus.DOWNLOADING)
                emit(current(themeId))

                val themeResult = downloadFileWithRetry(
                    url = theme.downloadUrl,
                    file = File(task.stage, "${sanitizeFilename(theme.name)}.fpt"),
                    taskId = themeId,
                    isThemeFile = true,
                ) { progress ->
                    updateProgress(themeId, progress, 0f, progress * 0.7f, DownloadStatus.DOWNLOADING)
                }
                if (themeResult.paused) {
                    updateProgress(themeId, 0f, 0f, 0f, DownloadStatus.PAUSED)
                    emit(current(themeId))
                    return@withPermit
                }
                if (!themeResult.success) {
                    // A cancelled task already cleared its progress; do not report it as failed.
                    if (task.isCancelled) return@withPermit
                    fail(themeId, themeResult.error)
                    emit(current(themeId))
                    return@withPermit
                }

                if (theme.previewUrl.isNotBlank() && isSafeDownloadUrl(theme.previewUrl)) {
                    val previewResult = downloadFileWithRetry(
                        url = theme.previewUrl,
                        file = File(task.stage, ThemeLibrary.PREVIEW_FILE_NAME),
                        taskId = themeId,
                        isThemeFile = false,
                    ) { progress ->
                        updateProgress(themeId, 1f, progress, 0.7f + progress * 0.3f, DownloadStatus.DOWNLOADING)
                    }
                    if (previewResult.paused) {
                        updateProgress(themeId, 1f, 0f, 0.7f, DownloadStatus.PAUSED)
                        emit(current(themeId))
                        return@withPermit
                    }
                    // A failed preview is not fatal; the theme file is what matters.
                }

                if (task.isCancelled) return@withPermit
                library.publish(task.stage, theme)
                updateProgress(themeId, 1f, 1f, 1f, DownloadStatus.COMPLETED)
                emit(current(themeId))
                downloadTasks.remove(themeId)
            } catch (e: IOException) {
                if (task.isCancelled) return@withPermit
                fail(themeId, describeException(e))
                emit(current(themeId))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun current(themeId: String): DownloadProgress =
        _downloadProgress.value[themeId] ?: DownloadProgress(themeId, 0f, 0f, 0f, DownloadStatus.FAILED)

    private fun fail(themeId: String, error: String?) {
        updateProgress(themeId, 0f, 0f, 0f, DownloadStatus.FAILED, error)
        // Drop the task so a later retry starts clean instead of reporting "already downloading".
        downloadTasks.remove(themeId)?.stage?.deleteRecursively()
    }

    private suspend fun downloadFileWithRetry(
        url: String,
        file: File,
        taskId: String,
        isThemeFile: Boolean,
        onProgress: (Float) -> Unit,
    ): DownloadFileResult {
        // Download to a temp sibling first: a killed process then leaves a `.tmp` that the local
        // scanner ignores, instead of a half-written `.fpt` that looks installed.
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        var lastError: String? = null
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                val expected = downloadOnce(url, temp, taskId, if (isThemeFile) MAX_THEME_BYTES else MAX_PREVIEW_BYTES, onProgress)
                validateDownloadedFile(temp, isThemeFile, expected)
                commitDownload(temp, file)
                return DownloadFileResult(true)
            } catch (_: DownloadPausedException) {
                // Keep the temp file so a resume can continue from its length.
                return DownloadFileResult(false, paused = true)
            } catch (e: FatalDownloadException) {
                temp.delete()
                return DownloadFileResult(false, describeException(e))
            } catch (e: IOException) {
                lastError = describeException(e)
                attempt++
                if (downloadTasks[taskId]?.isCancelled != false) {
                    temp.delete()
                    return DownloadFileResult(false, "Download cancelled")
                }
                // A corrupt partial file must not be resumed by the next attempt.
                temp.delete()
                if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
            }
        }
        temp.delete()
        return DownloadFileResult(false, lastError)
    }

    private fun commitDownload(temp: File, target: File) {
        ensureDirectory(target.parentFile ?: throw IOException("No parent directory for $target"))
        if (target.exists()) target.delete()
        if (temp.renameTo(target)) return
        temp.copyTo(target, overwrite = true)
        temp.delete()
    }

    private fun downloadOnce(
        url: String,
        file: File,
        taskId: String,
        maxBytes: Long,
        onProgress: (Float) -> Unit,
    ): Long {
        ensureDirectory(file.parentFile ?: throw IOException("No parent directory for $file"))
        if (file.isDirectory) file.deleteRecursively()

        val downloadedBytes = file.length()
        val requestBuilder = Request.Builder().url(url)
        if (downloadedBytes > 0) {
            requestBuilder.header("Range", "bytes=$downloadedBytes-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

            val resume = response.code == 206 && downloadedBytes > 0
            val contentLength = response.body.contentLength()
            val total = if (contentLength > 0) {
                if (resume) downloadedBytes + contentLength else contentLength
            } else {
                -1L
            }
            if (total > maxBytes) throw FatalDownloadException("File too large")

            FileOutputStream(file, resume).use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = if (resume) downloadedBytes else 0L
                    while (true) {
                        val task = downloadTasks[taskId]
                        if (task == null || task.isCancelled) throw IOException("Download cancelled")
                        if (task.isPaused) throw DownloadPausedException()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written > maxBytes) throw FatalDownloadException("File too large")
                        val progress = if (total > 0) written.toFloat() / total else estimateUnknownProgress(written)
                        onProgress(progress.coerceIn(0f, 1f))
                    }
                }
            }
            return total
        }
    }

    private fun estimateUnknownProgress(downloaded: Long): Float =
        (1.0 - 1.0 / (1.0 + downloaded / 256.0 / 1024.0)).toFloat().coerceIn(0f, 0.99f)

    private fun updateProgress(
        themeId: String,
        fileProgress: Float,
        imageProgress: Float,
        overallProgress: Float,
        status: DownloadStatus,
        errorMessage: String? = null,
    ) {
        _downloadProgress.update { current ->
            current + (themeId to DownloadProgress(themeId, fileProgress, imageProgress, overallProgress, status, errorMessage))
        }
    }

    private fun ensureDirectory(dir: File) {
        if (dir.isDirectory) return
        if (dir.exists()) dir.delete()
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("Cannot create directory: ${dir.absolutePath}")
        }
    }

    private fun describeException(e: Exception): String =
        e.message ?: e::class.simpleName ?: "Unknown error"

    private data class DownloadFileResult(
        val success: Boolean,
        val error: String? = null,
        val paused: Boolean = false,
    )

    private class DownloadTask(val stage: File) {
        @Volatile var isCancelled = false
        @Volatile var isPaused = false
    }

    private class DownloadPausedException : IOException("Download paused")

    /** Retrying cannot help, e.g. the file exceeds the importer's size limit. */
    private class FatalDownloadException(message: String) : IOException(message)

    companion object {
        private const val DOWNLOAD_TIMEOUT_SEC = 300L
        private const val BUFFER_SIZE = 8192
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val MAX_CONCURRENT = 3
        private const val TEMP_SUFFIX = ".tmp"
        // Mirrors FptContainer.MAX_CONTAINER; the importer rejects anything larger anyway.
        private const val MAX_THEME_BYTES = 1056L * 1024 * 1024
        private const val MAX_PREVIEW_BYTES = 32L * 1024 * 1024

        /** Path-safe directory/file name; never empty, never `.` or `..`. */
        fun sanitizeFilename(name: String): String {
            val cleaned = name
                .replace(Regex("""[<>:"/\\|?*\u0000-\u001f]"""), "_")
                .trim()
                .trim('.')
            return cleaned.ifBlank { "unnamed" }
        }
    }
}

// Adapted from FinaSU d869a70e: an IV has no restricted byte values.
internal fun validateDownloadedFile(file: File, isThemeFile: Boolean, expectedSize: Long) {
    val size = file.length()
    if (!file.isFile || size == 0L) throw IOException("Downloaded file is empty")
    if (expectedSize > 0 && size != expectedSize) throw IOException("Size mismatch")
    if (isThemeFile) {
        if (size < 32L) throw IOException("Theme file too small")
        // FPT starts with an unrestricted 16-byte IV, followed by AES-CBC ciphertext.
        // Only check framing here; import validates the decrypted ZIP and theme.json.
        if ((size - 16L) % 16L != 0L) throw IOException("Invalid theme file length")
    }
}
