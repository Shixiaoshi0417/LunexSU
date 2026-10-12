package com.shixiaoshi0417.lunexsu.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shixiaoshi0417.lunexsu.ui.navigation3.LocalNavigator
import com.shixiaoshi0417.lunexsu.ui.navigation3.Route
import com.shixiaoshi0417.lunexsu.ui.navigation3.rememberNavigator
import com.shixiaoshi0417.lunexsu.ui.screen.about.AboutScreen
import com.shixiaoshi0417.lunexsu.ui.screen.colorpalette.ColorPaletteScreen
import com.shixiaoshi0417.lunexsu.ui.screen.font.FontScreen
import com.shixiaoshi0417.lunexsu.ui.screen.themestore.MyThemesScreen
import com.shixiaoshi0417.lunexsu.ui.screen.themestore.ThemeStoreScreen
import com.shixiaoshi0417.lunexsu.ui.theme.LunexSUTheme
import com.shixiaoshi0417.lunexsu.ui.theme.LocalColorMode
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalThemePackBackground
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemePackState
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemePackWallpaper
import com.shixiaoshi0417.lunexsu.ui.viewmodel.MainActivityViewModel
import top.yukonga.miuix.kmp.nav.core.NavDisplay

/** Hosts the actual theme/About screens on a Core-less emulator without changing capability gates. */
class ThemeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemePackState.reload(this)
        val initial = when (intent.getStringExtra("page")) {
            "about" -> Route.About
            "store" -> Route.ThemeStore
            else -> Route.ColorPalette
        }
        setContent {
            val model = viewModel<MainActivityViewModel>()
            val state by model.uiState.collectAsStateWithLifecycle()
            val navigator = rememberNavigator(initial)
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalUiMode provides state.uiMode,
                LocalColorMode provides state.appSettings.colorMode.value,
                LocalThemePackBackground provides ThemePackState.hasBackground,
            ) {
                LunexSUTheme(appSettings = state.appSettings, uiMode = state.uiMode) {
                    Box(Modifier.fillMaxSize()) {
                        ThemePackWallpaper(page = 3)
                        NavDisplay(backStack = navigator.backStack, onBack = { finish() }) {
                            entry<Route.ColorPalette> { ColorPaletteScreen() }
                            entry<Route.Font> { FontScreen() }
                            entry<Route.ThemeStore> { ThemeStoreScreen() }
                            entry<Route.MyThemes> { MyThemesScreen() }
                            entry<Route.About> { AboutScreen() }
                        }
                    }
                }
            }
        }
    }
}
