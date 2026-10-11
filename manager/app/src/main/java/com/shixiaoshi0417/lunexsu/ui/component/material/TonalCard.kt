package com.shixiaoshi0417.lunexsu.ui.component.material

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFolkWallpaperTokens
import com.shixiaoshi0417.lunexsu.ui.theme.pack.WallpaperSurfaceRole

/**
 * A rounded tonal card. A clickable card answers a press with the shared scale-and-haptic feedback
 * instead of a ripple (the press model adapted from FinaSU, GPL-3.0).
 *
 * Over a pack wallpaper a neutral card ([MaterialTheme.colorScheme.surfaceBright]) draws through
 * [FolkWallpaperSurface] with [wallpaperRole], sharing one alpha with every other panel; a card with
 * a semantic container color (error, secondary, ...) keeps it. Outside wallpaper mode it is a plain
 * [Card] exactly as before.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceBright,
    contentColor: Color = contentColorFor(containerColor),
    shape: Shape = MaterialTheme.shapes.large,
    wallpaperRole: WallpaperSurfaceRole? =
        if (containerColor == MaterialTheme.colorScheme.surfaceBright) WallpaperSurfaceRole.Group else null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val colors = CardDefaults.cardColors(
        containerColor = containerColor,
        contentColor = contentColor,
    )
    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier
            .clip(shape)
            .folkPressScale(interactionSource, enabled)
            .combinedClickable(
                enabled = enabled,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick?.invoke()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
                interactionSource = interactionSource,
                indication = null,
            )
    } else {
        Modifier
    }
    if (wallpaperRole != null && LocalFolkWallpaperTokens.current != null) {
        FolkWallpaperSurface(
            role = wallpaperRole,
            shape = shape,
            modifier = modifier.then(clickModifier),
            fallbackColor = containerColor,
            fallbackContentColor = contentColor,
        ) {
            Column { content() }
        }
        return
    }
    Card(
        modifier = modifier.then(clickModifier),
        colors = colors,
        shape = shape,
    ) { content() }
}
