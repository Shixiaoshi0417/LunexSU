// Theme store adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.themestore

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.themestore.DownloadProgress
import com.shixiaoshi0417.lunexsu.data.themestore.DownloadStatus
import com.shixiaoshi0417.lunexsu.data.themestore.LocalTheme
import com.shixiaoshi0417.lunexsu.data.themestore.RemoteTheme
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_SOURCE_OFFICIAL
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_SOURCE_THIRD_PARTY
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_TYPE_PHONE
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_TYPE_TABLET
import com.shixiaoshi0417.lunexsu.data.themestore.ThemeStoreFilter
import com.shixiaoshi0417.lunexsu.ksuApp
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.navigation3.LocalNavigator
import com.shixiaoshi0417.lunexsu.ui.navigation3.Route
import com.shixiaoshi0417.lunexsu.ui.screen.colorpalette.rememberThemePackActions
import com.shixiaoshi0417.lunexsu.ui.viewmodel.ThemeStoreUiState
import com.shixiaoshi0417.lunexsu.ui.viewmodel.ThemeStoreViewModel
import java.io.File

@Immutable
data class ThemeStoreScreenState(
    val ui: ThemeStoreUiState,
    val progress: Map<String, DownloadProgress>,
    val selected: RemoteTheme?,
    val downloading: RemoteTheme?,
    val completed: RemoteTheme?,
    val showFilter: Boolean,
    val importBusy: Boolean,
)

@Immutable
data class ThemeStoreScreenActions(
    val onBack: () -> Unit,
    val onOpenMyThemes: () -> Unit,
    val onRefresh: () -> Unit,
    val onSearchChange: (String) -> Unit,
    val onSelect: (RemoteTheme?) -> Unit,
    val onHideProgress: () -> Unit,
    val onDownload: (RemoteTheme) -> Unit,
    val onApply: (String) -> Unit,
    val onCancelDownload: (RemoteTheme) -> Unit,
    val onTogglePause: (RemoteTheme) -> Unit,
    val onDismissCompleted: () -> Unit,
    val onShowFilter: (Boolean) -> Unit,
    val onApplyFilter: (ThemeStoreFilter) -> Unit,
)

@Immutable
data class MyThemesScreenState(
    val ui: ThemeStoreUiState,
    val selected: LocalTheme?,
    val pendingDelete: LocalTheme?,
    val importBusy: Boolean,
)

@Immutable
data class MyThemesScreenActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSearchChange: (String) -> Unit,
    val onSelect: (LocalTheme?) -> Unit,
    val onApply: (LocalTheme) -> Unit,
    val onRequestDelete: (LocalTheme?) -> Unit,
    val onConfirmDelete: (LocalTheme) -> Unit,
)

/** The store and the library share one application-scoped model, so downloads outlive the page. */
@Composable
private fun themeStoreViewModel(): ThemeStoreViewModel = viewModel(viewModelStoreOwner = ksuApp)

/**
 * The online theme store: remote `.fpt` themes from the FinaSU (FolkPatch) store index, with
 * search, filters, resumable downloads and a jump to the local library. Downloaded packages are
 * applied through the regular FPT import confirmation, so nothing changes without the user's OK.
 */
