// Theme store adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.themestore

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.themestore.DownloadProgress
import com.shixiaoshi0417.lunexsu.data.themestore.DownloadStatus
import com.shixiaoshi0417.lunexsu.data.themestore.LocalTheme
import com.shixiaoshi0417.lunexsu.data.themestore.RemoteTheme
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_SOURCE_ALL
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_SOURCE_OFFICIAL
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_SOURCE_THIRD_PARTY
import com.shixiaoshi0417.lunexsu.data.themestore.ThemeStoreFilter
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveDialog
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveScaffold
import com.shixiaoshi0417.lunexsu.ui.component.material.FolkButton
import com.shixiaoshi0417.lunexsu.ui.component.material.FolkIconButton
import com.shixiaoshi0417.lunexsu.ui.component.material.FolkTextButton
import com.shixiaoshi0417.lunexsu.ui.component.material.SearchAppBar
import com.shixiaoshi0417.lunexsu.ui.component.material.TonalCard
import com.shixiaoshi0417.lunexsu.ui.component.material.TopBarBackButton
import com.shixiaoshi0417.lunexsu.ui.theme.FolkShape
import com.shixiaoshi0417.lunexsu.ui.theme.FolkType

private val PageInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)

@Composable
fun ThemeStoreScreenMaterial(
    state: ThemeStoreScreenState,
    actions: ThemeStoreScreenActions,
) {
    val ui = state.ui
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()
    val gridState = rememberLazyStaggeredGridState()

    ExpressiveScaffold(
        topBar = {
            SearchAppBar(
                snackbarHostState = snackbarHostState,
                title = { Text(stringResource(R.string.theme_store_title)) },
                searchText = ui.filter.query,
                onSearchTextChange = actions.onSearchChange,
                onClearClick = { actions.onSearchChange("") },
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarBackButton(onClick = actions.onBack) },
                actions = {
                    FolkIconButton(onClick = actions.onOpenMyThemes) {
                        Icon(Icons.Rounded.ColorLens, contentDescription = stringResource(R.string.my_themes_title))
                    }
                    FolkIconButton(onClick = { actions.onShowFilter(true) }) {
                        Icon(Icons.Rounded.FilterList, contentDescription = stringResource(R.string.theme_store_filter_title))
                    }
                },
                defaultContent = { bottomPadding, _ ->
                    RemoteThemeGrid(
                        themes = ui.themes,
                        localThemes = ui.localThemes,
                        contentPadding = gridPadding(bottomPadding, top = 8.dp),
                        onSelect = actions.onSelect,
                    )
                },
                searchContent = { bottomPadding, _ ->
                    RemoteThemeGrid(
                        themes = ui.themes,
                        localThemes = ui.localThemes,
                        contentPadding = gridPadding(bottomPadding, top = 8.dp),
                        onSelect = actions.onSelect,
                    )
                },
            )
        },
        contentWindowInsets = PageInsets,
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                ui.isRefreshing && ui.themes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }

                ui.errorMessage != null && ui.themes.isEmpty() -> EmptyThemesMaterial(
                    message = ui.errorMessage,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    FolkButton(onClick = actions.onRefresh) { Text(stringResource(R.string.theme_store_retry)) }
                }

                else -> PullToRefreshBox(
                    modifier = Modifier.fillMaxSize(),
                    isRefreshing = ui.isRefreshing,
                    onRefresh = actions.onRefresh,
                    state = pullToRefreshState,
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            modifier = Modifier.align(Alignment.TopCenter),
                            isRefreshing = ui.isRefreshing,
                            state = pullToRefreshState,
                        )
                    },
                ) {
                    RemoteThemeGrid(
                        themes = ui.themes,
                        localThemes = ui.localThemes,
                        contentPadding = gridPadding(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                        gridState = gridState,
                        onSelect = actions.onSelect,
                    )
                }
            }
        }
    }

    ThemeStoreDialogsMaterial(state, actions)
}

