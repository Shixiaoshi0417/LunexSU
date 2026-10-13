package com.shixiaoshi0417.lunexsu.ui.component.bottombar

import androidx.annotation.StringRes
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFinaThemeUi
import com.shixiaoshi0417.lunexsu.ui.util.shouldShowSplitPane
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.utils.springAnimateToPage

class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
    private val animatePageChanges: Boolean,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage) return

        navJob?.cancel()

        selectedPage = targetIndex
        isNavigating = true

        navJob = coroutineScope.launch {
            val myJob = coroutineContext.job
            try {
                if (animatePageChanges) {
                    pagerState.springAnimateToPage(targetIndex)
                } else {
                    pagerState.scrollToPage(targetIndex)
                }
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != targetIndex) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

@Composable
fun rememberMainPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    animatePageChanges: Boolean = true,
): MainPagerState {
    return remember(pagerState, coroutineScope, animatePageChanges) {
        MainPagerState(pagerState, coroutineScope, animatePageChanges)
    }
}

@Immutable
data class NavigationBadgeState(
    val superuserCount: Int = 0,
    val moduleEnabledCount: Int = 0,
    val moduleUpdatableCount: Int = 0,
)

/**
 * The four main pages, in pager order. [icon] is the MIUIX glyph; Material shows [iconSelected] or
 * [iconNotSelected] (as in FinaSU, GPL-3.0).
 */
enum class BottomBarDestination(
    @get:StringRes val label: Int,
    val icon: ImageVector,
    val iconSelected: ImageVector,
    val iconNotSelected: ImageVector,
) {
    Home(R.string.home, Icons.Rounded.Cottage, Icons.Filled.Home, Icons.Outlined.Home),
    SuperUser(R.string.superuser, Icons.Rounded.Security, Icons.Filled.Shield, Icons.Outlined.Shield),
    Module(R.string.module, Icons.Rounded.Extension, Icons.Filled.Extension, Icons.Outlined.Extension),
    Setting(R.string.settings, Icons.Rounded.Settings, Icons.Filled.Settings, Icons.Outlined.Settings)
}

internal enum class BadgeTone { Alert, Accent }

@Immutable
internal data class NavBadge(val count: Int, val tone: BadgeTone)

internal fun badgeFor(index: Int, state: NavigationBadgeState): NavBadge? = when (index) {
    BottomBarDestination.SuperUser.ordinal ->
        state.superuserCount.takeIf { it > 0 }?.let { NavBadge(it, BadgeTone.Accent) }

    BottomBarDestination.Module.ordinal -> when {
        state.moduleUpdatableCount > 0 -> NavBadge(state.moduleUpdatableCount, BadgeTone.Alert)
        state.moduleEnabledCount > 0 -> NavBadge(state.moduleEnabledCount, BadgeTone.Accent)
        else -> null
    }

    else -> null
}

/** The Material navigation mode, recomposing when it changes. */
@Composable
fun rememberNavMode(): NavMode {
    val revision by NavModeConfig.revision.collectAsStateWithLifecycle()
    return remember(revision) { NavModeConfig.mode }
}

/**
 * Only the dedicated theme UI follows FinaSU's navigation modes. Ordinary UIs keep their native
 * window-size policy and do not consult the theme navigation preference.
 */
@Composable
fun useNavigationRail(enableFloatingBottomBar: Boolean): Boolean = navigationRailEnabled(
    themeUi = LocalFinaThemeUi.current,
    largeWindow = shouldShowSplitPane(),
    enableFloatingBottomBar = enableFloatingBottomBar,
    themeMode = if (LocalFinaThemeUi.current) rememberNavMode() else NavMode.Auto,
)

@Composable
fun BottomBar(
    blurBackdrop: LayerBackdrop?,
    backdrop: Backdrop,
    navigationBadge: NavigationBadgeState,
    modifier: Modifier = Modifier,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> BottomBarMiuix(blurBackdrop, backdrop, navigationBadge, modifier)
        UiMode.Material -> if (LocalFinaThemeUi.current) FinaBottomBar(navigationBadge)
            else BottomBarMaterial(navigationBadge)
    }
}

@Composable
fun SideRail(
    navigationBadge: NavigationBadgeState,
    modifier: Modifier = Modifier,
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> NavigationRailMiuix(navigationBadge, modifier)
        UiMode.Material -> if (LocalFinaThemeUi.current) FinaNavigationRail(navigationBadge, modifier)
            else NavigationRailMaterial(navigationBadge, modifier)
    }
}
