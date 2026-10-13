package com.shixiaoshi0417.lunexsu.ui.component.bottombar

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.repository.SettingsRepositoryImpl
import com.shixiaoshi0417.lunexsu.ui.LocalMainPagerState
import kotlinx.coroutines.launch

@Composable
fun NavigationRailMaterial(navigationBadge: NavigationBadgeState, modifier: Modifier = Modifier) {
    if (!Natives.isFullFeatured()) return
    val pager = LocalMainPagerState.current
    val settings = remember { SettingsRepositoryImpl() }
    val state = rememberWideNavigationRailState(
        initialValue = if (settings.navigationRailExpanded) WideNavigationRailValue.Expanded
            else WideNavigationRailValue.Collapsed,
    )
    val scope = rememberCoroutineScope()
    val expanded = state.targetValue == WideNavigationRailValue.Expanded
    LaunchedEffect(state.targetValue) { settings.navigationRailExpanded = expanded }
    WideNavigationRail(
        modifier = modifier.fillMaxHeight(),
        state = state,
        colors = WideNavigationRailDefaults.colors().copy(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
        contentPadding = PaddingValues(vertical = 20.dp),
        header = {
            IconButton(modifier = Modifier.padding(start = 24.dp), onClick = {
                scope.launch { if (expanded) state.collapse() else state.expand() }
            }) {
                Icon(if (expanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Filled.Menu,
                    stringResource(if (expanded) R.string.nav_rail_collapse else R.string.nav_rail_expand))
            }
        },
    ) {
        BottomBarDestination.entries.forEachIndexed { index, destination ->
            val selected = pager.selectedPage == index
            WideNavigationRailItem(
                railExpanded = expanded,
                selected = selected,
                onClick = { if (!selected) pager.animateToPage(index) },
                icon = { NavigationIconWithBadge(destination, selected, badgeFor(index, navigationBadge)) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}
