// FPT compatibility adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.theme.pack

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.shixiaoshi0417.lunexsu.data.model.HomeLayoutStyle
import com.shixiaoshi0417.lunexsu.ui.theme.ColorGenerationMode
import com.shixiaoshi0417.lunexsu.ui.theme.FontConfig
import java.io.Closeable
import java.io.File
import java.util.UUID

internal class PreparedThemePack(
    val directory: File,
    val json: String,
    val files: Map<String, File>,
    val plan: ThemePackPlan,
) : Closeable {
    override fun close() { directory.deleteRecursively() }
}

internal object ThemePackImporter {
    private val mutex = Mutex()
    const val ACTIVE_PACK = "fpt_active_pack"
    const val ACTIVE_JSON = "fpt_active_json"

    suspend fun prepare(context: Context, uri: Uri): PreparedThemePack = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "fpt-${UUID.randomUUID()}")
        try {
            val (json, files) = context.contentResolver.openInputStream(uri)?.use {
                FptContainer.read(it, directory)
            } ?: error("Cannot read theme")
            val plan = ThemePackSchema.parse(json, files.keys)
            val backgrounds = plan.background.images.values.toSet()
            files.filterKeys { it in backgrounds }.forEach { (name, file) ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "Invalid image: $name" }
            }
            if (plan.fontMode == "custom") {
                val font = files.getValue(requireNotNull(plan.font))
                require(font.length() <= FontConfig.MAX_FONT_BYTES) { "Font exceeds size limit" }
                require(Typeface.Builder(font).build() != null) { "Invalid font" }
            }
            PreparedThemePack(directory, json.toString(), files.toMap(), plan)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    /** Assets become visible only after the complete, validated preference batch is published. */
    suspend fun apply(context: Context, theme: PreparedThemePack): Result<Unit> = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                check(theme.directory.isDirectory) { "Prepared theme has expired" }
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val id = UUID.randomUUID().toString()
                val directory = packDirectory(context, id) ?: error("Invalid pack directory")
                check(directory.mkdirs()) { "Cannot create theme directory" }
                try {
                    val selected = theme.plan.background.images.values.toMutableSet()
                    if (theme.plan.fontMode == "custom") selected += requireNotNull(theme.plan.font)
                    selected.forEach { name -> theme.files.getValue(name).copyTo(File(directory, name)) }
                    val changes = theme.plan.settings.toMutableMap()
                    changes[ACTIVE_PACK] = id
                    changes[ACTIVE_JSON] = theme.json
                    val removals = mutableSetOf<String>()
                    when (theme.plan.fontMode) {
                        "custom" -> {
                            changes["font_mode"] = "CUSTOM"
                            changes["custom_font_file"] = "theme_packs/$id/${theme.plan.font}"
                        }
                        "system" -> { changes["font_mode"] = "SYSTEM_DEFAULT"; removals += "custom_font_file" }
                    }
                    val replacedFont = prefs.getString("custom_font_file", null)
                    publish(prefs, changes, removals)
                    withContext(Dispatchers.Main) {
                        // Invalidates older in-flight font picks before reloading Compose typography.
                        FontConfig.reloadFromPreferences(context)
                        // Pack GC only scans theme_packs, so a displaced standalone font is removed here.
                        FontConfig.discardReplacedFont(context, replacedFont)
                        ThemePackState.reload(context)
                    }
                } finally {
                    // commit() can update in-memory preferences even when disk persistence fails.
                    // Keep any directory still referenced by the current preference snapshot.
                    runCatching { cleanup(context, prefs) }
                }
            }
        }
    }

    suspend fun reset(context: Context): Result<Unit> = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val replacedFont = prefs.getString("custom_font_file", null)
                publish(prefs, mapOf(
                    "color_mode" to 0, "key_color" to 0, "color_style" to "TonalSpot",
                    "color_spec" to "SPEC_2025", "color_contrast" to "STANDARD",
                    "color_generation_mode" to ColorGenerationMode.CUSTOM,
                    "use_dynamic_color" to true, "miuix_monet" to false, "amoled_black" to false,
                    "home_layout_style" to "circle", "stats_top_layout" to HomeLayoutStyle.STATS_TOP_STATUS,
                    "home_hide_working_mode" to false, "font_mode" to "SYSTEM_DEFAULT",
                ), setOf(ACTIVE_PACK, ACTIVE_JSON, "custom_font_file"))
                withContext(Dispatchers.Main) {
                    FontConfig.reloadFromPreferences(context)
                    FontConfig.discardReplacedFont(context, replacedFont)
                    ThemePackState.reload(context)
                }
                cleanup(context, prefs)
            }
        }
    }

    private fun publish(prefs: SharedPreferences, changes: Map<String, Any>, removals: Set<String>) {
        val keys = changes.keys + removals
        val previous = prefs.all.filterKeys { it in keys }
        val editor = prefs.edit()
        removals.forEach(editor::remove)
        changes.forEach { (key, value) -> editor.putValue(key, value) }
        if (!editor.commit()) {
            val rollback = prefs.edit()
            keys.forEach(rollback::remove)
            previous.forEach { (key, value) -> if (value != null) rollback.putValue(key, value) }
            rollback.commit()
            error("Cannot persist theme settings")
        }
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any) {
        when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
            else -> error("Unsupported theme setting type")
        }
    }

    private fun cleanup(context: Context, prefs: SharedPreferences) {
        val keep = mutableSetOf(prefs.getString(ACTIVE_PACK, null))
        prefs.getString("custom_font_file", null)?.takeIf { it.startsWith("theme_packs/") }
            ?.split('/')?.getOrNull(1)?.let { keep += it }
        File(context.filesDir, "theme_packs").listFiles()?.forEach { file ->
            if (file.name !in keep && packDirectory(context, file.name) == file.canonicalFile) file.deleteRecursively()
        }
    }

    fun packDirectory(context: Context, id: String): File? {
        if (!id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) return null
        val root = File(context.filesDir, "theme_packs").canonicalFile
        val directory = File(root, id).canonicalFile
        return directory.takeIf { it.parentFile == root }
    }
}
