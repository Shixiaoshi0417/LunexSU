package com.shixiaoshi0417.lunexsu.ui.component.material

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFolkWallpaperTokens

/**
 * Over a pack wallpaper the page canvas is transparent so the wallpaper stays continuous, and the
 * bars draw their reading platform through [WallpaperChromeZone]. (Adapted from FinaSU, GPL-3.0.)
 */
@Composable
fun ExpressiveScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val wallpaperActive = LocalFolkWallpaperTokens.current != null
    Scaffold(
        modifier = modifier,
        topBar = {
            if (wallpaperActive) WallpaperChromeZone(ChromeEdge.Top) { topBar() } else topBar()
        },
        bottomBar = {
            if (wallpaperActive) WallpaperChromeZone(ChromeEdge.Bottom) { bottomBar() } else bottomBar()
        },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = if (wallpaperActive) Color.Transparent else containerColor,
        contentColor = if (wallpaperActive) MaterialTheme.colorScheme.onSurface else contentColor,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}

/** Over a wallpaper the bar is transparent (the chrome zone draws its platform) with opaque content. */
@Composable
fun expressiveTopAppBarColors(
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    scrolledContainerColor: Color = containerColor,
): TopAppBarColors {
    val tokens = LocalFolkWallpaperTokens.current
    if (tokens != null) {
        return TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = tokens.chrome.contentColor,
            navigationIconContentColor = tokens.chrome.contentColor,
            actionIconContentColor = tokens.chrome.contentColor,
        )
    }
    return TopAppBarDefaults.topAppBarColors(
        containerColor = containerColor,
        scrolledContainerColor = scrolledContainerColor,
    )
}
