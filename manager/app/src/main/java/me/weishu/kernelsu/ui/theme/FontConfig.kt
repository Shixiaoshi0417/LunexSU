// Adapted from FolkSU (https://github.com/LyraVoid/FolkSU), GPL-3.0.
package me.weishu.kernelsu.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * How the application renders text: [SYSTEM_DEFAULT] keeps the platform font, [CUSTOM] uses a
 * font file the user imported.
 */
enum class FontMode {
    SYSTEM_DEFAULT,
    CUSTOM;

    companion object {
        fun fromName(name: String?): FontMode? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The app's font selection, shared by the Material and MIUIX themes.
 *
 * The state is Compose state, so both themes rebuild their typography the moment it changes; it is
 * persisted in the same `settings` preferences as the rest of the theme. The imported file lives in
 * the app's private files dir under a fresh name per import, so a cached Typeface is never served
 * for a replaced file.
 */
object FontConfig {
    private const val PREFS_NAME = "settings"
    private const val KEY_FONT_MODE = "font_mode"
    private const val KEY_CUSTOM_FONT_FILE = "custom_font_file"
    private const val FILE_PREFIX = "custom_font_"
    private const val TAG = "FontConfig"
    private val imports = FontImportGuard()

    var fontMode: FontMode by mutableStateOf(FontMode.SYSTEM_DEFAULT)
        private set

    var customFontFilename: String? by mutableStateOf(null)
        private set

    @Volatile
    private var loaded = false

    /** Reads the persisted selection once; a missing custom file falls back to the system font. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val storedMode = FontMode.fromName(prefs.getString(KEY_FONT_MODE, null)) ?: FontMode.SYSTEM_DEFAULT
            val storedFile = prefs.getString(KEY_CUSTOM_FONT_FILE, null)
            val fileExists = storedFile?.let { File(context.filesDir, it).isFile } == true
            customFontFilename = storedFile.takeIf { fileExists }
            fontMode = if (storedMode == FontMode.CUSTOM && !fileExists) FontMode.SYSTEM_DEFAULT else storedMode
            if (fontMode != storedMode || customFontFilename != storedFile) save(context)
            loaded = true
        }
    }

    /**
     * Selects a mode. CUSTOM is only accepted once a font file was imported, so the UI never ends
     * up in a mode that silently renders the system font.
     */
    fun setFontMode(context: Context, mode: FontMode): Boolean {
        ensureLoaded(context)
        return imports.change {
            if (mode == FontMode.CUSTOM && customFontFilename == null) {
                false
            } else {
                fontMode = mode
                invalidateFontCache()
                save(context)
                true
            }
        }
    }

    /** Capture the user's choice before dispatching the file read to an IO worker. */
    fun beginImport(context: Context): Long {
        ensureLoaded(context)
        return imports.begin()
    }

    /** A theme publishes its font selection in the same preference batch as its palette. */
    internal fun reloadFromPreferences(context: Context) {
        imports.change {
            loaded = false
            invalidateFontCache()
            ensureLoaded(context)
        }
    }

    /**
     * Copies the picked file into private storage and switches to it. The file is rejected unless
     * the platform can parse it as a font, so an arbitrary document cannot be "applied".
     */
    suspend fun importFont(context: Context, uri: Uri, token: Long): Boolean {
        ensureLoaded(context)
        val newFilename = "$FILE_PREFIX${UUID.randomUUID()}.ttf"
        val destFile = File(context.filesDir, newFilename)
        var applied = false
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied || !isLoadableFont(destFile)) {
                destFile.delete()
                return false
            }
            // Selection, Compose state and the Typeface cache are all updated on the UI thread.
            withContext(Dispatchers.Main) {
                applied = imports.commit(token) {
                    val oldFilename = customFontFilename
                    customFontFilename = newFilename
                    fontMode = FontMode.CUSTOM
                    invalidateFontCache()
                    save(context)
                    oldFilename?.let { deleteStandaloneFont(context, it) }
                }
            }
            if (!applied) destFile.delete()
            applied
        } catch (e: Exception) {
            if (!applied) destFile.delete()
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to import font", e)
            false
        }
    }

    /** Deletes the imported file and returns to the system font. */
    fun restoreDefault(context: Context) {
        ensureLoaded(context)
        imports.change {
            customFontFilename?.let { deleteStandaloneFont(context, it) }
            customFontFilename = null
            fontMode = FontMode.SYSTEM_DEFAULT
            invalidateFontCache()
            save(context)
        }
    }

    private fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_FONT_MODE, fontMode.name)
            putString(KEY_CUSTOM_FONT_FILE, customFontFilename)
        }
    }

    private fun deleteStandaloneFont(context: Context, filename: String) {
        // Pack assets remain available to its immutable configuration until pack GC removes them.
        if (filename.startsWith(FILE_PREFIX) && '/' !in filename && '\\' !in filename) {
            File(context.filesDir, filename).delete()
        }
    }

    private fun isLoadableFont(file: File): Boolean = runCatching {
        val typeface = Typeface.Builder(file).build()
        typeface != null
    }.getOrDefault(false)

    // Font cache, so Typeface parsing does not run on every recomposition.
    private var cachedFontFamily: FontFamily? = null
    private var cachedFilename: String? = null
    private var cachedMode: FontMode? = null

    fun getFontFamily(context: Context): FontFamily {
        ensureLoaded(context)
        val cached = cachedFontFamily
        if (cached != null && fontMode == cachedMode && customFontFilename == cachedFilename) return cached

        val family = when (fontMode) {
            FontMode.SYSTEM_DEFAULT -> FontFamily.Default
            FontMode.CUSTOM -> loadCustomFont(context)
        }
        cachedMode = fontMode
        cachedFilename = customFontFilename
        cachedFontFamily = family
        return family
    }

    private fun loadCustomFont(context: Context): FontFamily {
        val filename = customFontFilename ?: return FontFamily.Default
        val file = File(context.filesDir, filename)
        if (!file.isFile) return FontFamily.Default
        return try {
            FontFamily(Typeface.createFromFile(file))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom font", e)
            FontFamily.Default
        }
    }

    private fun invalidateFontCache() {
        cachedFilename = null
        cachedFontFamily = null
        cachedMode = null
    }
}

/** The font family the user picked, recomputed whenever the selection changes. */
@Composable
fun rememberAppFontFamily(): FontFamily {
    val context = LocalContext.current.applicationContext
    remember(context) { FontConfig.ensureLoaded(context) }
    val mode = FontConfig.fontMode
    val file = FontConfig.customFontFilename
    return remember(mode, file) { FontConfig.getFontFamily(context) }
}
