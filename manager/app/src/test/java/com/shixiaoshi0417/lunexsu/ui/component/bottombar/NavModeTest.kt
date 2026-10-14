package com.shixiaoshi0417.lunexsu.ui.component.bottombar

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavModeTest {
    @Test fun aFreshInstallUsesTheFloatingBarLikeFinaSu() {
        assertEquals(NavMode.Floating, NavMode.fromValue(null))
    }

    @Test fun storedValuesRoundTripAndUnknownOnesFallBackToAuto() {
        for (mode in NavMode.entries) assertEquals(mode, NavMode.fromValue(mode.storageValue))
        assertEquals(NavMode.Rail, NavMode.fromValue("RAIL"))
        assertEquals(NavMode.Auto, NavMode.fromValue("sideways"))
    }

    @Test fun onlyRailAndAutoInALargeWindowShowTheRail() {
        assertTrue(NavMode.Rail.usesRail(largeWindow = false))
        assertTrue(NavMode.Auto.usesRail(largeWindow = true))
        assertFalse(NavMode.Auto.usesRail(largeWindow = false))
        for (large in listOf(false, true)) {
            assertFalse(NavMode.Bottom.usesRail(large))
            assertFalse(NavMode.Floating.usesRail(large))
        }
    }

    @Test fun pagesReserveTheFloatingBarHeightPlusItsMargins() {
        assertEquals(96.dp, floatingBarReservedHeight(compact = true, style = FloatingBarConfig.Style.Drawer))
        assertEquals(96.dp, floatingBarReservedHeight(compact = false, style = FloatingBarConfig.Style.Drawer))
        assertEquals(100.dp, floatingBarReservedHeight(compact = true, style = FloatingBarConfig.Style.Standard))
        assertEquals(104.dp, floatingBarReservedHeight(compact = false, style = FloatingBarConfig.Style.Standard))
    }

    @Test fun eachEnabledHideBehaviorCanTakeTheBarAway() {
        // Neither behavior: always shown.
        assertTrue(floatingBarVisible(timerVisible = false, scrollingDown = true, autoHide = false, swipeHide = false))
        // Swipe only: hidden while scrolling down.
        assertFalse(floatingBarVisible(timerVisible = true, scrollingDown = true, autoHide = false, swipeHide = true))
        assertTrue(floatingBarVisible(timerVisible = false, scrollingDown = false, autoHide = false, swipeHide = true))
        // Timer only: follows the three second timer.
        assertFalse(floatingBarVisible(timerVisible = false, scrollingDown = false, autoHide = true, swipeHide = false))
        assertTrue(floatingBarVisible(timerVisible = true, scrollingDown = true, autoHide = true, swipeHide = false))
        // Both (the default): either one hides it.
        assertTrue(floatingBarVisible(timerVisible = true, scrollingDown = false, autoHide = true, swipeHide = true))
        assertFalse(floatingBarVisible(timerVisible = true, scrollingDown = true, autoHide = true, swipeHide = true))
        assertFalse(floatingBarVisible(timerVisible = false, scrollingDown = false, autoHide = true, swipeHide = true))
    }
}
