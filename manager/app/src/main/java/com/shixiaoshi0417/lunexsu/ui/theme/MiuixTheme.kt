package com.shixiaoshi0417.lunexsu.ui.theme

import com.shixiaoshi0417.lunexsu.ui.theme.pack.wallpaperAlphas
import top.yukonga.miuix.kmp.theme.Colors
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Immutable
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.dynamiccolor.ColorSpec
import com.shixiaoshi0417.lunexsu.ui.webui.MonetColorsProvider
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalThemePackBackground
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemePackState
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/** MIUIX text styles with every slot moved onto [fontFamily]; sizes and weights stay MIUIX's. */
private fun TextStyles.withFontFamily(fontFamily: FontFamily): TextStyles = copy(
    main = main.copy(fontFamily = fontFamily),
    paragraph = paragraph.copy(fontFamily = fontFamily),
    body1 = body1.copy(fontFamily = fontFamily),
    body2 = body2.copy(fontFamily = fontFamily),
    button = button.copy(fontFamily = fontFamily),
    footnote1 = footnote1.copy(fontFamily = fontFamily),
    footnote2 = footnote2.copy(fontFamily = fontFamily),
    headline1 = headline1.copy(fontFamily = fontFamily),
    headline2 = headline2.copy(fontFamily = fontFamily),
    subtitle = subtitle.copy(fontFamily = fontFamily),
    title1 = title1.copy(fontFamily = fontFamily),
    title2 = title2.copy(fontFamily = fontFamily),
    title3 = title3.copy(fontFamily = fontFamily),
    title4 = title4.copy(fontFamily = fontFamily),
)

@Composable
fun MiuixLunexSUTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val amoledMode = appSettings.amoled && darkTheme
    val colorStyle = appSettings.paletteStyle
    val colorSpec = appSettings.colorSpec

    val miuixPaletteStyle = try {
        ThemePaletteStyle.valueOf(colorStyle.name)
    } catch (_: Exception) {
        ThemePaletteStyle.TonalSpot
    }

    val miuixColorSpec = if (colorSpec.effectiveFor(colorStyle) == ColorSpec.SpecVersion.SPEC_2025) {
        ThemeColorSpec.Spec2025
    } else {
        ThemeColorSpec.Spec2021
    }

    val resolvedKeyColor: Color? = when {
        appSettings.keyColor != 0 -> Color(appSettings.keyColor)
        appSettings.colorMode.isMonet ->
            if (darkTheme) dynamicDarkColorScheme(context).primary
            else dynamicLightColorScheme(context).primary

        else -> null
    }

    val controller = ThemeController(
        when (appSettings.colorMode) {
            ColorMode.SYSTEM -> ColorSchemeMode.System
            ColorMode.LIGHT -> ColorSchemeMode.Light
            ColorMode.DARK -> ColorSchemeMode.Dark
            ColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
            ColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
            ColorMode.MONET_DARK, ColorMode.DARK_AMOLED -> ColorSchemeMode.MonetDark
        },
        keyColor = resolvedKeyColor,
        isDark = darkTheme,
        paletteStyle = miuixPaletteStyle,
        colorSpec = miuixColorSpec,
    )

    val fontFamily = rememberAppFontFamily()
    val textStyles = remember(fontFamily) {
        if (fontFamily == FontFamily.Default) defaultTextStyles() else defaultTextStyles().withFontFamily(fontFamily)
    }

    MiuixTheme(
        controller = controller,
        textStyles = textStyles,
        content = {
            LaunchedEffect(darkTheme) {
                val window = (context as? Activity)?.window ?: return@LaunchedEffect
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            val packBackground = ThemePackState.active?.plan?.background
            if (LocalThemePackBackground.current && packBackground != null) {
                val wallpaper = miuixWallpaperColors(MiuixTheme.colorScheme, packBackground.opacity, textStyles)
                // The root Scaffold hosts MIUIX's overlay dialogs and popups. They are drawn with the
                // background and surfaceContainer roles, so this outer scheme keeps the background
                // opaque and the container at the near-opaque overlay alpha; the page content gets
                // the group alpha from MiuixWallpaperContent.
                MiuixTheme(colors = wallpaper.host, textStyles = textStyles) {
                    CompositionLocalProvider(LocalMiuixWallpaper provides wallpaper) {
                        MiuixThemeContent(content)
                    }
                }
            } else if (amoledMode) {
                // MIUIX has no AMOLED palette of its own: only the page canvas turns black, the
                // cards keep their container color so they stay distinguishable from the page.
                MiuixTheme(
                    colors = MiuixTheme.colorScheme.copy(
                        background = Color.Black,
                        surface = Color.Black,
                    ),
                    textStyles = textStyles,
                ) {
                    MiuixThemeContent(content)
                }
            } else {
                MiuixThemeContent(content)
            }
        }
    )
}

@Composable
private fun MiuixThemeContent(content: @Composable () -> Unit) {
    MonetColorsProvider.UpdateCss()
    CompositionLocalProvider(
        LocalContentColor provides MiuixTheme.colorScheme.onBackground,
    ) {
        content()
    }
}

/**
 * MIUIX colors over a pack wallpaper. MIUIX draws cards and list popups with the same
 * `surfaceContainer` role, so the materials are split by where they render: [host] (root Scaffold,
 * which shows overlay dialogs and popups) and [page] (screen content, see [MiuixWallpaperContent]).
 * Bars use [chrome]. Only container fills are translucent; content colors stay opaque.
 */
@Immutable
internal data class MiuixWallpaperColors(
    val host: Colors,
    val page: Colors,
    val chrome: Color,
    val textStyles: TextStyles,
)

internal val LocalMiuixWallpaper = staticCompositionLocalOf<MiuixWallpaperColors?> { null }

internal fun miuixWallpaperColors(base: Colors, opacity: Float, textStyles: TextStyles): MiuixWallpaperColors {
    val alphas = wallpaperAlphas(opacity)
    return MiuixWallpaperColors(
        host = base.copy(
            surface = Color.Transparent,
            surfaceContainer = base.surfaceContainer.copy(alpha = alphas.overlay),
        ),
        page = base.copy(
            surface = Color.Transparent,
            surfaceContainer = base.surfaceContainer.copy(alpha = alphas.group),
            surfaceVariant = base.surfaceVariant.copy(alpha = alphas.group),
        ),
        chrome = base.surface.copy(alpha = alphas.chrome),
        textStyles = textStyles,
    )
}

/** Screen content over a pack wallpaper: MIUIX cards at the group alpha. A no-op otherwise. */
@Composable
fun MiuixWallpaperContent(content: @Composable () -> Unit) {
    val wallpaper = LocalMiuixWallpaper.current
    if (wallpaper == null) {
        content()
        return
    }
    MiuixTheme(colors = wallpaper.page, textStyles = wallpaper.textStyles) { content() }
}

/**
 * A MIUIX top or bottom bar color: transparent under an active blur backdrop, the chrome material over
 * a pack wallpaper, otherwise the opaque surface exactly as before.
 */
@Composable
fun miuixBarColor(blurActive: Boolean): Color = when {
    blurActive -> Color.Transparent
    else -> LocalMiuixWallpaper.current?.chrome ?: MiuixTheme.colorScheme.surface
}

