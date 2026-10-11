// Wallpaper dim and contrast adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.theme.pack

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

// Luminance of a light-mode wallpaper, measured after its dim, below which content switches to
// dark-neutral roles: a heavily dimmed bright image behaves like a dark one.
private const val WALLPAPER_DARK_THRESHOLD = 0.5f

// Minimum contrast ratio between normal text and its background (WCAG AA).
private const val MIN_CONTRAST = 4.5f

/** The dim the pack requests for the current theme; dual dim picks the day or night value. */
internal fun effectiveWallpaperDim(background: PackBackground, dark: Boolean): Float {
    val dim = if (background.dualDim) {
        if (dark) background.nightDim else background.dayDim
    } else {
        background.dim
    }
    return dim.coerceIn(0f, 1f)
}

/** Average perceived luminance (0..1) of ARGB pixels, or -1 when there are none. */
internal fun averageLuminance(pixels: IntArray): Float {
    if (pixels.isEmpty()) return -1f
    var sum = 0.0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        sum += (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    }
    return (sum / pixels.size).toFloat()
}

/**
 * Whether content over the wallpaper uses dark-neutral roles (light text). A dark theme always
 * stays dark; readability there is left to the night dim. In a light theme the wallpaper counts
 * after its dim. An unknown luminance (-1) keeps the theme's polarity.
 */
internal fun useDarkNeutral(darkTheme: Boolean, luminance: Float, dim: Float): Boolean {
    if (darkTheme) return true
    if (luminance < 0f) return false
    return luminance * (1f - dim.coerceIn(0f, 1f)) < WALLPAPER_DARK_THRESHOLD
}

/** Takes the neutral (surface and text) roles from [neutral]; accent roles stay from [base]. */
internal fun adaptNeutralRoles(base: ColorScheme, neutral: ColorScheme): ColorScheme = base.copy(
    surface = neutral.surface,
    surfaceDim = neutral.surfaceDim,
    surfaceBright = neutral.surfaceBright,
    surfaceContainer = neutral.surfaceContainer,
    surfaceContainerLow = neutral.surfaceContainerLow,
    surfaceContainerLowest = neutral.surfaceContainerLowest,
    surfaceContainerHigh = neutral.surfaceContainerHigh,
    surfaceContainerHighest = neutral.surfaceContainerHighest,
    surfaceVariant = neutral.surfaceVariant,
    onBackground = neutral.onBackground,
    onSurface = neutral.onSurface,
    onSurfaceVariant = neutral.onSurfaceVariant,
    outline = neutral.outline,
    outlineVariant = neutral.outlineVariant,
    inverseSurface = neutral.inverseSurface,
    inverseOnSurface = neutral.inverseOnSurface,
)

/**
 * Smallest dim (>= [requested]) that keeps the neutral text of [scheme] legible over the worst-case
 * (pure white) wallpaper, or null when even a fully opaque scrim is insufficient. The stored
 * preference is never rewritten; only the painted scrim is raised.
 */
internal fun guardedDim(requested: Float, scheme: ColorScheme): Float? {
    val start = requested.coerceIn(0f, 1f)
    if (passesContrast(start, scheme)) return start
    if (!passesContrast(1f, scheme)) return null
    var low = start
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2f
        if (passesContrast(mid, scheme)) high = mid else low = mid
    }
    return high
}

internal fun passesContrast(dim: Float, scheme: ColorScheme): Boolean {
    val wall = Color.Black.copy(alpha = dim.coerceIn(0f, 1f)).compositeOver(Color.White)
    val backgrounds = listOf(
        wall,
        scheme.surface.compositeOver(wall),
        scheme.surfaceContainer.compositeOver(wall),
    ).map { it.luminance() }
    val foregrounds = listOf(scheme.onBackground, scheme.onSurface, scheme.onSurfaceVariant).map { it.luminance() }
    return foregrounds.all { foreground ->
        backgrounds.all { background ->
            val high = maxOf(foreground, background)
            val low = minOf(foreground, background)
            (high + 0.05f) / (low + 0.05f) >= MIN_CONTRAST
        }
    }
}
