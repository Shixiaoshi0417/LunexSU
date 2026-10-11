package com.shixiaoshi0417.lunexsu.ui.theme.pack

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONObject
import java.io.File

internal data class ActiveThemePack(val directory: File, val plan: ThemePackPlan)

internal object ThemePackState {
    var active: ActiveThemePack? by mutableStateOf(null)
        private set
    val hasBackground: Boolean get() = active?.plan?.background?.enabled == true

    /** Pager page whose wallpaper is on screen; content polarity follows it, as in FinaSU. */
    var visiblePage by mutableIntStateOf(0)

    fun reload(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        active = runCatching {
            val directory = ThemePackImporter.packDirectory(context, prefs.getString(ThemePackImporter.ACTIVE_PACK, null)
                ?: return@runCatching null)?.takeIf { it.isDirectory } ?: return@runCatching null
            val json = JSONObject(prefs.getString(ThemePackImporter.ACTIVE_JSON, null) ?: return@runCatching null)
            val files = directory.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: emptySet()
            ActiveThemePack(directory, ThemePackSchema.parse(json, files))
        }.getOrNull()
    }
}

// Only MainActivity enables transparent wallpaper surfaces; module WebUI retains opaque colors.
internal val LocalThemePackBackground = staticCompositionLocalOf { false }

/**
 * The dim actually painted over the wallpaper. The Material theme may raise it above the pack's
 * requested dim to keep text legible; null where no theme computed it (falls back to the request).
 */
internal val LocalWallpaperDim = compositionLocalOf<Float?> { null }