@Composable
fun MyThemesScreenMaterial(
    state: MyThemesScreenState,
    actions: MyThemesScreenActions,
) {
    val ui = state.ui
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()
    val gridState = rememberLazyStaggeredGridState()

    ExpressiveScaffold(
        topBar = {
            SearchAppBar(
                snackbarHostState = snackbarHostState,
                title = { Text(stringResource(R.string.my_themes_title)) },
                searchText = ui.localSearchQuery,
                onSearchTextChange = actions.onSearchChange,
                onClearClick = { actions.onSearchChange("") },
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarBackButton(onClick = actions.onBack) },
                defaultContent = { bottomPadding, _ ->
                    LocalThemeGrid(
                        themes = ui.filteredLocalThemes,
                        contentPadding = gridPadding(bottomPadding, top = 8.dp),
                        onSelect = actions.onSelect,
                        onLongClick = actions.onRequestDelete,
                    )
                },
                searchContent = { bottomPadding, _ ->
                    LocalThemeGrid(
                        themes = ui.filteredLocalThemes,
                        contentPadding = gridPadding(bottomPadding, top = 8.dp),
                        onSelect = actions.onSelect,
                        onLongClick = actions.onRequestDelete,
                    )
                },
            )
        },
        contentWindowInsets = PageInsets,
    ) { paddingValues ->
        PullToRefreshBox(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            isRefreshing = ui.isRefreshingLocal,
            onRefresh = actions.onRefresh,
            state = pullToRefreshState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = ui.isRefreshingLocal,
                    state = pullToRefreshState,
                )
            },
        ) {
            if (ui.localThemes.isEmpty()) {
                EmptyThemesMaterial(stringResource(R.string.my_themes_empty), Modifier.fillMaxSize())
            } else {
                LocalThemeGrid(
                    themes = ui.filteredLocalThemes,
                    contentPadding = gridPadding(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    gridState = gridState,
                    onSelect = actions.onSelect,
                    onLongClick = actions.onRequestDelete,
                )
            }
        }
    }

    state.selected?.let { theme ->
        ExpressiveDialog(
            onDismissRequest = { actions.onSelect(null) },
            title = { Text(theme.name) },
            text = { ThemeDetailsMaterial(theme.author, theme.version, theme.type, theme.source, theme.description) },
            confirmButton = {
                FolkButton(onClick = { actions.onApply(theme) }, enabled = !state.importBusy) {
                    Text(stringResource(R.string.theme_store_apply))
                }
            },
            dismissButton = {
                FolkTextButton(onClick = { actions.onRequestDelete(theme); actions.onSelect(null) }) {
                    Text(stringResource(R.string.my_themes_delete))
                }
            },
        )
    }
    state.pendingDelete?.let { theme ->
        ExpressiveDialog(
            onDismissRequest = { actions.onRequestDelete(null) },
            title = { Text(stringResource(R.string.my_themes_delete_title)) },
            text = { Text(stringResource(R.string.my_themes_delete_message, theme.name)) },
            confirmButton = {
                FolkButton(onClick = { actions.onConfirmDelete(theme) }) { Text(stringResource(R.string.my_themes_delete)) }
            },
            dismissButton = {
                FolkTextButton(onClick = { actions.onRequestDelete(null) }) { Text(stringResource(R.string.theme_store_cancel)) }
            },
        )
    }
}

private fun gridPadding(bottom: Dp, top: Dp = 4.dp) = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = 16.dp + bottom)

@Composable
private fun RemoteThemeGrid(
    themes: List<RemoteTheme>,
    localThemes: List<LocalTheme>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    onSelect: (RemoteTheme) -> Unit,
) {
    if (themes.isEmpty()) {
        EmptyThemesMaterial(stringResource(R.string.theme_store_empty), Modifier.fillMaxSize())
        return
    }
    val localById = remember(localThemes) { localThemes.associateBy { it.id } }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(128.dp),
        modifier = modifier.fillMaxSize(),
        state = gridState,
        contentPadding = contentPadding,
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(themes, key = { it.id }) { theme ->
            ThemePreviewCardMaterial(
                name = theme.name,
                model = previewModel(localById[theme.id], theme.previewUrl),
                onClick = { onSelect(theme) },
            )
        }
    }
}

