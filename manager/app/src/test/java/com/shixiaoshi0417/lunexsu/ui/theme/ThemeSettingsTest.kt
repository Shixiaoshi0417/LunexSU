package com.shixiaoshi0417.lunexsu.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsTest {

    @Test
    fun legacyAmoledModeSplitsIntoDarkPlusSwitch() {
        assertEquals(ColorMode.DARK.value to true, ThemePreferenceMigration.splitAmoled(6, false))
        assertEquals(ColorMode.DARK.value to true, ThemePreferenceMigration.splitAmoled(6, true))
    }

    @Test
    fun nonLegacyModesKeepTheirAmoledSwitch() {
        for (mode in 0..5) {
            assertEquals(mode to false, ThemePreferenceMigration.splitAmoled(mode, false))
            assertEquals(mode to true, ThemePreferenceMigration.splitAmoled(mode, true))
        }
    }

    @Test
    fun monetDefaultsOnOnlyWithoutCustomKeyColor() {
        assertTrue(ThemePreferenceMigration.defaultUseDynamicColor(0))
        assertFalse(ThemePreferenceMigration.defaultUseDynamicColor(0xFFF44336.toInt()))
    }

    @Test
    fun baseIndexIgnoresMiuixMonetFlag() {
        assertEquals(0, ColorMode.SYSTEM.baseIndex)
        assertEquals(0, ColorMode.MONET_SYSTEM.baseIndex)
        assertEquals(1, ColorMode.LIGHT.baseIndex)
        assertEquals(1, ColorMode.MONET_LIGHT.baseIndex)
        assertEquals(2, ColorMode.DARK.baseIndex)
        assertEquals(2, ColorMode.MONET_DARK.baseIndex)
        assertEquals(2, ColorMode.DARK_AMOLED.baseIndex)
    }

    @Test
    fun contrastFallsBackToStandard() {
        assertEquals(ColorContrast.HIGH, ColorContrast.fromName("HIGH"))
        assertEquals(ColorContrast.STANDARD, ColorContrast.fromName(null))
        assertEquals(ColorContrast.STANDARD, ColorContrast.fromName("bogus"))
        assertEquals(0.0, ColorContrast.STANDARD.level, 0.0)
        assertEquals(1.0, ColorContrast.HIGH.level, 0.0)
    }

    @Test
    fun materialSeedFollowsMonetThenKeyColorThenDefault() {
        val red = 0xFFF44336.toInt()
        assertEquals(Color.Unspecified, settings(keyColor = red, dynamic = true).materialSeedColor())
        assertEquals(Color(red), settings(keyColor = red, dynamic = false).materialSeedColor())
        assertEquals(DefaultKeyColor, settings(keyColor = 0, dynamic = false).materialSeedColor())
    }

    private fun settings(keyColor: Int, dynamic: Boolean) = AppSettings(
        colorMode = ColorMode.SYSTEM,
        keyColor = keyColor,
        paletteStyle = PaletteStyle.TonalSpot,
        colorSpec = ColorSpec.SpecVersion.SPEC_2025,
        useDynamicColor = dynamic,
    )
}
