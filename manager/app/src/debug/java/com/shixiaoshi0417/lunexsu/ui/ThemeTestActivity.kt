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
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFinaThemeUi
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemePackState
import com.shixiaoshi0417.lunexsu.ui.navigation3.themeEntry
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
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
            // This private debug host deliberately previews the installed UI without a Core.
            val presentation = managerUiPresentation(state.uiMode, ThemePackState.active != null, coreInstalled = true)
            val themeUi = presentation.finaThemeUi
            val uiMode = presentation.uiMode
            val navigator = rememberNavigator(initial)
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalUiMode provides uiMode,
                LocalFinaThemeUi provides themeUi,
                LocalColorMode provides state.appSettings.colorMode.value,
                LocalThemePackBackground provides (themeUi && ThemePackState.hasBackground),
            ) {
                LunexSUTheme(appSettings = state.appSettings, uiMode = uiMode) {
                    Box(Modifier.fillMaxSize()) {
                        NavDisplay(backStack = navigator.backStack, onBack = { finish() }) {
                            themeEntry<Route.ColorPalette>(NavSwipeDirection.None) { ColorPaletteScreen() }
                            themeEntry<Route.Font>(NavSwipeDirection.None) { FontScreen() }
                            themeEntry<Route.ThemeStore>(NavSwipeDirection.None) { ThemeStoreScreen() }
                            themeEntry<Route.MyThemes>(NavSwipeDirection.None) { MyThemesScreen() }
                            themeEntry<Route.About>(NavSwipeDirection.None) { AboutScreen() }
                        }
                    }
                }
            }
        }
    }
}
