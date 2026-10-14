package com.shixiaoshi0417.lunexsu.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.shixiaoshi0417.lunexsu.ui.theme.pack.ThemePackSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ColorGenerationModeTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = max(a.luminance(), b.luminance()) to min(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test fun onlyCustomSchemesTakeTheContrastBoost() {
        assertEquals(1.0, ColorGenerationMode.contrastLevel(ColorGenerationMode.CUSTOM, 1.0), 0.0)
        assertEquals(0.0, ColorGenerationMode.contrastLevel(ColorGenerationMode.CLASSIC, 1.0), 0.0)
        assertEquals(0.0, ColorGenerationMode.contrastLevel(ColorGenerationMode.CLASSIC, 0.5), 0.0)
    }

    @Test fun unknownStoredModesFallBackToCustom() {
        assertEquals(ColorGenerationMode.CLASSIC, ColorGenerationMode.fromValue("classic"))
        assertEquals(ColorGenerationMode.CUSTOM, ColorGenerationMode.fromValue(null))
        assertEquals(ColorGenerationMode.CUSTOM, ColorGenerationMode.fromValue("neon"))
        val defaults = AppSettings(ColorMode.SYSTEM, 0, PaletteStyle.TonalSpot, ColorSpec.SpecVersion.SPEC_2025)
        assertEquals(ColorGenerationMode.CUSTOM, defaults.colorGenerationMode)
    }

    @Test fun everyFptCatalogSeedHasAFixedClassicPalette() {
        for ((name, argb) in ThemePackSchema.colors) for (dark in listOf(false, true)) {
            val scheme = FptPalette.scheme(Color(argb), dark)
            assertNotNull("$name dark=$dark", scheme)
        }
        // The light table is keyed by its own primary; the dark one maps the seed to its primary.
        assertEquals(Color(0xFF0061A4), FptPalette.scheme(Color(0xFF0061A4), false)!!.primary)
        assertEquals(Color(0xFF9ECAFF), FptPalette.scheme(Color(0xFF0061A4), true)!!.primary)
        assertEquals(Color(0xFF9ECAFF), FptPalette.seed(Color(0xFF0061A4), true))
    }

    @Test fun seedsOutsideTheCatalogAreRegeneratedUnchanged() {
        val seed = Color(0xFF123456)
        assertNull(FptPalette.scheme(seed, false))
        assertEquals(seed, FptPalette.seed(seed, true))
    }

    @Test fun classicPalettesKeepBodyTextReadable() {
        // The point of classic mode: an imported pack keeps FolkPatch's tuned tables, whose text
        // roles meet WCAG AA on their surfaces in both brightnesses.
        for ((name, argb) in ThemePackSchema.colors) for (dark in listOf(false, true)) {
            val s = FptPalette.scheme(Color(argb), dark)!!
            assertTrue("$name dark=$dark onSurface", contrast(s.onSurface, s.surface) >= 4.5f)
            assertTrue("$name dark=$dark onSurfaceVariant", contrast(s.onSurfaceVariant, s.surfaceContainer) >= 4.5f)
            assertTrue("$name dark=$dark onPrimary", contrast(s.onPrimary, s.primary) >= 4.5f)
            assertTrue("$name dark=$dark onSecondaryContainer",
                contrast(s.onSecondaryContainer, s.secondaryContainer) >= 4.5f)
        }
    }
}
