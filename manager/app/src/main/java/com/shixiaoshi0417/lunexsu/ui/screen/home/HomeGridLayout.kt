// Adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.home

import com.shixiaoshi0417.lunexsu.ui.theme.pack.PackSurfaceId
import com.shixiaoshi0417.lunexsu.ui.theme.pack.currentFinaWallpaperTokens
import com.shixiaoshi0417.lunexsu.ui.theme.pack.packSurface
import com.shixiaoshi0417.lunexsu.ui.theme.pack.packSurfaceColor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.ui.component.statustag.StatusTag
import com.shixiaoshi0417.lunexsu.ui.theme.FolkType

/** The gap between the tiles of the two-column grid. */
private val GridTileSpacing = 12.dp

/** GridUI's own content: the hero tile next to the stacked counters, as one row. */
@Composable
internal fun GridHomeBoard(
    state: HomeUiState,
    actions: HomeActions,
    superuserCount: Int,
    moduleEnabledCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(GridTileSpacing)
    ) {
        GridStatusCard(
            state = state,
            actions = actions,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        // Unavailable counts keep their place so a change in capability does not rearrange the grid.
        CountCardPair(
            superuserCount = superuserCount,
            moduleEnabledCount = moduleEnabledCount,
            onOpenSuperUser = actions.onOpenSuperUser,
            onOpenModule = actions.onOpenModule,
            layout = CountCardLayout.Vertical,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            pairSpacing = GridTileSpacing,
            contentPadding = PaddingValues(18.dp),
            emphasis = CountCardEmphasis.Value,
            available = state.isFullFeatured,
        )
    }
}

/** GridUI: a hero status tile on the left with the two count cards stacked on the right. */
@Composable
internal fun GridHomeContent(
    state: HomeUiState,
    actions: HomeActions,
    superuserCount: Int,
    moduleEnabledCount: Int,
) {
    GridHomeBoard(state, actions, superuserCount, moduleEnabledCount)
    HomeInfoCard(systemInfo = state.systemInfo)
}

/**
 * The grid hero card. The status icon sits in the top corner while the state title and its mode
 * tag sit against the bottom edge, so the card reads as a tile rather than a list row.
 */
@Composable
private fun GridStatusCard(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val ksuActive = state.ksuVersion != null
    // Wallpaper-backed status cards use the same neutral surface as the other theme tiles.
    // Keep the semantic status fill in the ordinary (non-theme) home UI.
    val containerColor = if (currentFinaWallpaperTokens() != null) MaterialTheme.colorScheme.surfaceBright
        else statusContainerColor(ksuActive)
    val contentColor = contentColorFor(containerColor)
    val statusTitle = state.statusTitle()
    val workingMode = workingModeLabel(state)
    val surface = packSurface(PackSurfaceId.GRID)
    val hideMode = LocalHomeLayoutOptions.current.hideWorkingMode || surface?.hideMode == true

    HomeCard(
        surfaceId = PackSurfaceId.GRID,
        modifier = modifier,
        containerColor = containerColor,
        contentColor = contentColor,
        onClick = {
            if (!state.isLateLoadMode) {
                actions.onInstallClick()
            }
        },
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 160.dp)
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (surface?.hideText != true) Text(
                    text = statusTitle,
                    style = FolkType.Title
                )
                if (ksuActive && workingMode.isNotEmpty()) {
                    if (!hideMode) {
                        StatusTag(
                            label = workingMode,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            backgroundColor = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (state.isNotInstalled() && state.isSELinuxPermissive) {
                    StatusJailbreakButton(onClick = actions.onJailbreakClick)
                }
            }
            if (surface?.hideIcon != true) Icon(
                imageVector = state.statusIcon(),
                contentDescription = statusTitle,
                tint = packSurfaceColor(PackSurfaceId.GRID, contentColor),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
            )
        }
    }
}
