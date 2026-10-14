package com.shixiaoshi0417.lunexsu.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.shixiaoshi0417.lunexsu.ui.webui.MonetColorsProvider

/** The seed the Material scheme is generated from; [Color.Unspecified] selects system Monet. */
fun AppSettings.materialSeedColor(): Color = when {
    useDynamicColor -> Color.Unspecified
    keyColor != 0 -> Color(keyColor)
    else -> DefaultKeyColor
}

/** Ordinary M3E rendering: no theme-pack palette, wallpaper, dim or ripple overrides. */
@Composable
fun MaterialLunexSUTheme(appSettings: AppSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = appSettings.colorMode.isDark ||
        (appSettings.colorMode.isSystem && isSystemInDarkTheme())
    val colorScheme = rememberLunexSUColorScheme(
        seedColor = appSettings.materialSeedColor(),
        isDark = darkTheme,
        isAmoled = appSettings.amoled && darkTheme,
        paletteStyle = appSettings.paletteStyle,
        colorSpec = appSettings.colorSpec,
        contrastLevel = appSettings.contrast.level,
    )
    LaunchedEffect(darkTheme) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    val fontFamily = rememberAppFontFamily()
    MaterialExpressiveTheme(
        colorScheme = colorScheme.animateAsState(),
        motionScheme = MotionScheme.expressive(),
        typography = remember(fontFamily) { getTypography(fontFamily) },
        shapes = FolkShape.materialShapes,
    ) {
        MonetColorsProvider.UpdateCss(colorScheme)
        content()
    }
}
