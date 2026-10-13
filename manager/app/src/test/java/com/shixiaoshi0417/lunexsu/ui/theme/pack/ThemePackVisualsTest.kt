package com.shixiaoshi0417.lunexsu.ui.theme.pack

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThemePackVisualsTest {
    private fun parse(json: String, vararg names: String) = ThemePackSchema.parse(JSONObject(json), names.toSet())

    @Test fun legacyGridGifAndHideFlagsAreRealAssets() {
        val plan = parse("""{"homeLayoutStyle":"kernelsu","isGridWorkingCardBackgroundEnabled":true,
            "gridWorkingCardBackgroundOpacity":0.8,"isGridDualOpacityEnabled":true,
            "gridWorkingCardBackgroundDayOpacity":0.7,"gridWorkingCardBackgroundNightOpacity":0.3,
            "gridWorkingCardBackgroundDim":0.2,"isGridWorkingCardCheckHidden":true,
            "isGridWorkingCardModeHidden":true,"isGridWorkingCardTextHidden":true}""", "grid_working_card_background.gif")
        val surface = plan.visuals.surfaces.getValue(PackSurfaceId.GRID)
        assertTrue(surface.enabled && surface.hideIcon && surface.hideText && surface.hideMode)
        assertEquals(0.7f, surface.opacity(false)); assertEquals(0.3f, surface.opacity(true))
        assertEquals(setOf("grid_working_card_background.gif"), plan.imageAssets)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test fun modernSurfaceFieldsOverrideLegacyAndInheritFocusGroup() {
        val plan = parse("""{"isFocusCardBackgroundEnabled":true,"hasFocusCardAppBg":true,
            "focusCardBgOpacity":0.8,"isFocusCardDualDimEnabled":true,"focusCardBgNightDim":0.6,
            "surface.layout.focus.card.app.opacity":0.4,"surface.layout.focus.card.app.day_dim":0.1}""", "focus_card_app_bg.webp")
        val surface = plan.visuals.surfaces.getValue(PackSurfaceId.focus("app"))
        assertTrue(surface.enabled)
        assertEquals(0.4f, surface.opacity); assertEquals(0.1f, surface.dim(false)); assertEquals(0.6f, surface.dim(true))
        assertTrue(plan.warnings.isEmpty())
    }

    @Test fun fullyHiddenGridStillRetainsItsPhotoAtEveryOpacity() {
        for (opacity in listOf(0f, 0.5f, 1f)) {
            val plan = ThemePackSchema.parse(JSONObject()
                .put("isGridWorkingCardBackgroundEnabled", true)
                .put("gridWorkingCardBackgroundOpacity", opacity.toDouble())
                .put("gridWorkingCardBackgroundDim", 0)
                .put("isGridWorkingCardCheckHidden", true)
                .put("isGridWorkingCardModeHidden", true)
                .put("isGridWorkingCardTextHidden", true), setOf("grid_working_card_background.png"))
            val surface = plan.visuals.surfaces.getValue(PackSurfaceId.GRID)
            assertTrue(surface.enabled && surface.hideIcon && surface.hideText && surface.hideMode)
            assertEquals(opacity, surface.opacity(false))
            assertEquals(setOf("grid_working_card_background.png"), plan.imageAssets)
            assertTrue(plan.warnings.isEmpty())
        }
    }

    @Test fun dashboardPresenceAndEnableAreIndependent() {
        val plan = parse("""{"hasDashboardCardBg":true,"isDashboardCardBackgroundEnabled":true,
            "dashboardCardBgOpacity":0.8,"isDashboardCardDualOpacityEnabled":true,
            "dashboardCardBgNightOpacity":0.2,"isDashboardCardDualDimEnabled":true,"dashboardCardBgNightDim":0.9}""", "dashboard_card_bg.png")
        val surface = plan.visuals.surfaces.getValue(PackSurfaceId.DASHBOARD)
        assertEquals(0.2f, surface.opacity(true)); assertEquals(0.9f, surface.dim(true))
        assertTrue(plan.warnings.isEmpty())
        val absent = parse("""{"hasDashboardCardBg":false,"isDashboardCardBackgroundEnabled":true}""", "dashboard_card_bg.png")
        assertNull(absent.visuals.surfaces.getValue(PackSurfaceId.DASHBOARD).image)
        assertTrue(absent.warnings.isNotEmpty())
    }

    @Test fun modernDashboardEnableOverridesLegacyAbsence() {
        val plan = parse("""{"hasDashboardCardBg":false,"surface.layout.dashboard.hero.enabled":true}""",
            "dashboard_card_bg.webp")
        val surface = plan.visuals.surfaces.getValue(PackSurfaceId.DASHBOARD)
        assertTrue(surface.enabled)
        assertEquals("dashboard_card_bg.webp", surface.image)
        assertTrue(plan.warnings.isEmpty())
    }

    @Test fun titleAndNavigationAssetsAreValidatedAndSelected() {
        val plan = parse("""{"isAdvancedTitleStyleEnabled":true,"titleImageOffsetX":-0.5,
            "titleImageNightOpacity":0.3,"navIconCustomEnabled":true,
            "navIcons":{"Home":"nav_icon_Home.png","Settings":"settings.gif"}}""",
            "title_image.png", "nav_icon_Home.png", "settings.gif")
        assertEquals(-0.5f, plan.visuals.title.offsetX)
        assertEquals("settings.gif", plan.visuals.navIcons[3])
        assertEquals(3, plan.imageAssets.size)
        assertTrue(plan.warnings.isEmpty())
        assertThrows(Exception::class.java) { parse("""{"navIcons":{"Home":"../evil.png"}}""") }
        assertThrows(Exception::class.java) { parse("""{"navIconCustomEnabled":true,"navIcons":{"Home":"x.sh"}}""", "x.sh") }
    }

    @Test fun modernFocusDisableOverridesLegacyPresenceWhenInferringGroup() {
        val plan = parse("""{"hasFocusCardAppBg":true,"surface.layout.focus.card.app.enabled":false}""",
            "focus_card_app_bg.png")
        assertFalse(plan.visuals.surfaces.getValue(PackSurfaceId.focus("app")).enabled)
    }

    @Test fun omittedLanguageKeepsUserLocaleButExplicitEmptyResetsIt() {
        assertFalse(parse("{}").settings.containsKey(ThemePackLanguage.KEY))
        assertEquals("", parse("""{"appLanguage":""}""").settings[ThemePackLanguage.KEY])
    }

    @Test fun newFieldsRetainStrictTypeAndRangeValidation() {
        for (json in listOf("""{"isGridWorkingCardCheckHidden":"yes"}""",
            """{"focusCardBgDayDim":1.1}""", """{"surface.layout.dashboard.hero.opacity":-1}""",
            """{"titleImageOffsetX":2}""", """{"videoVolume":2}""", """{"appLanguage":"en_US"}""")) {
            assertThrows(Exception::class.java) { parse(json) }
        }
        assertThrows(Exception::class.java) { parse("{}", "grid_working_card_background.png", "grid_working_card_background.gif") }
    }

    @Test fun disabledMediaDefaultsDoNotGenerateFalseUnsupportedWarnings() {
        val plan = parse("""{"isMusicEnabled":false,"musicVolume":1,"isAutoPlayEnabled":false,
            "isLoopingEnabled":false,"isVideoBackgroundEnabled":false,"videoVolume":0,
            "isSoundEffectEnabled":false,"soundEffectScope":"global","appLanguage":""}""")
        assertTrue(plan.media.assets.isEmpty())
        assertTrue(plan.warnings.isEmpty())
        assertTrue(parse("""{"isSoundEffectEnabled":true}""").warnings.any { it.contains("sound effects") })
    }

    @Test fun musicCannotReadFilesOutsideArchiveOrActivateWithoutConsentFields() {
        val plan = parse("""{"isMusicEnabled":true,"musicFilename":"song.mp3","musicVolume":0.4,
            "isAutoPlayEnabled":true,"isLoopingEnabled":true}""", "song.mp3")
        assertEquals("song.mp3", plan.media.music); assertEquals(0.4f, plan.media.musicVolume)
        assertTrue(plan.media.autoPlay && plan.media.looping)
        assertThrows(Exception::class.java) { parse("""{"isMusicEnabled":true,"musicFilename":"/sdcard/song.mp3"}""") }
        assertTrue(parse("{}", "song.mp3").media.assets.isEmpty())
        assertTrue(parse("{}", "song.mp3").warnings.contains("song.mp3"))
    }
}