@Composable
fun ThemeStoreScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val viewModel = themeStoreViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val progressMap by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val themePack = rememberThemePackActions(onApplied = {})
    val applyFailed = stringResource(R.string.theme_store_apply_failed)
    val downloadFailed = stringResource(R.string.theme_store_download_failed)

    var selected by remember { mutableStateOf<RemoteTheme?>(null) }
    var downloading by remember { mutableStateOf<RemoteTheme?>(null) }
    var completed by remember { mutableStateOf<RemoteTheme?>(null) }
    var showFilter by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadLocalThemes() }

    val active = downloading
    LaunchedEffect(progressMap, active) {
        if (active == null) return@LaunchedEffect
        val progress = progressMap[active.id]
        when (progress?.status) {
            DownloadStatus.COMPLETED -> {
                downloading = null
                completed = active
            }

            DownloadStatus.FAILED -> {
                downloading = null
                Toast.makeText(context, progress.errorMessage?.let { "$downloadFailed: $it" } ?: downloadFailed, Toast.LENGTH_LONG).show()
            }

            else -> Unit
        }
    }

    val state = ThemeStoreScreenState(
        ui = uiState,
        progress = progressMap,
        selected = selected,
        downloading = downloading,
        completed = completed,
        showFilter = showFilter,
        importBusy = themePack.busy,
    )
    val actions = ThemeStoreScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenMyThemes = dropUnlessResumed { navigator.push(Route.MyThemes) },
        onRefresh = viewModel::refresh,
        onSearchChange = viewModel::onSearchQueryChange,
        onSelect = { theme ->
            // A running download reopens its progress instead of the details.
            if (theme != null && theme.id in uiState.downloading) downloading = theme else selected = theme
        },
        onHideProgress = { downloading = null },
        onDownload = { theme ->
            selected = null
            viewModel.startDownload(theme)
            downloading = theme
        },
        onApply = { id ->
            selected = null
            completed = null
            val local = viewModel.localTheme(id)
            if (local != null && File(local.localPath).isFile) {
                themePack.onImportUri(Uri.fromFile(File(local.localPath)))
            } else {
                Toast.makeText(context, applyFailed, Toast.LENGTH_SHORT).show()
                viewModel.loadLocalThemes()
            }
        },
        onCancelDownload = { theme ->
            viewModel.cancelDownload(theme.id)
            downloading = null
        },
        onTogglePause = { theme ->
            if (progressMap[theme.id]?.status == DownloadStatus.PAUSED) {
                viewModel.startDownload(theme)
            } else {
                viewModel.pauseDownload(theme.id)
            }
        },
        onDismissCompleted = { completed = null },
        onShowFilter = { showFilter = it },
        onApplyFilter = { filter ->
            viewModel.updateFilter(filter)
            showFilter = false
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> ThemeStoreScreenMiuix(state, actions)
        UiMode.Material -> ThemeStoreScreenMaterial(state, actions)
    }
}

/**
 * The local theme library: every downloaded package, applied with a tap (through the FPT import
 * confirmation) and removed with a long press.
 */
@Composable
fun MyThemesScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val viewModel = themeStoreViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val themePack = rememberThemePackActions(onApplied = {})
    val applyFailed = stringResource(R.string.theme_store_apply_failed)

    var selected by remember { mutableStateOf<LocalTheme?>(null) }
    var pendingDelete by remember { mutableStateOf<LocalTheme?>(null) }

    LaunchedEffect(Unit) { viewModel.loadLocalThemes() }

    val state = MyThemesScreenState(
        ui = uiState,
        selected = selected,
        pendingDelete = pendingDelete,
        importBusy = themePack.busy,
    )
    val actions = MyThemesScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onRefresh = viewModel::loadLocalThemes,
        onSearchChange = viewModel::onLocalSearchQueryChange,
        onSelect = { selected = it },
        onApply = { theme ->
            selected = null
            val file = File(theme.localPath)
            if (file.isFile) {
                themePack.onImportUri(Uri.fromFile(file))
            } else {
                Toast.makeText(context, applyFailed, Toast.LENGTH_SHORT).show()
                viewModel.loadLocalThemes()
            }
        },
        onRequestDelete = { pendingDelete = it },
        onConfirmDelete = { theme ->
            pendingDelete = null
            viewModel.deleteTheme(theme)
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> MyThemesScreenMiuix(state, actions)
        UiMode.Material -> MyThemesScreenMaterial(state, actions)
    }
}

/** A local preview file wins over the remote URL, so the library also works offline. */
internal fun previewModel(local: LocalTheme?, remoteUrl: String): Any? =
    local?.previewImagePath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
        ?: remoteUrl.takeIf { it.isNotBlank() }

@Composable
internal fun themeSourceLabel(source: String): String = when (source) {
    THEME_SOURCE_OFFICIAL -> stringResource(R.string.theme_source_official)
    THEME_SOURCE_THIRD_PARTY -> stringResource(R.string.theme_source_third_party)
    else -> source
}

@Composable
internal fun themeTypeLabel(type: String): String = when (type) {
    THEME_TYPE_PHONE -> stringResource(R.string.theme_type_phone)
    THEME_TYPE_TABLET -> stringResource(R.string.theme_type_tablet)
    else -> type
}
