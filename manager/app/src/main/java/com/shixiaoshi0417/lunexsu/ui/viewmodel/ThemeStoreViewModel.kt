// Theme store adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shixiaoshi0417.lunexsu.data.themestore.DownloadProgress
import com.shixiaoshi0417.lunexsu.data.themestore.DownloadStatus
import com.shixiaoshi0417.lunexsu.data.themestore.LocalTheme
import com.shixiaoshi0417.lunexsu.data.themestore.RemoteTheme
import com.shixiaoshi0417.lunexsu.data.themestore.THEME_STORE_URL
import com.shixiaoshi0417.lunexsu.data.themestore.ThemeDownloader
import com.shixiaoshi0417.lunexsu.data.themestore.ThemeStoreFilter
import com.shixiaoshi0417.lunexsu.data.themestore.matchesRevision
import com.shixiaoshi0417.lunexsu.data.themestore.parseThemeStoreIndex
import com.shixiaoshi0417.lunexsu.ksuApp
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

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

    fun hasCurrentRevision(theme: RemoteTheme): Boolean = localThemes.any { it.matchesRevision(theme) }

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
 * the completed private library and is applied through the regular FPT import confirmation,
 * so the store never changes a setting on its own.
 */
class ThemeStoreViewModel : ViewModel() {

    private val themeDownloader = ThemeDownloader(ksuApp)
    private var allThemes: List<RemoteTheme> = emptyList()

    private val _uiState = MutableStateFlow(ThemeStoreUiState())
    val uiState: StateFlow<ThemeStoreUiState> = _uiState.asStateFlow()
    private val downloadJobs = ThemeDownloadJobs(viewModelScope) { activeIds ->
        _uiState.update { it.copy(downloading = activeIds) }
    }

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
        if (_uiState.value.hasCurrentRevision(theme)) return
        downloadJobs.start(id) {
            themeDownloader.downloadTheme(theme).collect { progress ->
                if (progress.status == DownloadStatus.COMPLETED) {
                    val scanned = withContext(Dispatchers.IO) { themeDownloader.scanLocalThemes() }
                    downloadJobs.updateIfCurrent(id) {
                        _uiState.update { it.copy(localThemes = scanned) }
                    }
                }
            }
        }
    }

    fun cancelDownload(id: String) {
        downloadJobs.cancel(id) { themeDownloader.cancelDownload(id) }
    }

    fun pauseDownload(id: String) {
        themeDownloader.pauseDownload(id)
    }

    fun localTheme(id: String): LocalTheme? = _uiState.value.localThemes.firstOrNull { it.id == id }

    fun deleteTheme(localTheme: LocalTheme) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                themeDownloader.deleteLocalTheme(localTheme)
            }
            _uiState.update { state -> state.copy(localThemes = state.localThemes.filterNot { it.id == localTheme.id }) }
        }
    }

    fun loadLocalThemes() {
        _uiState.update { it.copy(isRefreshingLocal = true) }
        viewModelScope.launch {
            val scanned = withContext(Dispatchers.IO) { runCatching { themeDownloader.scanLocalThemes() }.getOrDefault(emptyList()) }
            _uiState.update { it.copy(localThemes = scanned, isRefreshingLocal = false) }
        }
    }

}
