package me.weishu.kernelsu.ui.theme.pack

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThemePackSchemaTest {
    private fun parse(text: String, vararg names: String) = ThemePackSchema.parse(JSONObject(text), names.toSet())

    @Test fun mapsOnlyThemeFieldsAndReportsUnsupportedConfiguration() {
        val plan = parse("""{"customColor":"blue","homeLayoutStyle":"stats","fontMode":"system",
            "allowSu":true,"authorizationMode":0,"ui_mode":"material","intent_token":"evil",
            "auto_jailbreak":true,"check_update":false,"appLanguage":"en-US"}""")
        assertEquals(0xFF0061A4.toInt(), plan.settings["key_color"])
        assertEquals("stats", plan.settings["home_layout_style"])
        assertEquals(setOf("key_color", "home_layout_style"), plan.settings.keys)
        assertTrue(plan.warnings.containsAll(listOf("allowSu", "authorizationMode", "ui_mode", "intent_token", "auto_jailbreak", "check_update", "appLanguage")))
    }
    @Test fun dynamicColorsRemoveOldCustomSeedAndEnableMiuixMonet() {
        val plan = parse("""{"customColor":"red","folksu_keyColor":123,"useSystemDynamicColor":true}""")
        assertEquals(0, plan.settings["key_color"])
        assertEquals(true, plan.settings["miuix_monet"])
        assertEquals(true, plan.settings["use_dynamic_color"])
    }
    @Test fun preservesExactCustomArgb() {
        assertEquals(-123456, parse("""{"folksu_keyColor":-123456}""").settings["key_color"])
    }
    @Test fun rejectsFractionalArgb() {
        assertThrows(Exception::class.java) { parse("""{"folksu_keyColor":1.5}""") }
    }
    @Test fun rejectsIncorrectBooleanTypes() {
        assertThrows(Exception::class.java) { parse("""{"isBackgroundEnabled":"true"}""") }
    }
    @Test fun rejectsOutOfRangeImageParameters() {
        listOf("backgroundOpacity", "backgroundDim", "backgroundNightDim").forEach {
            assertThrows(Exception::class.java) { parse("""{"$it":1.1}""") }
        }
        assertThrows(Exception::class.java) { parse("""{"backgroundBlur":101}""") }
    }
    @Test fun requiresWallpaperForTheSelectedMode() {
        assertThrows(Exception::class.java) { parse("""{"isBackgroundEnabled":true}""") }
        assertThrows(Exception::class.java) {
            parse("""{"isBackgroundEnabled":true,"isMultiBackgroundEnabled":true}""", "background.png")
        }
    }
    @Test fun mapsPerPageImagesAndLegacySuperuserSlot() {
        val plan = parse("""{"isBackgroundEnabled":true,"isMultiBackgroundEnabled":true}""",
            "background_home.webp", "background_kernel.png", "background_system_module.jpg", "background_settings.gif")
        assertEquals("background_home.webp", plan.background.image(0))
        assertEquals("background_kernel.png", plan.background.image(1))
        assertEquals("background_system_module.jpg", plan.background.image(2))
        assertEquals("background_settings.gif", plan.background.image(3))
    }
    @Test fun globalBackgroundIsUsedForEveryPage() {
        val plan = parse("""{"isBackgroundEnabled":true}""", "background.png")
        assertEquals("background.png", plan.background.image(3))
    }
    @Test fun rejectsAmbiguousOrWrongFormatResources() {
        assertThrows(Exception::class.java) { parse("{}", "background.png", "background.webp") }
        assertThrows(Exception::class.java) { parse("{}", "background.sh") }
        assertThrows(Exception::class.java) { parse("{}", "font.exe") }
    }
    @Test fun explicitCustomFontRequiresItsPayload() {
        assertThrows(Exception::class.java) { parse("""{"fontMode":"custom"}""") }
    }
    @Test fun legacyMissingFontFallsBackExplicitly() {
        val plan = parse("""{"isFontEnabled":true}""")
        assertEquals("system", plan.fontMode)
        assertTrue(plan.warnings.isNotEmpty())
    }
    @Test fun unknownLayoutAndPaletteRetainExistingSetting() {
        val plan = parse("""{"homeLayoutStyle":"future","colorStyle":"UNKNOWN"}""")
        assertTrue(plan.settings.isEmpty())
        assertEquals(2, plan.warnings.size)
    }
    @Test fun legacyLayoutsMapToCircleWithWarning() {
        val plan = parse("""{"homeLayoutStyle":"sign"}""")
        assertEquals("circle", plan.settings["home_layout_style"])
        assertTrue(plan.warnings.isNotEmpty())
    }
    @Test fun rejectsUnboundedMetadata() {
        assertThrows(Exception::class.java) {
            ThemePackSchema.parse(JSONObject().put("meta_description", "x".repeat(4097)), emptySet())
        }
    }
    @Test fun optionalUnsupportedAssetsAreListedAndNeverActivated() {
        val plan = parse("""{"isVideoBackgroundEnabled":true,"isMusicEnabled":true}""", "video_background.mp4", "music.mp3")
        assertFalse(plan.background.enabled)
        assertTrue(plan.warnings.containsAll(listOf("isVideoBackgroundEnabled", "isMusicEnabled", "video_background.mp4", "music.mp3")))
    }
}
