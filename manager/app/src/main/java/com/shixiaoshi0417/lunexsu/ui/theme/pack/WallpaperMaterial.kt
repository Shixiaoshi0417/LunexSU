// Wallpaper materials adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.theme.pack

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Which wallpaper material a container draws. There is deliberately no canvas role: the page canvas
 * is transparent and has no material of its own. Top and bottom bars use [FolkWallpaperTokens.chrome].
 */
enum class WallpaperSurfaceRole { Group, Raised, Overlay }

/** An opaque semantic [tint] drawn at [alpha] over the wallpaper, with the content colors it pairs with. */
@Immutable
data class WallpaperMaterial(
    val tint: Color,
    val alpha: Float,
    val contentColor: Color,
    val supportingColor: Color,
) {
    /** Only the fill is translucent; content colors stay fully opaque. */
    val fill: Color get() = tint.copy(alpha = alpha)
}

@Immutable
data class FolkWallpaperTokens(
    val group: WallpaperMaterial,
    val raised: WallpaperMaterial,
    val overlay: WallpaperMaterial,
    val chrome: WallpaperMaterial,
) {
    fun material(role: WallpaperSurfaceRole): WallpaperMaterial = when (role) {
        WallpaperSurfaceRole.Group -> group
        WallpaperSurfaceRole.Raised -> raised
        WallpaperSurfaceRole.Overlay -> overlay
    }
}

/** Null outside wallpaper mode; the Material theme provides it while a pack wallpaper is shown. */
val LocalFolkWallpaperTokens = compositionLocalOf<FolkWallpaperTokens?> { null }

/** Bars keep this much of their tint even at opacity 0: their labels sit over scrolling content. */
internal const val CHROME_ALPHA_FLOOR = 0.10f

/** Dialogs and menus stay near-opaque; a popup that lets the content behind it read through is unusable. */
internal const val OVERLAY_ALPHA = 0.96f

/** Raised cards sit one step above the group panels. */
internal const val RAISED_ALPHA_STEP = 0.12f

/** A fully collapsed top bar is at least this opaque, whatever the panels' opacity. */
internal const val COLLAPSED_CHROME_FLOOR = 0.85f

/** The alpha of each role for the pack's `backgroundOpacity` (shared by Material and MIUIX). */
@Immutable
data class WallpaperAlphas(val group: Float, val raised: Float, val overlay: Float, val chrome: Float)

/**
 * Maps `backgroundOpacity` (0..1) onto the role alphas. The opacity *is* the panel alpha: 0 lets the
 * wallpaper through completely and 1 is opaque, so panels track it one-to-one. Overlays stay
 * near-opaque and the chrome never drops below [CHROME_ALPHA_FLOOR].
 */
fun wallpaperAlphas(opacity: Float): WallpaperAlphas {
    val alpha = opacity.coerceIn(0f, 1f)
    return WallpaperAlphas(
        group = alpha,
        raised = (alpha + RAISED_ALPHA_STEP).coerceAtMost(1f),
        overlay = OVERLAY_ALPHA,
        chrome = CHROME_ALPHA_FLOOR + (1f - CHROME_ALPHA_FLOOR) * alpha,
    )
}

/**
 * The materials for [colorScheme]. Panels and bars are tinted with the opaque `surface` role and the
 * overlay with `surfaceContainerHigh`, so a theme scheme must keep those roles opaque.
 */
fun resolveFolkWallpaperTokens(colorScheme: ColorScheme, opacity: Float): FolkWallpaperTokens {
    val alphas = wallpaperAlphas(opacity)
    val panel = WallpaperMaterial(
        tint = colorScheme.surface,
        alpha = alphas.group,
        contentColor = colorScheme.onSurface,
        supportingColor = colorScheme.onSurfaceVariant,
    )
    return FolkWallpaperTokens(
        group = panel,
        raised = panel.copy(alpha = alphas.raised),
        overlay = WallpaperMaterial(
            tint = colorScheme.surfaceContainerHigh,
            alpha = alphas.overlay,
            contentColor = colorScheme.onSurface,
            supportingColor = colorScheme.onSurfaceVariant,
        ),
        chrome = panel.copy(alpha = alphas.chrome),
    )
}

/**
 * The alpha of a bar's platform. A top bar honours the chrome alpha while expanded and strengthens to
 * at least [COLLAPSED_CHROME_FLOOR] as it collapses ([collapsed] 0..1); other edges never collapse.
 */
fun chromePlatformAlpha(chromeAlpha: Float, collapsed: Float, topEdge: Boolean): Float {
    val floor = if (topEdge) COLLAPSED_CHROME_FLOOR else chromeAlpha
    val progress = collapsed.coerceIn(0f, 1f)
    return (chromeAlpha + (floor - chromeAlpha).coerceAtLeast(0f) * progress).coerceIn(0f, 1f)
}
