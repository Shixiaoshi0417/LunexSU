package com.shixiaoshi0417.lunexsu.ui.navigation3

import androidx.compose.runtime.Composable
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemeRouteSurface
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/** Preserve the navigation library's entry ownership, transitions and swipe handling. */
internal inline fun <reified T : Route> NavEntryBuilder.themeEntry(
    swipeDismiss: NavSwipeDirection,
    noinline content: @Composable (T) -> Unit,
) {
    entry<T>(swipeDismiss = swipeDismiss) { key ->
        ThemeRouteSurface(
            mainPage = key is Route.Main || key is Route.Home || key is Route.SuperUser ||
                key is Route.Module || key is Route.Settings,
            visible = LocalNavigator.current.current() == key,
        ) { content(key) }
    }
}
