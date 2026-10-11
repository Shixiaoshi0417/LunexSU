package me.weishu.kernelsu.ui

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
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.navigation3.Route
import me.weishu.kernelsu.ui.navigation3.rememberNavigator
import me.weishu.kernelsu.ui.screen.about.AboutScreen
import me.weishu.kernelsu.ui.screen.colorpalette.ColorPaletteScreen
import me.weishu.kernelsu.ui.screen.font.FontScreen
import me.weishu.kernelsu.ui.theme.KernelSUTheme
import me.weishu.kernelsu.ui.theme.LocalColorMode
import me.weishu.kernelsu.ui.theme.pack.LocalThemePackBackground
import me.weishu.kernelsu.ui.theme.pack.ThemePackState
import me.weishu.kernelsu.ui.theme.pack.ThemePackWallpaper
import me.weishu.kernelsu.ui.viewmodel.MainActivityViewModel
import top.yukonga.miuix.kmp.nav.core.NavDisplay

/** Hosts the actual theme/About screens on a Core-less emulator without changing capability gates. */
class ThemeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemePackState.reload(this)
        val initial = if (intent.getStringExtra("page") == "about") Route.About else Route.ColorPalette
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
                KernelSUTheme(appSettings = state.appSettings, uiMode = state.uiMode) {
                    Box(Modifier.fillMaxSize()) {
                        ThemePackWallpaper(page = 3)
                        NavDisplay(backStack = navigator.backStack, onBack = { finish() }) {
                            entry<Route.ColorPalette> { ColorPaletteScreen() }
                            entry<Route.Font> { FontScreen() }
                            entry<Route.About> { AboutScreen() }
                        }
                    }
                }
            }
        }
    }
}
