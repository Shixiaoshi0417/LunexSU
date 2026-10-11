package com.shixiaoshi0417.lunexsu.ui.theme.pack

import android.content.Context
import androidx.compose.runtime.getValue
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
