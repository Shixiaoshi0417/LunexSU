package com.shixiaoshi0417.lunexsu.ui.viewmodel

import androidx.compose.runtime.Immutable
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.screen.home.HomeLayoutOptions
import com.shixiaoshi0417.lunexsu.ui.theme.AppSettings

@Immutable
data class MainActivityUiState(
    val appSettings: AppSettings,
    val pageScale: Float,
    val enableBlur: Boolean,
    val enableFloatingBottomBar: Boolean,
    val enableFloatingBottomBarBlur: Boolean,
    val enableNavigationBadge: Boolean,
    val enableSwipeDismiss: Boolean,
    val pagerInterceptionMode: Int,
    val moduleDescriptionMaxLines: Int = 4,
    val uiMode: UiMode,
    val homeLayout: HomeLayoutOptions = HomeLayoutOptions(),
)
