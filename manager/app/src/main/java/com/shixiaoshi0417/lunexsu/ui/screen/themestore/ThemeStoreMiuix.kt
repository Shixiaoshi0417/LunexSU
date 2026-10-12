// Theme store adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.themestore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.shixiaoshi0417.lunexsu.ui.theme.LocalEnableBlur
import com.shixiaoshi0417.lunexsu.ui.theme.miuixBarColor
import com.shixiaoshi0417.lunexsu.ui.util.BlurredBar
import com.shixiaoshi0417.lunexsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun ThemeStoreScreenMiuix(
    state: ThemeStoreScreenState,
    actions: ThemeStoreScreenActions,
) {
    val ui = state.ui
    val localById = remember(ui.localThemes) { ui.localThemes.associateBy { it.id } }
    ThemeGridPageMiuix(
        title = stringResource(R.string.theme_store_title),
        onBack = actions.onBack,
        isRefreshing = ui.isRefreshing,
        onRefresh = actions.onRefresh,
        searchText = ui.filter.query,
        onSearchChange = actions.onSearchChange,
        topActions = {
            BarIconButton(Icons.Rounded.ColorLens, stringResource(R.string.my_themes_title), actions.onOpenMyThemes)
            BarIconButton(Icons.Rounded.FilterList, stringResource(R.string.theme_store_filter_title)) { actions.onShowFilter(true) }
        },
    ) {
        when {
            ui.isRefreshing && ui.themes.isEmpty() -> fullLine { CenteredMessage { InfiniteProgressIndicator() } }
            ui.errorMessage != null && ui.themes.isEmpty() -> fullLine {
                CenteredMessage(ui.errorMessage) {
                    TextButton(text = stringResource(R.string.theme_store_retry), onClick = actions.onRefresh)
                }
            }

            ui.themes.isEmpty() -> fullLine { CenteredMessage(stringResource(R.string.theme_store_empty)) }
            else -> items(ui.themes, key = { it.id }) { theme ->
                ThemePreviewCardMiuix(
                    name = theme.name,
                    model = previewModel(localById[theme.id], theme.previewUrl),
                    onClick = { actions.onSelect(theme) },
                )
            }
        }
    }

    ThemeStoreDialogsMiuix(state, actions)
}

@Composable
fun MyThemesScreenMiuix(
    state: MyThemesScreenState,
    actions: MyThemesScreenActions,
) {
    val ui = state.ui
    ThemeGridPageMiuix(
        title = stringResource(R.string.my_themes_title),
        onBack = actions.onBack,
        isRefreshing = ui.isRefreshingLocal,
        onRefresh = actions.onRefresh,
        searchText = ui.localSearchQuery,
        onSearchChange = actions.onSearchChange,
    ) {
        val themes = ui.filteredLocalThemes
        when {
            ui.localThemes.isEmpty() -> fullLine { CenteredMessage(stringResource(R.string.my_themes_empty)) }
            themes.isEmpty() -> fullLine { CenteredMessage(stringResource(R.string.theme_store_empty)) }
            else -> items(themes, key = { it.id }) { theme ->
                ThemePreviewCardMiuix(
                    name = theme.name,
                    model = previewModel(theme, theme.previewUrl),
                    onClick = { actions.onSelect(theme) },
                    onLongClick = { actions.onRequestDelete(theme) },
                )
            }
        }
    }

    val selected = rememberLatest(state.selected)
    WindowDialog(
        show = state.selected != null,
        title = selected?.name.orEmpty(),
        onDismissRequest = { actions.onSelect(null) },
    ) {
        selected?.let { theme ->
            ThemeDetailsMiuix(theme.author, theme.version, theme.type, theme.source, theme.description)
            DialogButtons(
                dismissText = stringResource(R.string.my_themes_delete),
                onDismiss = { actions.onSelect(null); actions.onRequestDelete(theme) },
                confirmText = stringResource(R.string.theme_store_apply),
                confirmEnabled = !state.importBusy,
                onConfirm = { actions.onApply(theme) },
            )
        }
    }

    val pendingDelete = rememberLatest(state.pendingDelete)
    WindowDialog(
        show = state.pendingDelete != null,
        title = stringResource(R.string.my_themes_delete_title),
        onDismissRequest = { actions.onRequestDelete(null) },
    ) {
        pendingDelete?.let { theme ->
            Text(stringResource(R.string.my_themes_delete_message, theme.name))
            DialogButtons(
                dismissText = stringResource(R.string.theme_store_cancel),
                onDismiss = { actions.onRequestDelete(null) },
                confirmText = stringResource(R.string.my_themes_delete),
                onConfirm = { actions.onConfirmDelete(theme) },
            )
        }
    }
}

/** Keeps the last non-null value so a dialog still has content while it animates out. */
@Composable
private fun <T> rememberLatest(value: T?): T? {
    var latest by remember { mutableStateOf(value) }
    if (value != null) latest = value
    return latest
}

