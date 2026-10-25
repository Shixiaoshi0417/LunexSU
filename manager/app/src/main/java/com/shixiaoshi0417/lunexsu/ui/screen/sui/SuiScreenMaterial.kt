package com.shixiaoshi0417.lunexsu.ui.screen.sui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.BuildConfig
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.sui.SuiApp
import com.shixiaoshi0417.lunexsu.data.sui.SuiUiState
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveScaffold
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedColumn
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedItem
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedListItem
import com.shixiaoshi0417.lunexsu.ui.component.material.TopBarBackButton
import com.shixiaoshi0417.lunexsu.ui.component.material.expressiveTopAppBarColors
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFinaThemeUi

/**
 * Material and Fina layout. The bar takes the shared theme colors, so a pack wallpaper keeps its
 * reading platform instead of an opaque band, and the large bar collapses with the list.
 */
@Composable
internal fun SuiScreenMaterial(
    ui: SuiUiState,
    apps: List<SuiApp>,
    editable: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: (SuiApp) -> Unit,
    search: @Composable (Modifier) -> Unit,
) {
    val fina = LocalFinaThemeUi.current
    // A fixed theme bar must not consume gestures trying to collapse an unattached large bar.
    val scrollBehavior = if (fina) {
        TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    } else {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    }
    ExpressiveScaffold(
        topBar = {
            val title = stringResource(R.string.sui_title)
            val actions: @Composable RowScope.() -> Unit = {
                IconButton(enabled = !ui.busy, onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.susfs_refresh))
                }
            }
            if (fina) {
                TopAppBar(title = { Text(title) }, navigationIcon = { TopBarBackButton(onClick = onBack) },
                    actions = actions, colors = expressiveTopAppBarColors(), scrollBehavior = scrollBehavior)
            } else {
                LargeFlexibleTopAppBar(title = { Text(title) }, navigationIcon = { TopBarBackButton(onClick = onBack) },
                    actions = actions, colors = expressiveTopAppBarColors(), scrollBehavior = scrollBehavior)
            }
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner).imePadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "status") {
                SegmentedColumn(modifier = Modifier.padding(bottom = 11.dp)) {
                    item {
                        SegmentedListItem(
                            headlineContent = { Text(stringResource(R.string.sui_payload_version, ui.status.version.ifEmpty { "Sui" })) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(suiStatusText(ui))
                                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    if (ui.status.reason.isNotBlank()) Text(ui.status.reason)
                                    Text(stringResource(R.string.sui_request_dialog))
                                    if (BuildConfig.NO_ROOT_DEMO) Text(stringResource(R.string.sui_demo))
                                }
                            },
                            leadingContent = { Icon(Icons.Filled.Api, null) },
                        )
                    }
                }
            }
            item(key = "search") { search(Modifier.padding(bottom = 11.dp)) }
            if (apps.isNotEmpty()) item(key = "header") {
                Text(stringResource(R.string.sui_apps), Modifier.padding(start = 16.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            itemsIndexed(apps, key = { _, app -> app.uid }) { index, app ->
                SegmentedItem(index = index, count = apps.size) {
                    SegmentedListItem(
                        onClick = { onEdit(app) },
                        enabled = editable,
                        headlineContent = { Text(app.label,
                            maxLines = if (editable) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("UID ${app.uid} · ${app.packages}",
                                maxLines = if (editable) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            Text(stringResource(app.permission.label()), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }
        }
    }
}
