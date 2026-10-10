package me.weishu.kernelsu.ui.screen.font

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedListItem
import me.weishu.kernelsu.ui.component.material.SegmentedRadioItem
import me.weishu.kernelsu.ui.component.material.TonalCard
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.theme.FontMode

@Composable
fun FontScreenMaterial(
    state: FontScreenState,
    actions: FontScreenActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.font_title)) },
                navigationIcon = { TopBarBackButton(onClick = actions.onBack) },
                colors = expressiveTopAppBarColors(),
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.font_preview),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = FONT_PREVIEW_SAMPLE, style = MaterialTheme.typography.headlineSmall)
                    Text(text = FONT_PREVIEW_SAMPLE, style = MaterialTheme.typography.bodyMedium)
                }
            }

            SegmentedColumn(
                content = listOf(
                    {
                        SegmentedRadioItem(
                            title = stringResource(R.string.font_mode_system),
                            selected = state.fontMode == FontMode.SYSTEM_DEFAULT,
                            onClick = actions.onSelectSystem,
                        )
                    },
                    {
                        SegmentedRadioItem(
                            title = stringResource(R.string.font_mode_custom),
                            summary = if (state.hasCustomFont) null else stringResource(R.string.font_mode_custom_summary),
                            selected = state.fontMode == FontMode.CUSTOM,
                            onClick = actions.onSelectCustom,
                        )
                    },
                ),
            )

            SegmentedColumn(
                content = buildList {
                    add {
                        SegmentedListItem(
                            onClick = actions.onImportFont,
                            headlineContent = { Text(stringResource(R.string.font_select_file)) },
                            leadingContent = { Icon(Icons.Filled.FontDownload, contentDescription = null) },
                        )
                    }
                    if (state.hasCustomFont) {
                        add {
                            SegmentedListItem(
                                onClick = actions.onRestoreDefault,
                                headlineContent = { Text(stringResource(R.string.font_restore_default)) },
                                leadingContent = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
                            )
                        }
                    }
                },
            )

            Spacer(Modifier.height(16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        }
    }
}
