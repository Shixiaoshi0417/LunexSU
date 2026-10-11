package com.shixiaoshi0417.lunexsu.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.shixiaoshi0417.lunexsu.ui.webui.MonetColorsProvider
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalThemePackBackground
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemePackState

// The default dark ripple alpha is nearly invisible on near-black surfaces, so boost it for a clear
// press feedback at night. Light mode keeps Compose's default. (From FolkSU, GPL-3.0.)
private val DarkRippleAlpha = RippleAlpha(
    draggedAlpha = 0.32f,
    focusedAlpha = 0.24f,
    hoveredAlpha = 0.16f,
    pressedAlpha = 0.24f,
)

/** The seed the Material scheme is generated from; [Color.Unspecified] means the system Monet palette. */
fun AppSettings.materialSeedColor(): Color = when {
    useDynamicColor -> Color.Unspecified
    keyColor != 0 -> Color(keyColor)
    else -> DefaultKeyColor
}

@Composable
fun MaterialLunexSUTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val amoledMode = appSettings.amoled && darkTheme

    val colorScheme = rememberLunexSUColorScheme(
        seedColor = appSettings.materialSeedColor(),
        isDark = darkTheme,
        isAmoled = amoledMode,
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

    val wallpaperScheme = if (LocalThemePackBackground.current) {
        val opacity = ThemePackState.active?.plan?.background?.opacity ?: 0.6f
        colorScheme.copy(
            background = Color.Transparent, surface = Color.Transparent,
            surfaceContainer = colorScheme.surfaceContainer.copy(alpha = opacity),
            surfaceContainerLowest = colorScheme.surfaceContainerLowest.copy(alpha = opacity),
            surfaceContainerLow = colorScheme.surfaceContainerLow.copy(alpha = opacity),
            surfaceContainerHigh = colorScheme.surfaceContainerHigh.copy(alpha = opacity),
            surfaceContainerHighest = colorScheme.surfaceContainerHighest.copy(alpha = opacity),
            surfaceBright = colorScheme.surfaceBright.copy(alpha = opacity),
            surfaceVariant = colorScheme.surfaceVariant.copy(alpha = opacity),
        )
    } else colorScheme
    val animatedColorScheme = wallpaperScheme.animateAsState()
    val fontFamily = rememberAppFontFamily()

    MaterialExpressiveTheme(
        colorScheme = animatedColorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = remember(fontFamily) { getTypography(fontFamily) },
        shapes = FolkShape.materialShapes,
        content = {
            // alpha28 has no non-deprecated way to override the ripple alpha.
            @Suppress("DEPRECATION")
            val rippleConfiguration = if (darkTheme) {
                RippleConfiguration(rippleAlpha = DarkRippleAlpha)
            } else {
                LocalRippleConfiguration.current
            }
            CompositionLocalProvider(LocalRippleConfiguration provides rippleConfiguration) {
                MonetColorsProvider.UpdateCss(colorScheme)
                content()
            }
        }
    )
}