@Composable
private fun LocalThemeGrid(
    themes: List<LocalTheme>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    onSelect: (LocalTheme) -> Unit,
    onLongClick: (LocalTheme) -> Unit,
) {
    if (themes.isEmpty()) {
        EmptyThemesMaterial(stringResource(R.string.theme_store_empty), Modifier.fillMaxSize())
        return
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(128.dp),
        modifier = modifier.fillMaxSize(),
        state = gridState,
        contentPadding = contentPadding,
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(themes, key = { it.id }) { theme ->
            ThemePreviewCardMaterial(
                name = theme.name,
                model = previewModel(theme, theme.previewUrl),
                onClick = { onSelect(theme) },
                onLongClick = { onLongClick(theme) },
            )
        }
    }
}

/**
 * A preview tile that keeps the artwork's own aspect ratio and labels it with a soft bottom
 * gradient, so unknown image sizes stagger instead of being cropped into a fixed box.
 */
@Composable
private fun ThemePreviewCardMaterial(
    name: String,
    model: Any?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        shape = FolkShape.Corner16,
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Box {
            SubcomposeAsyncImage(
                model = remember(model) { ImageRequest.Builder(context).data(model).crossfade(true).build() },
                contentDescription = name,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
                loading = { PreviewPlaceholderMaterial(failed = false) },
                error = { PreviewPlaceholderMaterial(failed = true) },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = name,
                    style = FolkType.Caption,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PreviewPlaceholderMaterial(failed: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (failed) Icons.Outlined.BrokenImage else Icons.Outlined.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyThemesMaterial(
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(message, style = FolkType.Summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
private fun ThemeDetailsMaterial(author: String, version: String, type: String, source: String, description: String) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (author.isNotBlank()) Text(stringResource(R.string.theme_store_author, author), style = FolkType.Summary)
        if (version.isNotBlank()) Text(stringResource(R.string.theme_store_version, version), style = FolkType.Caption)
        Text("${themeTypeLabel(type)} · ${stringResource(R.string.theme_store_source_value, themeSourceLabel(source))}", style = FolkType.Caption)
        if (description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(description, style = FolkType.Summary)
        }
    }
}

@Composable
private fun ThemeStoreDialogsMaterial(state: ThemeStoreScreenState, actions: ThemeStoreScreenActions) {
    state.selected?.let { theme ->
        val downloaded = state.ui.hasCurrentRevision(theme)
        ExpressiveDialog(
            onDismissRequest = { actions.onSelect(null) },
            title = { Text(theme.name) },
            text = { ThemeDetailsMaterial(theme.author, theme.version, theme.type, theme.source, theme.description) },
            confirmButton = {
                if (downloaded) {
                    FolkButton(onClick = { actions.onApply(theme.id) }, enabled = !state.importBusy) {
                        Text(stringResource(R.string.theme_store_apply))
                    }
                } else {
                    FolkButton(onClick = { actions.onDownload(theme) }) { Text(stringResource(R.string.theme_store_download)) }
                }
            },
            dismissButton = {
                FolkTextButton(onClick = { actions.onSelect(null) }) { Text(stringResource(R.string.theme_store_cancel)) }
            },
        )
    }

    state.downloading?.let { theme ->
        DownloadProgressDialogMaterial(
            theme = theme,
            progress = state.progress[theme.id],
            onHide = actions.onHideProgress,
            onCancel = { actions.onCancelDownload(theme) },
            onTogglePause = { actions.onTogglePause(theme) },
        )
    }

    state.completed?.let { theme ->
        ExpressiveDialog(
            onDismissRequest = actions.onDismissCompleted,
            title = { Text(stringResource(R.string.theme_store_download_complete)) },
            text = { Text(stringResource(R.string.theme_store_download_complete_message, theme.name)) },
            confirmButton = {
                FolkButton(onClick = { actions.onApply(theme.id) }, enabled = !state.importBusy) {
                    Text(stringResource(R.string.theme_store_apply))
                }
            },
            dismissButton = {
                FolkTextButton(onClick = { actions.onDismissCompleted(); actions.onOpenMyThemes() }) {
                    Text(stringResource(R.string.theme_store_go_my_themes))
                }
            },
        )
    }

    if (state.showFilter) {
        ThemeFilterDialogMaterial(
            current = state.ui.filter,
            onApply = actions.onApplyFilter,
            onDismiss = { actions.onShowFilter(false) },
        )
    }
}

@Composable
private fun DownloadProgressDialogMaterial(
    theme: RemoteTheme,
    progress: DownloadProgress?,
    onHide: () -> Unit,
    onCancel: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val paused = progress?.status == DownloadStatus.PAUSED
    val fraction = progress?.overallProgress ?: 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "ThemeDownloadProgress",
    )
    ExpressiveDialog(
        onDismissRequest = onHide,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.theme_download_title)) },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = theme.previewUrl.takeIf { it.isNotBlank() },
                        contentDescription = theme.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(FolkShape.Corner12)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(theme.name, style = FolkType.Title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (theme.author.isNotBlank()) Text(theme.author, style = FolkType.Caption)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.theme_download_progress, (fraction * 100).toInt()), style = FolkType.Caption)
                Spacer(Modifier.height(8.dp))
                LinearWavyProgressIndicator(progress = { animatedFraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.theme_download_file, ((progress?.fileProgress ?: 0f) * 100).toInt()), style = FolkType.Caption)
                    Text(stringResource(R.string.theme_download_image, ((progress?.imageProgress ?: 0f) * 100).toInt()), style = FolkType.Caption)
                }
                if (paused) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.theme_download_paused), style = FolkType.Caption, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            FolkButton(onClick = onTogglePause, enabled = paused || progress?.status == DownloadStatus.DOWNLOADING) {
                Text(stringResource(if (paused) R.string.theme_download_resume else R.string.theme_download_pause))
            }
        },
        dismissButton = {
            FolkTextButton(onClick = onCancel) { Text(stringResource(R.string.theme_store_cancel)) }
        },
    )
}

