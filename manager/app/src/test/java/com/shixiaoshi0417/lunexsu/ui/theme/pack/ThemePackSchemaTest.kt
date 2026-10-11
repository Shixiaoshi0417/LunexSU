package com.shixiaoshi0417.lunexsu.ui.theme.pack

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThemePackSchemaTest {
    private companion object {
        /** Every preference an import may write; anything else in a pack is ignored. */
        val THEME_PREFERENCES = setOf("key_color", "color_mode", "use_dynamic_color", "miuix_monet",
            "color_generation_mode", "color_style", "color_spec", "color_contrast", "stats_top_layout",
            "home_layout_style", "home_hide_working_mode")
    }
    private fun parse(text: String, vararg names: String) = ThemePackSchema.parse(JSONObject(text), names.toSet())

    @Test fun mapsOnlyThemeFieldsAndReportsUnsupportedConfiguration() {
        val plan = parse("""{"customColor":"blue","useSystemDynamicColor":false,"homeLayoutStyle":"stats",
            "fontMode":"system","allowSu":true,"authorizationMode":0,"ui_mode":"material","intent_token":"evil",
            "auto_jailbreak":true,"check_update":false,"appLanguage":"en-US"}""")
        assertEquals(0xFF0061A4.toInt(), plan.settings["key_color"])
        assertEquals("stats", plan.settings["home_layout_style"])
        assertEquals(THEME_PREFERENCES, plan.settings.keys)
        assertTrue(plan.warnings.containsAll(listOf("allowSu", "authorizationMode", "ui_mode", "intent_token", "auto_jailbreak", "check_update", "appLanguage")))
    }
    @Test fun dynamicColorsRemoveOldCustomSeedAndEnableMiuixMonet() {
        val plan = parse("""{"customColor":"red","folksu_keyColor":123,"useSystemDynamicColor":true}""")
        assertEquals(0, plan.settings["key_color"])
        assertEquals(true, plan.settings["miuix_monet"])
        assertEquals(true, plan.settings["use_dynamic_color"])
    }
    @Test fun mapsStatsHeaderToLunexTokens() {
        assertEquals("status", parse("""{"statsTopLayout":"list"}""").settings["stats_top_layout"])
        assertEquals("grid", parse("""{"statsTopLayout":"grid"}""").settings["stats_top_layout"])
        val unknown = parse("""{"statsTopLayout":"cards"}""")
        assertFalse("stats_top_layout" in unknown.settings)
        assertTrue("statsTopLayout=cards" in unknown.warnings)
    }
    @Test fun preservesExactCustomArgb() {
        val exact = """{"folksu_keyColor":-123456,"useSystemDynamicColor":false}"""
        assertEquals(-123456, parse(exact).settings["key_color"])
        // Without the flag the FinaSU default turns system dynamic colors on, which take precedence.
        assertEquals(0, parse("""{"folksu_keyColor":-123456}""").settings["key_color"])
    }
    @Test fun rejectsFractionalArgb() {
        assertThrows(Exception::class.java) { parse("""{"folksu_keyColor":1.5}""") }
    }
    @Test fun rejectsIncorrectBooleanTypes() {
        assertThrows(Exception::class.java) { parse("""{"isBackgroundEnabled":"true"}""") }
    }
    @Test fun rejectsOutOfRangeImageParameters() {
        listOf("backgroundOpacity", "backgroundDim", "backgroundDayDim", "backgroundNightDim").forEach {
            assertThrows(Exception::class.java) { parse("""{"$it":1.1}""") }
        }
        assertThrows(Exception::class.java) { parse("""{"backgroundBlur":101}""") }
    }
    @Test fun mapsFinaSuDualDimFieldsAndDefaults() {
        val defaults = parse("{}").background
        assertFalse(defaults.dualDim)
        assertEquals(0.2f, defaults.dim)
        assertEquals(0.2f, defaults.dayDim)
        assertEquals(0.4f, defaults.nightDim)
        val dual = parse("""{"isDualBackgroundDimEnabled":true,"backgroundDim":0.3,
            "backgroundDayDim":0.15,"backgroundNightDim":0.65}""").background
        assertTrue(dual.dualDim)
        assertEquals(0.3f, dual.dim)
        assertEquals(0.15f, dual.dayDim)
        assertEquals(0.65f, dual.nightDim)
        assertThrows(Exception::class.java) { parse("""{"isDualBackgroundDimEnabled":"yes"}""") }
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
        assertFalse("home_layout_style" in plan.settings)
        assertFalse("color_style" in plan.settings)
        assertEquals(2, plan.warnings.size)
    }
    @Test fun missingFieldsTakeFinaSuImportDefaults() {
        val settings = parse("{}").settings
        assertEquals(THEME_PREFERENCES, settings.keys)
        assertEquals("classic", settings["color_generation_mode"])
        assertEquals(0, settings["color_mode"]) // follow system
        assertEquals(true, settings["use_dynamic_color"])
        assertEquals(0, settings["key_color"]) // the dynamic default replaces the indigo seed
        assertEquals("SPEC_2021", settings["color_spec"])
        assertEquals("TonalSpot", settings["color_style"])
        assertEquals("STANDARD", settings["color_contrast"])
        assertEquals("circle", settings["home_layout_style"])
        assertEquals("status", settings["stats_top_layout"])
        assertEquals(false, settings["home_hide_working_mode"])
        assertTrue(parse("{}").warnings.isEmpty())
    }
    @Test fun explicitFieldsOverrideTheImportDefaults() {
        val settings = parse("""{"customColor":"sakura","useSystemDynamicColor":false,
            "colorGenerationMode":"custom","colorContrast":"HIGH","nightModeFollowSys":false,
            "nightModeEnabled":false}""").settings
        assertEquals(0xFF9B404F.toInt(), settings["key_color"])
        assertEquals("custom", settings["color_generation_mode"])
        assertEquals("HIGH", settings["color_contrast"])
        assertEquals(1, settings["color_mode"])
    }
    @Test fun unknownGenerationModeIsReportedAndNotApplied() {
        val plan = parse("""{"colorGenerationMode":"neon"}""")
        assertFalse("color_generation_mode" in plan.settings)
        assertTrue("colorGenerationMode=neon" in plan.warnings)
        assertThrows(Exception::class.java) { parse("""{"colorGenerationMode":true}""") }
    }
    @Test fun importDefaultsDoNotMutateTheCallersJson() {
        val json = JSONObject("{}")
        ThemePackSchema.parse(json, emptySet())
        assertEquals(0, json.length())
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
