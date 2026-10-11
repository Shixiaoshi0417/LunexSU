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
import com.materialkolor.PaletteStyle
import com.shixiaoshi0417.lunexsu.ui.webui.MonetColorsProvider
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalThemePackBackground
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalWallpaperDim
import com.shixiaoshi0417.lunexsu.ui.theme.pack.resolveFolkWallpaperTokens
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFolkWallpaperTokens
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemePackState
import com.shixiaoshi0417.lunexsu.ui.theme.pack.adaptNeutralRoles
import com.shixiaoshi0417.lunexsu.ui.theme.pack.effectiveWallpaperDim
import com.shixiaoshi0417.lunexsu.ui.theme.pack.guardedDim
import com.shixiaoshi0417.lunexsu.ui.theme.pack.rememberThemePackLuminance
import com.shixiaoshi0417.lunexsu.ui.theme.pack.useDarkNeutral

// The default dark ripple alpha is nearly invisible on near-black surfaces, so boost it for a clear
// press feedback at night. Light mode keeps Compose's default. (From FinaSU, GPL-3.0.)
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

    // Over a pack wallpaper a light theme takes dark-neutral (light text) roles when the visible
    // wallpaper reads dark after its dim; a dark theme never flips (as in FinaSU).
    val background = ThemePackState.active?.plan?.background?.takeIf { LocalThemePackBackground.current }
    val requestedDim = background?.let { effectiveWallpaperDim(it, darkTheme) } ?: 0f
    val luminance = rememberThemePackLuminance()
    val flipToDark = background != null && !darkTheme && useDarkNeutral(false, luminance, requestedDim)
    val neutralScheme = rememberLunexSUColorScheme(
        seedColor = appSettings.materialSeedColor(),
        isDark = true,
        isAmoled = false,
        paletteStyle = PaletteStyle.Neutral,
        colorSpec = appSettings.colorSpec,
        contrastLevel = appSettings.contrast.level,
    )
    val systemBarDark = darkTheme || flipToDark

    LaunchedEffect(systemBarDark) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !systemBarDark
            isAppearanceLightNavigationBars = !systemBarDark
        }
    }

    // Over a wallpaper only the canvas is transparent. Surface roles stay opaque: they are the source
    // colors of the wallpaper materials, which own all translucency per component (one material
    // owner, no double veil), so dialogs and menus built on these roles stay readable.
    val wallpaperScheme = if (background != null) {
        val base = if (flipToDark) adaptNeutralRoles(colorScheme, neutralScheme) else colorScheme
        base.copy(background = Color.Transparent)
    } else colorScheme
    // Light text over a light theme's wallpaper gets the smallest scrim that keeps it legible.
    val renderDim = when {
        background == null -> null
        flipToDark -> guardedDim(requestedDim, wallpaperScheme) ?: requestedDim
        else -> requestedDim
    }
    // Derive the materials from the animated scheme, so a panel's fill and its text interpolate together.
    val animatedColorScheme = wallpaperScheme.animateAsState()
    val wallpaperTokens = background?.let { resolveFolkWallpaperTokens(animatedColorScheme, it.opacity) }
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
            CompositionLocalProvider(
                LocalRippleConfiguration provides rippleConfiguration,
                LocalWallpaperDim provides renderDim,
                LocalFolkWallpaperTokens provides wallpaperTokens,
            ) {
                MonetColorsProvider.UpdateCss(colorScheme)
                content()
            }
        }
    )
}
