package com.shixiaoshi0417.lunexsu.ui.theme.pack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds

internal val LocalThemeRouteVisible = staticCompositionLocalOf { true }

/** Transparent theme controls see this entry's wallpaper, never a covered route's controls. */
@PublishedApi
@Composable
internal fun ThemeRouteSurface(mainPage: Boolean, visible: Boolean, content: @Composable () -> Unit) {
    if (!LocalFinaThemeUi.current) {
        content()
        return
    }
    CompositionLocalProvider(LocalThemeRouteVisible provides visible) {
        if (mainPage) {
            // Each main pager page already owns its wallpaper.
            content()
        } else {
            val page = rememberSaveable { ThemePackState.visiblePage }
            // This backing remains opaque even before decoding, for video-only packs,
            // or when the pack has no wallpaper. It moves with the navigation entry.
            Box(Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.surface)) {
                ThemePackWallpaper(page = page, isCurrentPage = visible)
                content()
            }
        }
    }
}
