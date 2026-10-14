package com.shixiaoshi0417.lunexsu.ui.theme

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
            if (amoledMode) {
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
