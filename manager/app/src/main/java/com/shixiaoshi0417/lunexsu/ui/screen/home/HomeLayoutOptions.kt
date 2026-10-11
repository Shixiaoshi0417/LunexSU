package com.shixiaoshi0417.lunexsu.ui.screen.home

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.shixiaoshi0417.lunexsu.data.model.HomeLayoutStyle

/**
 * The home personalisation the Material UI renders: which layout, how the Stats layout opens and
 * whether the status card shows its LKM/GKI tag. Provided once at the top of the app so switching a
 * preference recomposes the home screen without a restart.
 */
@Immutable
data class HomeLayoutOptions(
    val style: String = HomeLayoutStyle.DEFAULT,
    val statsTopLayout: String = HomeLayoutStyle.STATS_TOP_STATUS,
    val hideWorkingMode: Boolean = false,
)

val LocalHomeLayoutOptions = staticCompositionLocalOf { HomeLayoutOptions() }
