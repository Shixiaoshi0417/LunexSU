package com.shixiaoshi0417.lunexsu.ui.webui.file

import android.webkit.JavascriptInterface
import com.shixiaoshi0417.lunexsu.BuildConfig
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

const val TAG = "KsuIO"

/** One instance per WebUI bridge: closing a WebUI must not close another WebUI's handles. */
class KsuIO {
    internal val openInputStreams = ConcurrentHashMap<String, ManagedInputStream>()
    internal val openOutputStreams = ConcurrentHashMap<String, OutputStream>()

    private val fileOutputStream = FileOutputStreamInterface(this)
    private val fileInputStream = FileInputStreamInterface(this)
    private val randomAccessFile = RandomAccessFileInterface()

    // SuFile would ask libsu's main shell for root. NoRootDemo must never request su,
    // so every factory fails closed there and version() reports the API as unavailable.
    private fun requireRootIo() {
        check(!BuildConfig.NO_ROOT_DEMO) { "File interface is unavailable in the no-root demo" }
    }

    @JavascriptInterface
    fun version(): Int = if (BuildConfig.NO_ROOT_DEMO) 0 else KSU_IO_VERSION

    @JavascriptInterface
    fun File(path: String): FileInterface {
        requireRootIo()
        return FileInterface(path, this)
    }

    @JavascriptInterface
    fun FileInputStream(): FileInputStreamInterface {
        requireRootIo()
        return fileInputStream
    }

    @JavascriptInterface
    fun FileOutputStream(): FileOutputStreamInterface {
        requireRootIo()
        return fileOutputStream
    }

    @JavascriptInterface
    fun RandomAccessFile(): RandomAccessFileInterface {
        requireRootIo()
        return randomAccessFile
    }

    /**
     * Check if we can open a new stream without exceeding resource limits.
     * Returns true if under limit, false otherwise.
     */
    internal fun canOpenStream(): Boolean {
        return openInputStreams.size + openOutputStreams.size < MAX_OPEN_STREAMS
    }

    fun destroy() {
        fileInputStream.closeAll()
        fileOutputStream.closeAll()
        randomAccessFile.closeAll()
    }

    private companion object {
        const val KSU_IO_VERSION = 1
        const val MAX_OPEN_STREAMS = 50
    }
}