private fun LazyStaggeredGridScope.fullLine(content: @Composable () -> Unit) {
    item(span = StaggeredGridItemSpan.FullLine) { content() }
}

@Composable
private fun ThemeGridPageMiuix(
    title: String,
    onBack: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    searchText: String,
    onSearchChange: (String) -> Unit,
    topActions: @Composable RowScope.() -> Unit = {},
    content: LazyStaggeredGridScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = miuixBarColor(backdrop != null)

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                PageTopBar(title, onBack, topActions, scrollBehavior, barColor)
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val pullToRefreshState = rememberPullToRefreshState()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
        PullToRefresh(
            isRefreshing = isRefreshing,
            pullToRefreshState = pullToRefreshState,
            onRefresh = onRefresh,
            refreshTexts = listOf(
                stringResource(R.string.refresh_pulling),
                stringResource(R.string.refresh_release),
                stringResource(R.string.refresh_refresh),
                stringResource(R.string.refresh_complete),
            ),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            GridBox(backdrop) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(128.dp),
                    state = rememberLazyStaggeredGridState(),
                    modifier = Modifier
                        .fillMaxHeight()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 12.dp,
                        bottom = innerPadding.calculateBottomPadding() + bottomInset + 12.dp,
                    ),
                    verticalItemSpacing = 12.dp,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    overscrollEffect = null,
                ) {
                    fullLine {
                        TextField(
                            value = searchText,
                            onValueChange = onSearchChange,
                            label = stringResource(R.string.theme_store_search),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun GridBox(backdrop: LayerBackdrop?, content: @Composable () -> Unit) {
    Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) { content() }
}

@Composable
private fun PageTopBar(
    title: String,
    onBack: () -> Unit,
    topActions: @Composable RowScope.() -> Unit,
    scrollBehavior: ScrollBehavior,
    barColor: Color,
) {
    TopAppBar(
        color = barColor,
        title = title,
        navigationIcon = {
            IconButton(onClick = onBack) {
                val layoutDirection = LocalLayoutDirection.current
                Icon(
                    modifier = Modifier.graphicsLayer {
                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                    },
                    imageVector = MiuixIcons.Back,
                    contentDescription = null,
                    tint = colorScheme.onBackground,
                )
            }
        },
        actions = topActions,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun BarIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description, tint = colorScheme.onBackground)
    }
}

@Composable
private fun CenteredMessage(message: String? = null, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (message != null) {
            Text(message, color = colorScheme.onSurfaceVariantSummary, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        action?.invoke()
    }
}

@Composable
private fun ThemePreviewCardMiuix(
    name: String,
    model: Any?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
        onLongPress = onLongClick,
    ) {
        Box {
            SubcomposeAsyncImage(
                model = remember(model) { ImageRequest.Builder(context).data(model).crossfade(true).build() },
                contentDescription = name,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
                loading = { PreviewPlaceholderMiuix(failed = false) },
                error = { PreviewPlaceholderMiuix(failed = true) },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(text = name, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PreviewPlaceholderMiuix(failed: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .background(colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (failed) Icons.Outlined.BrokenImage else Icons.Outlined.Image,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ThemeDetailsMiuix(author: String, version: String, type: String, source: String, description: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (author.isNotBlank()) Text(stringResource(R.string.theme_store_author, author))
        if (version.isNotBlank()) {
            Text(stringResource(R.string.theme_store_version, version), color = colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
        }
        Text(
            "${themeTypeLabel(type)} · ${stringResource(R.string.theme_store_source_value, themeSourceLabel(source))}",
            color = colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
        )
        if (description.isNotBlank()) {
            Text(
                description,
                modifier = Modifier.padding(top = 8.dp).heightIn(max = MaxDescriptionHeight).verticalScroll(rememberScrollState()),
                style = MiuixTheme.textStyles.body2,
            )
        }
    }
}

private val MaxDescriptionHeight = 160.dp

@Composable
private fun DialogButtons(
    dismissText: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.padding(top = 16.dp)) {
        TextButton(text = dismissText, onClick = onDismiss, enabled = dismissEnabled, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(20.dp))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun ThemeStoreDialogsMiuix(state: ThemeStoreScreenState, actions: ThemeStoreScreenActions) {
    val selected = rememberLatest(state.selected)
    WindowDialog(
        show = state.selected != null,
        title = selected?.name.orEmpty(),
        onDismissRequest = { actions.onSelect(null) },
    ) {
        selected?.let { theme ->
            val downloaded = theme.id in state.ui.downloadedIds
            ThemeDetailsMiuix(theme.author, theme.version, theme.type, theme.source, theme.description)
            DialogButtons(
                dismissText = stringResource(R.string.theme_store_cancel),
                onDismiss = { actions.onSelect(null) },
                confirmText = stringResource(if (downloaded) R.string.theme_store_apply else R.string.theme_store_download),
                confirmEnabled = !downloaded || !state.importBusy,
                onConfirm = { if (downloaded) actions.onApply(theme.id) else actions.onDownload(theme) },
            )
        }
    }

    val downloading = rememberLatest(state.downloading)
    WindowDialog(
        show = state.downloading != null,
        title = stringResource(R.string.theme_download_title),
        onDismissRequest = actions.onHideProgress,
    ) {
        downloading?.let { theme -> DownloadProgressMiuix(theme, state.progress[theme.id], actions) }
    }

    val completed = rememberLatest(state.completed)
    WindowDialog(
        show = state.completed != null,
        title = stringResource(R.string.theme_store_download_complete),
        onDismissRequest = actions.onDismissCompleted,
    ) {
        completed?.let { theme ->
            Text(stringResource(R.string.theme_store_download_complete_message, theme.name))
            DialogButtons(
                dismissText = stringResource(R.string.theme_store_go_my_themes),
                onDismiss = { actions.onDismissCompleted(); actions.onOpenMyThemes() },
                confirmText = stringResource(R.string.theme_store_apply),
                confirmEnabled = !state.importBusy,
                onConfirm = { actions.onApply(theme.id) },
            )
        }
    }

    WindowDialog(
        show = state.showFilter,
        title = stringResource(R.string.theme_store_filter_title),
        onDismissRequest = { actions.onShowFilter(false) },
    ) {
        ThemeFilterMiuix(state.ui.filter, actions.onApplyFilter)
    }
}

@Composable
private fun DownloadProgressMiuix(theme: RemoteTheme, progress: DownloadProgress?, actions: ThemeStoreScreenActions) {
    val paused = progress?.status == DownloadStatus.PAUSED
    val fraction = progress?.overallProgress ?: 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(theme.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (theme.author.isNotBlank()) Text(theme.author, color = colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.theme_download_progress, (fraction * 100).toInt()), fontSize = 13.sp)
            Text(
                stringResource(R.string.theme_download_file, ((progress?.fileProgress ?: 0f) * 100).toInt()) + "  " +
                    stringResource(R.string.theme_download_image, ((progress?.imageProgress ?: 0f) * 100).toInt()),
                color = colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
            )
        }
        if (paused) {
            Text(stringResource(R.string.theme_download_paused), color = colorScheme.primary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        DialogButtons(
            dismissText = stringResource(R.string.theme_store_cancel),
            onDismiss = { actions.onCancelDownload(theme) },
            confirmText = stringResource(if (paused) R.string.theme_download_resume else R.string.theme_download_pause),
            confirmEnabled = paused || progress?.status == DownloadStatus.DOWNLOADING,
            onConfirm = { actions.onTogglePause(theme) },
        )
    }
}

@Composable
private fun ThemeFilterMiuix(current: ThemeStoreFilter, onApply: (ThemeStoreFilter) -> Unit) {
    var author by remember(current) { mutableStateOf(current.author) }
    var source by remember(current) { mutableStateOf(current.source) }
    var phone by remember(current) { mutableStateOf(current.phone) }
    var tablet by remember(current) { mutableStateOf(current.tablet) }
    val sources = listOf(THEME_SOURCE_ALL, THEME_SOURCE_OFFICIAL, THEME_SOURCE_THIRD_PARTY)

    Column(modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = author,
            onValueChange = { author = it },
            label = stringResource(R.string.theme_store_filter_author),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SmallTitle(text = stringResource(R.string.theme_store_filter_source), insideMargin = PaddingValues(4.dp, 12.dp, 4.dp, 8.dp))
        TabRow(
            tabs = listOf(
                stringResource(R.string.theme_store_filter_all),
                stringResource(R.string.theme_source_official),
                stringResource(R.string.theme_source_third_party),
            ),
            selectedTabIndex = sources.indexOf(source).coerceAtLeast(0),
            onTabSelected = { source = sources[it] },
        )
        SmallTitle(text = stringResource(R.string.theme_store_filter_type), insideMargin = PaddingValues(4.dp, 12.dp, 4.dp, 0.dp))
        CheckboxPreference(
            title = stringResource(R.string.theme_type_phone),
            checked = phone,
            onCheckedChange = { phone = it },
            insideMargin = PaddingValues(4.dp, 8.dp),
        )
        CheckboxPreference(
            title = stringResource(R.string.theme_type_tablet),
            checked = tablet,
            onCheckedChange = { tablet = it },
            insideMargin = PaddingValues(4.dp, 8.dp),
        )
        DialogButtons(
            dismissText = stringResource(R.string.theme_store_filter_reset),
            onDismiss = { onApply(ThemeStoreFilter(query = current.query)) },
            confirmText = stringResource(R.string.theme_store_filter_apply),
            onConfirm = { onApply(current.copy(author = author, source = source, phone = phone, tablet = tablet)) },
        )
    }
}