@Composable
private fun ThemeFilterDialogMaterial(
    current: ThemeStoreFilter,
    onApply: (ThemeStoreFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var author by remember { mutableStateOf(current.author) }
    var source by remember { mutableStateOf(current.source) }
    var phone by remember { mutableStateOf(current.phone) }
    var tablet by remember { mutableStateOf(current.tablet) }

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_store_filter_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.theme_store_filter_author)) },
                    singleLine = true,
                    shape = FolkShape.Corner16,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.theme_store_filter_source), style = FolkType.Title)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        THEME_SOURCE_ALL to R.string.theme_store_filter_all,
                        THEME_SOURCE_OFFICIAL to R.string.theme_source_official,
                        THEME_SOURCE_THIRD_PARTY to R.string.theme_source_third_party,
                    ).forEach { (value, label) ->
                        FilterChip(selected = source == value, onClick = { source = value }, label = { Text(stringResource(label)) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.theme_store_filter_type), style = FolkType.Title)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = phone, onClick = { phone = !phone }, label = { Text(stringResource(R.string.theme_type_phone)) })
                    FilterChip(selected = tablet, onClick = { tablet = !tablet }, label = { Text(stringResource(R.string.theme_type_tablet)) })
                }
            }
        },
        confirmButton = {
            FolkButton(onClick = { onApply(current.copy(author = author, source = source, phone = phone, tablet = tablet)) }) {
                Text(stringResource(R.string.theme_store_filter_apply))
            }
        },
        dismissButton = {
            FolkTextButton(onClick = { onApply(ThemeStoreFilter(query = current.query)) }) {
                Text(stringResource(R.string.theme_store_filter_reset))
            }
        },
    )
}
