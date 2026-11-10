package com.shixiaoshi0417.lunexsu.ui.webui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.util.DownloadService
import com.shixiaoshi0417.lunexsu.ui.util.resolveDownloadMimeType
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "WebUIDownload"
private const val DEFAULT_WEBUI_DOWNLOAD_NAME = "download.bin"
private const val MAX_FILE_NAME_LENGTH = 128
private val MIME_TYPE = Regex("^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}$")

/** Strip any directory part and control characters; never let a page choose a path. */
internal fun sanitizeWebUIDownloadFileName(requested: String?): String {
    val candidate = requested.orEmpty()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filterNot { it.isISOControl() || it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|' }
        .trim()
        .trimStart('.')
        .take(MAX_FILE_NAME_LENGTH)
        .trim()
    return candidate.ifEmpty { DEFAULT_WEBUI_DOWNLOAD_NAME }
}

internal fun sanitizeWebUIDownloadMimeType(fileName: String, requested: String?): String =
    resolveDownloadMimeType(fileName, requested?.trim()?.takeIf { MIME_TYPE.matches(it) })

/**
 * `ksu_download`: saves files a module WebUI hands over (blob:/data:/local, see download.js)
 * into the public Downloads folder through MediaStore. It cannot open URLs, pick a
 * directory or overwrite an existing file (MediaStore picks a unique name). Like `ksu`,
 * Android exposes it to every frame, so it relies on the same local-only WebUI policy.
 */
class WebUIDownloadInterface(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val output: OutputStream,
        var written: Long = 0,
    )

    companion object {
        private const val VERSION = 1
        private const val MAX_SESSIONS = 4
        private const val MAX_CHUNK_BYTES = 1024 * 1024
        private const val MAX_FILE_BYTES = 1024L * 1024 * 1024
        // Separate from DownloadService's notification ids.
        private const val NOTIFICATION_ID_BASE = 200000
        private val nextNotificationId = AtomicInteger()
    }

    @JavascriptInterface
    fun version(): Int = VERSION

    /** Returns a session id, or "" when the download is refused. */
    @JavascriptInterface
    fun begin(fileName: String?, mimeType: String?): String {
        if (sessions.size >= MAX_SESSIONS) return ""
        val displayName = sanitizeWebUIDownloadFileName(fileName)
        val type = sanitizeWebUIDownloadMimeType(displayName, mimeType)
        val resolver = context.contentResolver
        var uri: Uri? = null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("Failed to create download")
            val output = resolver.openOutputStream(uri!!) ?: error("Failed to open download")
            val id = UUID.randomUUID().toString()
            sessions[id] = Session(uri!!, displayName, type, output.buffered(64 * 1024))
            id
        }.getOrElse {
            Log.e(TAG, "Failed to start $displayName", it)
            uri?.let { pending -> runCatching { resolver.delete(pending, null, null) } }
            ""
        }
    }

    @JavascriptInterface
    fun write(id: String, base64Chunk: String): Boolean {
        val session = sessions[id] ?: return false
        return runCatching {
            synchronized(session) {
                val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
                require(bytes.size <= MAX_CHUNK_BYTES) { "Chunk too large" }
                require(session.written + bytes.size <= MAX_FILE_BYTES) { "File too large" }
                session.output.write(bytes)
                session.written += bytes.size
            }
            true
        }.getOrElse {
            Log.e(TAG, "Failed to write ${session.displayName}", it)
            fail(id)
            false
        }
    }

    @JavascriptInterface
    fun finish(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        return runCatching {
            synchronized(session) { session.output.close() }
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(context.contentResolver.update(session.uri, published, null, null) == 1) { "Failed to publish download" }
            notifyCompleted(session)
            toast(context.getString(R.string.download_complete_content, session.displayName))
            true
        }.getOrElse {
            Log.e(TAG, "Failed to finish ${session.displayName}", it)
            discard(session)
            toast(context.getString(R.string.download_failed_content, session.displayName))
            false
        }
    }

    @JavascriptInterface
    fun cancel(id: String) {
        sessions.remove(id)?.let(::discard)
    }

    fun destroy() {
        sessions.keys.toList().forEach(::cancel)
    }

    private fun fail(id: String) {
        sessions.remove(id)?.let { session ->
            discard(session)
            toast(context.getString(R.string.download_failed_content, session.displayName))
        }
    }

    private fun discard(session: Session) {
        runCatching { synchronized(session) { session.output.close() } }
        runCatching { context.contentResolver.delete(session.uri, null, null) }
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun notifyCompleted(session: Session) = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                DownloadService.CHANNEL_ID,
                context.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val notificationId = NOTIFICATION_ID_BASE + (nextNotificationId.getAndIncrement() and 0xffff)
        val open = Intent.createChooser(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(session.uri, session.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            context.getString(R.string.open),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notificationId,
            NotificationCompat.Builder(context, DownloadService.CHANNEL_ID)
                .setContentTitle(context.getString(R.string.download_complete_title))
                .setContentText(context.getString(R.string.download_complete_content, session.displayName))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        )
    }.onFailure { Log.w(TAG, "Completion notification failed", it) }
}
