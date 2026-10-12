// Theme store adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shixiaoshi0417.lunexsu.data.themestore.DownloadProgress
import com.shixiaoshi0417.lunexsu.data.themestore.DownloadStatus
import com.shixiaoshi0417.lunexsu.data.themestore.LocalTheme
import com.shixiaoshi0417.lunexsu.data.themestore.RemoteTheme
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_SOURCE_THIRD_PARTY
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_STORE_URL
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_TYPE_PHONE
import com.shixiaoshi0417.lunexsu.data.themestore.ThemeDownloader
import com.shixiaoshi0417.lunexsu.data.themestore.ThemeStoreFilter
import com.shixiaoshi0417.lunexsu.data.themestore.normalizeStoreUrl
import com.shixiaoshi0417.lunexsu.data.themestore.parseThemeStoreIndex
import com.shixiaoshi0417.lunexsu.ksuApp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class ThemeStoreUiState(
    val themes: List<RemoteTheme> = emptyList(),
    val totalThemes: Int = 0,
    val filter: ThemeStoreFilter = ThemeStoreFilter(),
    val localThemes: List<LocalTheme> = emptyList(),
    val localSearchQuery: String = "",
    val downloading: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val isRefreshingLocal: Boolean = false,
    val errorMessage: String? = null,
) {
    val downloadedIds: Set<String> get() = localThemes.mapTo(HashSet()) { it.id }

    val filteredLocalThemes: List<LocalTheme>
        get() {
            val query = localSearchQuery.trim()
            if (query.isEmpty()) return localThemes
            return localThemes.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.author.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
            }
        }
}

/**
 * Backs the online theme store and the local "my themes" library.
 *
 * Remote themes come from the FolkPatch-compatible index FinaSU uses; a downloaded `.fpt` lands in
 * `filesDir/themes/<author>/<name>/` and is applied through the regular FPT import confirmation,
 * so the store never changes a setting on its own.
 */
class ThemeStoreViewModel : ViewModel() {

    private val themeDownloader = ThemeDownloader(ksuApp)
    private var allThemes: List<RemoteTheme> = emptyList()
    private val downloadJobs = mutableMapOf<String, Job>()

    private val _uiState = MutableStateFlow(ThemeStoreUiState())
    val uiState: StateFlow<ThemeStoreUiState> = _uiState.asStateFlow()

    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = themeDownloader.downloadProgress

    init {
        // The local library lives on disk, so load it first and let the network refresh follow.
        loadLocalThemes()
        fetchThemes()
    }

    fun refresh() {
        loadLocalThemes()
        fetchThemes()
    }

