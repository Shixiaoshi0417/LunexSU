package com.shixiaoshi0417.lunexsu.ui.theme.pack

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePackContrastTest {
    private fun background(dim: Float = 0.2f, dual: Boolean = false, day: Float = 0.2f, night: Float = 0.4f) =
        PackBackground(true, false, 0.6f, 0f, dim, dual, day, night, mapOf(-1 to "background.png"))

    @Test fun singleDimAppliesToBothThemes() {
        val config = background(dim = 0.3f, day = 0.1f, night = 0.7f)
        assertEquals(0.3f, effectiveWallpaperDim(config, dark = false))
        assertEquals(0.3f, effectiveWallpaperDim(config, dark = true))
    }

    @Test fun dualDimPicksDayInLightAndNightInDark() {
        val config = background(dim = 0.3f, dual = true, day = 0.1f, night = 0.7f)
        assertEquals(0.1f, effectiveWallpaperDim(config, dark = false))
        assertEquals(0.7f, effectiveWallpaperDim(config, dark = true))
    }

    @Test fun averageLuminanceUsesPerceivedWeights() {
        assertEquals(1f, averageLuminance(IntArray(4) { Color.White.toArgb() }), 1e-4f)
        assertEquals(0f, averageLuminance(IntArray(4) { Color.Black.toArgb() }), 1e-4f)
        assertEquals(0.587f, averageLuminance(intArrayOf(Color(0xFF00FF00).toArgb())), 1e-3f)
        assertEquals(0.5f, averageLuminance(intArrayOf(Color.White.toArgb(), Color.Black.toArgb())), 1e-4f)
        assertEquals(-1f, averageLuminance(IntArray(0)))
    }

    @Test fun onlyALightThemeFlipsAndTheDimCounts() {
        assertTrue(useDarkNeutral(darkTheme = true, luminance = 1f, dim = 0f))
        assertFalse(useDarkNeutral(darkTheme = false, luminance = -1f, dim = 0.9f)) // unknown keeps polarity
        assertFalse(useDarkNeutral(darkTheme = false, luminance = 0.9f, dim = 0.2f)) // 0.72 stays light
        assertTrue(useDarkNeutral(darkTheme = false, luminance = 0.9f, dim = 0.5f)) // 0.45 reads dark
        assertTrue(useDarkNeutral(darkTheme = false, luminance = 0.3f, dim = 0f))
    }

    @Test fun neutralRolesComeFromTheNeutralSchemeAndAccentsStay() {
        val base = lightColorScheme(primary = Color.Red)
        val neutral = darkColorScheme()
        val adapted = adaptNeutralRoles(base, neutral)
        assertEquals(neutral.onSurface, adapted.onSurface)
        assertEquals(neutral.onBackground, adapted.onBackground)
        assertEquals(neutral.surfaceContainer, adapted.surfaceContainer)
        assertEquals(Color.Red, adapted.primary)
    }

    @Test fun guardRaisesTheScrimJustEnoughForLightText() {
        // Light text over a translucent card on a white wallpaper is unreadable without a scrim.
        val scheme = darkColorScheme(surface = Color.Transparent, surfaceContainer = Color(0x99211F26))
        assertFalse(passesContrast(0.2f, scheme))
        val guarded = guardedDim(0.2f, scheme)
        assertNotNull(guarded)
        val dim = guarded!!
        assertTrue(dim > 0.2f && dim <= 1f)
        assertTrue(passesContrast(dim, scheme))
        assertFalse(passesContrast(dim - 0.01f, scheme)) // the smallest sufficient scrim
    }

    @Test fun guardKeepsASufficientRequestAndGivesUpWhenImpossible() {
        val scheme = darkColorScheme(surface = Color.Transparent, surfaceContainer = Color(0x99211F26))
        assertEquals(0.95f, guardedDim(0.95f, scheme))
        // Text the same grey as an opaque card can never reach 4.5:1.
        val grey = Color(0xFF777777)
        val impossible = darkColorScheme(surface = grey, surfaceContainer = grey, onSurface = grey,
            onBackground = grey, onSurfaceVariant = grey)
        assertNull(guardedDim(0.2f, impossible))
    }
}
