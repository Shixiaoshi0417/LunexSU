// Adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.component.material

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.ui.theme.pack.currentFinaWallpaperTokens
import com.shixiaoshi0417.lunexsu.ui.theme.pack.WallpaperMaterial
import com.shixiaoshi0417.lunexsu.ui.theme.pack.chromePlatformAlpha

/** Which screen edge a chrome zone hugs. */
enum class ChromeEdge { Top, Bottom, Start }

/**
 * Draws the wallpaper reading platform behind a bar with the chrome material and fades it into the
 * wallpaper past the bar's edge, so the bar does not end in a hard band. The bar itself stays
 * transparent; its labels keep their opaque content colors. Outside wallpaper mode [content] is
 * emitted unchanged. [progress] (0 expanded, 1 collapsed) strengthens a collapsing top bar.
 */
@Composable
fun WallpaperChromeZone(
    edge: ChromeEdge,
    modifier: Modifier = Modifier,
    progress: () -> Float = { 0f },
    tailHeight: Dp = 48.dp,
    material: WallpaperMaterial? = null,
    content: @Composable () -> Unit,
) {
    val tokens = currentFinaWallpaperTokens()
    if (tokens == null) {
        content()
        return
    }
    val chrome = material ?: tokens.chrome
    val tailPx = with(LocalDensity.current) { tailHeight.toPx() }
    val layoutDirection = LocalLayoutDirection.current
    Box(
        modifier = modifier.drawBehind {
            val alpha = chromePlatformAlpha(chrome.alpha, progress(), topEdge = edge == ChromeEdge.Top)
            val solid = chrome.tint.copy(alpha = alpha)
            val clear = solid.copy(alpha = 0f)
            val width = size.width
            when (edge) {
                ChromeEdge.Top -> {
                    drawRect(solid, topLeft = Offset.Zero, size = Size(width, size.height))
                    drawRect(
                        brush = Brush.verticalGradient(listOf(solid, clear), startY = size.height, endY = size.height + tailPx),
                        topLeft = Offset(0f, size.height),
                        size = Size(width, tailPx),
                    )
                }

                ChromeEdge.Bottom -> {
                    drawRect(
                        brush = Brush.verticalGradient(listOf(clear, solid), startY = -tailPx, endY = 0f),
                        topLeft = Offset(0f, -tailPx),
                        size = Size(width, tailPx),
                    )
                    drawRect(solid, topLeft = Offset.Zero, size = Size(width, size.height))
                }

                ChromeEdge.Start -> {
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    drawRect(solid, topLeft = Offset.Zero, size = Size(width, size.height))
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = if (rtl) listOf(clear, solid) else listOf(solid, clear),
                            startX = if (rtl) -tailPx else width,
                            endX = if (rtl) 0f else width + tailPx,
                        ),
                        topLeft = Offset(if (rtl) -tailPx else width, 0f),
                        size = Size(tailPx, size.height),
                    )
                }
            }
        }
    ) {
        content()
    }
}
