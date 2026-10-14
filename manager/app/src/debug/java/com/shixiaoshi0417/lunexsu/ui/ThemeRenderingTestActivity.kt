package com.shixiaoshi0417.lunexsu.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.ui.screen.home.HomeCard
import com.shixiaoshi0417.lunexsu.ui.screen.settings.SettingPagerMaterial
import com.shixiaoshi0417.lunexsu.ui.screen.settings.SettingsScreenActions
import com.shixiaoshi0417.lunexsu.ui.screen.settings.SettingsUiState
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFinaThemeUi
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalThemePackBackground
import com.shixiaoshi0417.lunexsu.ui.theme.pack.PackSurfaceId
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemePackImporter
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemePackState
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemeRouteSurface
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Private debug-only pixel fixture; it never changes real settings or kernel state. */
class ThemeRenderingTestActivity : ComponentActivity() {
    private lateinit var fixtureRoot: File
    private lateinit var fixtureContext: Context
    private var settingsScrollState: androidx.compose.foundation.ScrollState? = null

    fun settingsScrollPosition(): Int = settingsScrollState?.value ?: -1
    fun settingsScrollRange(): Int = settingsScrollState?.maxValue ?: 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = UUID.randomUUID().toString()
        fixtureRoot = File(cacheDir, "render-$id").also { it.mkdirs() }
        fixtureContext = object : ContextWrapper(this) {
            override fun getFilesDir() = fixtureRoot
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("render-$id-$name", mode)
        }
        val directory = requireNotNull(ThemePackImporter.packDirectory(fixtureContext, id)).also { it.mkdirs() }
        fun image(name: String, color: Int) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        image("grid_working_card_background.png", android.graphics.Color.GREEN)
        image("background.png", android.graphics.Color.BLUE)
        val scenario = intent.getStringExtra("case") ?: "card"
        val json = JSONObject().put("isGridWorkingCardBackgroundEnabled", true)
            .put("gridWorkingCardBackgroundOpacity", intent.getFloatExtra("opacity", 1f).toDouble())
            .put("gridWorkingCardBackgroundDim", 0)
            .put("isGridWorkingCardCheckHidden", true).put("isGridWorkingCardTextHidden", true)
            .put("isGridWorkingCardModeHidden", true)
            .put("isBackgroundEnabled", scenario == "route_wallpaper").put("backgroundDim", 0)
        check(fixtureContext.getSharedPreferences("settings", 0).edit()
            .putString(ThemePackImporter.ACTIVE_PACK, id)
            .putString(ThemePackImporter.ACTIVE_JSON, json.toString()).commit())
        ThemePackState.reload(fixtureContext)
        setContent {
            CompositionLocalProvider(
                LocalFinaThemeUi provides (scenario != "native" && scenario != "settings_native"),
                LocalThemePackBackground provides (scenario == "route_wallpaper"),
            ) {
                MaterialTheme(colorScheme = lightColorScheme(surface = Color.Blue)) {
                    Box(Modifier.fillMaxSize().background(if (scenario == "card") Color.Blue else Color.Red),
                        contentAlignment = Alignment.Center) {
                        if (scenario.startsWith("settings")) {
                            val scrollState = rememberScrollState().also { settingsScrollState = it }
                            SettingPagerMaterial(
                                uiState = SettingsUiState(),
                                actions = SettingsScreenActions(
                                    onSetCheckUpdate = {}, onSetCheckModuleUpdate = {}, onOpenTheme = {},
                                    onSetUiModeIndex = {}, onOpenProfileTemplate = {}, onSetSuCompatMode = {},
                                    onSetKernelUmountEnabled = {}, onSetSelinuxHideEnabled = {}, onSetSulogEnabled = {},
                                    onSetAdbRootEnabled = {}, onSetDefaultUmountModules = {}, onSetEnableWebDebugging = {},
                                    onSetAutoJailbreak = {}, onSetUseSoftReboot = {}, onOpenAbout = {},
                                ),
                                // Ensure overflow even without a Core; no capability gate or real setting is changed.
                                bottomInnerPadding = 600.dp,
                                scrollState = scrollState,
                            )
                        } else if (scenario == "card") {
                            HomeCard(Modifier.size(160.dp), containerColor = Color.Red, surfaceId = PackSurfaceId.GRID) {
                                // The status tile's icon/title/mode are all hidden, leaving only its constraints.
                                Box(Modifier.fillMaxSize())
                            }
                        } else {
                            ThemeRouteSurface(mainPage = false, visible = true) {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::fixtureContext.isInitialized) {
            fixtureContext.getSharedPreferences("settings", 0).edit().clear().commit()
            fixtureRoot.deleteRecursively()
            ThemePackState.reload(applicationContext)
        }
    }
}
