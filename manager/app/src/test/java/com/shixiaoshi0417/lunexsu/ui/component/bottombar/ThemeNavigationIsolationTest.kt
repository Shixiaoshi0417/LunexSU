package com.shixiaoshi0417.lunexsu.ui.component.bottombar

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeNavigationIsolationTest {
    @Test fun ordinaryUiIgnoresEveryThemeNavigationMode() {
        for (mode in NavMode.entries) {
            for (largeWindow in listOf(false, true)) {
                for (floating in listOf(false, true)) {
                    assertEquals(largeWindow && !floating,
                        navigationRailEnabled(false, largeWindow, floating, mode))
                }
            }
        }
    }

    @Test fun themeUiKeepsFinaNavigationModesIndependentOfNativePreferences() {
        for (mode in NavMode.entries) {
            for (largeWindow in listOf(false, true)) {
                for (floating in listOf(false, true)) {
                    assertEquals(mode.usesRail(largeWindow),
                        navigationRailEnabled(true, largeWindow, floating, mode))
                }
            }
        }
    }

    @Test fun leavingThemeUiRestoresNativeNavigationPolicy() {
        assertEquals(false, navigationRailEnabled(true, true, false, NavMode.Floating))
        assertEquals(true, navigationRailEnabled(false, true, false, NavMode.Floating))
        assertEquals(true, navigationRailEnabled(true, false, true, NavMode.Rail))
        assertEquals(false, navigationRailEnabled(false, false, true, NavMode.Rail))
    }
}
