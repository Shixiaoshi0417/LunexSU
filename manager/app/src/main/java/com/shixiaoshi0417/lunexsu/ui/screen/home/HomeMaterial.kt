package com.shixiaoshi0417.lunexsu.ui.screen.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.HomeMetrics
import com.shixiaoshi0417.lunexsu.data.model.HomeLayoutStyle
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveScaffold
import com.shixiaoshi0417.lunexsu.ui.component.material.expressiveTopAppBarColors
import com.shixiaoshi0417.lunexsu.ui.component.rebootlistpopup.RebootListPopup

/**
 * The Material home. The warnings always come first; the board under them is the layout picked in
 * the theme settings (Circle, Grid, Focus, Dashboard or Stats, ported from FinaSU). Every layout
 * reads the same [HomeUiState] snapshot, so switching layouts never changes what is reported.
 */
@Composable
fun HomePagerMaterial(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
    superuserCount: Int = 0,
    moduleEnabledCount: Int = 0,
    metrics: HomeMetrics = HomeMetrics(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val layout = LocalHomeLayoutOptions.current.style

    ExpressiveScaffold(
        topBar = { TopBar(scrollBehavior = scrollBehavior) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HomeWarnings(state = state, actions = actions)
            if (!state.isManager) SuperkeyEntry(actions.onSuperkeyClick)
            val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = layout,
                transitionSpec = { fadeIn(effectsSpec) togetherWith fadeOut(effectsSpec) },
                label = "home_layout",
            ) { targetLayout ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(TileSpacing),
                ) {
                    HomeLayoutContent(
                        layout = targetLayout,
                        state = state,
                        actions = actions,
                        superuserCount = superuserCount,
                        moduleEnabledCount = moduleEnabledCount,
                        metrics = metrics,
                    )
                }
            }
            HomeSupportLinks(onOpenUrl = actions.onOpenUrl)
            Spacer(
                Modifier.height(
                    bottomInnerPadding + if (!state.isFullFeatured)
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp
                )
            )
        }
    }
}

@Composable
internal fun HomeLayoutContent(
    layout: String,
    state: HomeUiState,
    actions: HomeActions,
    superuserCount: Int,
    moduleEnabledCount: Int,
    metrics: HomeMetrics,
) {
    when (layout) {
        HomeLayoutStyle.GRID -> GridHomeContent(state, actions, superuserCount, moduleEnabledCount)
        HomeLayoutStyle.FOCUS -> FocusHomeContent(state, actions, metrics)
        HomeLayoutStyle.DASHBOARD -> DashboardHomeContent(state, actions, superuserCount, moduleEnabledCount)
        HomeLayoutStyle.STATS -> StatsHomeContent(state, actions, superuserCount, moduleEnabledCount, metrics)
        else -> CircleHomeContent(state, actions, superuserCount, moduleEnabledCount)
    }
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = { RebootListPopup() },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}
