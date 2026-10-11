// Adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.component.material

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.ui.theme.pack.FolkWallpaperTokens
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFolkWallpaperTokens
import com.shixiaoshi0417.lunexsu.ui.theme.pack.WallpaperMaterial
import com.shixiaoshi0417.lunexsu.ui.theme.pack.WallpaperSurfaceRole

/**
 * Marks that the subtree already sits on a wallpaper material, so a nested container does not stack
 * a second translucent fill and darken the wallpaper.
 */
val LocalWallpaperMaterialRole = staticCompositionLocalOf<WallpaperSurfaceRole?> { null }

/**
 * The material a container should draw, or null when it must not draw one: outside wallpaper mode,
 * or nested in another wallpaper material (which already paints the shared fill).
 */
internal fun wallpaperMaterialFor(
    tokens: FolkWallpaperTokens?,
    enclosing: WallpaperSurfaceRole?,
    role: WallpaperSurfaceRole,
): WallpaperMaterial? = if (tokens == null || enclosing != null) null else tokens.material(role)

/**
 * A dialog or menu container color: over a wallpaper the near-opaque overlay material, which keeps
 * popups readable whatever the panels' opacity; otherwise [fallback] unchanged.
 */
@Composable
fun wallpaperOverlayColor(fallback: Color): Color = LocalFolkWallpaperTokens.current?.overlay?.fill ?: fallback

/**
 * The single owner of a container's wallpaper fill. Cards ([TonalCard]), list groups
 * ([SegmentedColumn]) and dialogs draw through it, so each [WallpaperSurfaceRole] has exactly one
 * alpha. In wallpaper mode only the fill is translucent (`Modifier.alpha` is never used, text and
 * icons stay opaque); nested materials pass through. Outside wallpaper mode it draws [fallbackColor]
 * as a plain [Surface], or passes through when that is [Color.Unspecified].
 */
@Composable
fun FolkWallpaperSurface(
    role: WallpaperSurfaceRole,
    shape: Shape,
    modifier: Modifier = Modifier,
    fallbackColor: Color = Color.Unspecified,
    fallbackContentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val tokens = LocalFolkWallpaperTokens.current
    val material = wallpaperMaterialFor(tokens, LocalWallpaperMaterialRole.current, role)
    if (material == null) {
        if (tokens == null && fallbackColor != Color.Unspecified) {
            Surface(
                modifier = modifier,
                shape = shape,
                color = fallbackColor,
                contentColor = if (fallbackContentColor != Color.Unspecified) fallbackContentColor else contentColorFor(fallbackColor),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) { content() }
        } else {
            // Nested or unstyled: keep the caller's modifier (size, clicks) but add no fill of its own.
            Box(modifier = if (tokens != null) modifier.clip(shape) else modifier) { content() }
        }
        return
    }
    Surface(
        modifier = modifier,
        shape = shape,
        color = material.fill,
        contentColor = material.contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        CompositionLocalProvider(LocalWallpaperMaterialRole provides role) {
            content()
        }
    }
}
