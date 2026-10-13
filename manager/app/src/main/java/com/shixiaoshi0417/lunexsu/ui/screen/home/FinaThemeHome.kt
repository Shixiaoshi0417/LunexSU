// Adapted from LyraVoid/FinaSU HomeMaterial.kt (8ba5a6c), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.HomeMetrics
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveScaffold
import com.shixiaoshi0417.lunexsu.ui.component.material.expressiveTopAppBarColors
import com.shixiaoshi0417.lunexsu.ui.component.rebootlistpopup.RebootListPopup
import com.shixiaoshi0417.lunexsu.ui.theme.pack.PackHomeTitle
import com.shixiaoshi0417.lunexsu.ui.theme.pack.PackMusicButton

/** FinaSU chrome and layout, with LunexSU's existing state/actions rather than a second backend. */
@Composable
internal fun FinaThemeHome(
    state: HomeUiState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
    superuserCount: Int,
    moduleEnabledCount: Int,
    metrics: HomeMetrics,
) {
    ExpressiveScaffold(
        topBar = {
            TopAppBar(
                title = { PackHomeTitle { Text(stringResource(R.string.app_name)) } },
                actions = {
                    PackMusicButton()
                    IconButton(onClick = actions.onInstallClick, enabled = !state.isLateLoadMode) {
                        Icon(Icons.Filled.AutoFixHigh, stringResource(R.string.install))
                    }
                    RebootListPopup()
                },
                colors = expressiveTopAppBarColors(),
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HomeWarnings(state, actions)
            HomeLayoutContent(LocalHomeLayoutOptions.current.style, state, actions, superuserCount, moduleEnabledCount, metrics)
            HomeSupportLinks(onOpenUrl = actions.onOpenUrl)
            Spacer(Modifier.height(bottomInnerPadding + if (!state.isFullFeatured)
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp))
        }
    }
}
