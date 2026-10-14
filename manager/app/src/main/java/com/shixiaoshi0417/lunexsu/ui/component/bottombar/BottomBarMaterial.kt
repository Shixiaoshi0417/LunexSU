package com.shixiaoshi0417.lunexsu.ui.component.bottombar

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.ui.LocalMainPagerState

@Composable
fun BottomBarMaterial(navigationBadge: NavigationBadgeState) {
    if (!Natives.isFullFeatured()) return
    val pager = LocalMainPagerState.current
    ShortNavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) {
        BottomBarDestination.entries.forEachIndexed { index, destination ->
            val selected = pager.selectedPage == index
            ShortNavigationBarItem(
                selected = selected,
                onClick = { if (!selected) pager.animateToPage(index) },
                icon = { NavigationIconWithBadge(destination, selected, badgeFor(index, navigationBadge)) },
                label = { Text(stringResource(destination.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