    private fun fetchThemes() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { parseThemeStoreIndex(fetchIndex()) }
            }
            result.onSuccess { remote ->
                allThemes = remote
                _uiState.update { state ->
                    state.copy(themes = remote.filter(state.filter::matches), totalThemes = remote.size)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update { it.copy(errorMessage = error.message ?: error::class.java.simpleName) }
            }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun fetchIndex(): String {
        val request = Request.Builder().url(THEME_STORE_URL).build()
        ksuApp.okhttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body.string()
        }
    }

    fun onSearchQueryChange(query: String) = updateFilter(_uiState.value.filter.copy(query = query))

    fun updateFilter(filter: ThemeStoreFilter) {
        _uiState.update { it.copy(filter = filter, themes = allThemes.filter(filter::matches)) }
    }

    fun onLocalSearchQueryChange(query: String) {
        _uiState.update { it.copy(localSearchQuery = query) }
    }

    fun startDownload(theme: RemoteTheme) {
        val id = theme.id
        if (downloadJobs.containsKey(id) || id in _uiState.value.downloadedIds) return
        _uiState.update { it.copy(downloading = it.downloading + id) }
        downloadJobs[id] = viewModelScope.launch {
            try {
                themeDownloader.downloadTheme(theme).collect { progress ->
                    if (progress.status == DownloadStatus.COMPLETED) addLocalTheme(theme)
                }
            } finally {
                downloadJobs.remove(id)
                _uiState.update { it.copy(downloading = it.downloading - id) }
            }
        }
    }

    fun cancelDownload(id: String) {
        downloadJobs.remove(id)?.cancel()
        themeDownloader.cancelDownload(id)
        _uiState.update { it.copy(downloading = it.downloading - id) }
    }

    fun pauseDownload(id: String) {
        themeDownloader.pauseDownload(id)
    }

    fun localTheme(id: String): LocalTheme? = _uiState.value.localThemes.firstOrNull { it.id == id }

    fun deleteTheme(localTheme: LocalTheme) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val root = themeDownloader.getThemesDir().canonicalFile
                val directory = File(localTheme.localPath).parentFile?.canonicalFile
                // Only ever remove a theme directory inside our own library.
                if (directory != null && directory.parentFile?.parentFile == root) {
                    directory.deleteRecursively()
                    directory.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
                }
            }
            _uiState.update { state -> state.copy(localThemes = state.localThemes.filterNot { it.id == localTheme.id }) }
        }
    }

    fun loadLocalThemes() {
        _uiState.update { it.copy(isRefreshingLocal = true) }
        viewModelScope.launch {
            val scanned = withContext(Dispatchers.IO) { runCatching { scanLocalThemes() }.getOrDefault(emptyList()) }
            _uiState.update { it.copy(localThemes = scanned, isRefreshingLocal = false) }
        }
    }

    private fun scanLocalThemes(): List<LocalTheme> {
        val themesDir = themeDownloader.getThemesDir()
        if (!themesDir.isDirectory) return emptyList()
        val result = mutableListOf<LocalTheme>()
        themesDir.listFiles()?.sortedBy { it.name }?.forEach { authorDir ->
            if (!authorDir.isDirectory) return@forEach
            authorDir.listFiles()?.sortedBy { it.name }?.forEach { themeDir ->
                if (!themeDir.isDirectory) return@forEach
                val themeFile = themeDir.listFiles()
                    ?.firstOrNull { it.isFile && it.name.endsWith(".fpt") }
                    ?: return@forEach
                val previewFile = File(themeDir, ThemeDownloader.PREVIEW_FILE_NAME)
                result += readLocalTheme(themeFile, previewFile.takeIf { it.isFile }, authorDir.name)
            }
        }
        return result.distinctBy { it.id }
    }

    private fun readLocalTheme(themeFile: File, previewFile: File?, author: String): LocalTheme {
        val name = themeFile.nameWithoutExtension
        val json = runCatching {
            JSONObject(File(themeFile.parentFile, ThemeDownloader.META_FILE_NAME).readText())
        }.getOrNull()
        return LocalTheme(
            id = json?.optString("id")?.takeIf { it.isNotBlank() } ?: "${author}_$name",
            name = json?.optString("name")?.takeIf { it.isNotBlank() } ?: name,
            author = json?.optString("author")?.takeIf { it.isNotBlank() } ?: author,
            description = json?.optString("description", "").orEmpty(),
            version = json?.optString("version", "").orEmpty(),
            previewUrl = normalizeStoreUrl(json?.optString("previewUrl", "").orEmpty()),
            downloadUrl = normalizeStoreUrl(json?.optString("downloadUrl", "").orEmpty()),
            type = json?.optString("type")?.takeIf { it.isNotBlank() } ?: THEME_TYPE_PHONE,
            source = json?.optString("source")?.takeIf { it.isNotBlank() } ?: THEME_SOURCE_THIRD_PARTY,
            localPath = themeFile.absolutePath,
            previewImagePath = previewFile?.absolutePath.orEmpty(),
        )
    }

    private fun addLocalTheme(remoteTheme: RemoteTheme) {
        val file = themeDownloader.getThemeFilePath(remoteTheme.author, remoteTheme.name)
        val preview = themeDownloader.getPreviewImagePath(remoteTheme.author, remoteTheme.name)
        val local = LocalTheme(
            id = remoteTheme.id,
            name = remoteTheme.name,
            author = remoteTheme.author,
            description = remoteTheme.description,
            version = remoteTheme.version,
            previewUrl = remoteTheme.previewUrl,
            downloadUrl = remoteTheme.downloadUrl,
            type = remoteTheme.type,
            source = remoteTheme.source,
            localPath = file.absolutePath,
            previewImagePath = if (preview.isFile) preview.absolutePath else "",
        )
        _uiState.update { state -> state.copy(localThemes = state.localThemes.filterNot { it.id == local.id } + local) }
    }
}
