package com.shixiaoshi0417.lunexsu.ui.theme.pack

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperMaterialTest {
    private val eps = 1e-5f

    @Test fun opacityIsThePanelAlphaOneToOne() {
        for (opacity in listOf(0f, 0.25f, 0.5f, 0.6f, 1f)) {
            assertEquals(opacity, wallpaperAlphas(opacity).group, eps)
        }
        assertEquals(0f, wallpaperAlphas(-1f).group, eps)
        assertEquals(1f, wallpaperAlphas(2f).group, eps)
    }

    @Test fun raisedSitsOneStepAboveTheGroupAndCapsAtOpaque() {
        assertEquals(0.12f, wallpaperAlphas(0f).raised, eps)
        assertEquals(0.62f, wallpaperAlphas(0.5f).raised, eps)
        assertEquals(1f, wallpaperAlphas(0.95f).raised, eps)
    }

    @Test fun overlayStaysNearOpaqueForReadableDialogs() {
        for (opacity in listOf(0f, 0.3f, 1f)) assertEquals(0.96f, wallpaperAlphas(opacity).overlay, eps)
    }

    @Test fun chromeKeepsAFloorAndTracksTheOpacity() {
        assertEquals(0.10f, wallpaperAlphas(0f).chrome, eps)
        assertEquals(0.55f, wallpaperAlphas(0.5f).chrome, eps)
        assertEquals(1f, wallpaperAlphas(1f).chrome, eps)
    }

    @Test fun collapsingTopBarStrengthensWhileOtherEdgesDoNot() {
        assertEquals(0.37f, chromePlatformAlpha(0.37f, collapsed = 0f, topEdge = true), eps)
        assertEquals(0.85f, chromePlatformAlpha(0.37f, collapsed = 1f, topEdge = true), eps)
        assertEquals(0.37f + (0.85f - 0.37f) * 0.5f, chromePlatformAlpha(0.37f, collapsed = 0.5f, topEdge = true), eps)
        assertEquals(0.37f, chromePlatformAlpha(0.37f, collapsed = 1f, topEdge = false), eps)
        // A bar already more opaque than the floor is never weakened.
        assertEquals(0.95f, chromePlatformAlpha(0.95f, collapsed = 1f, topEdge = true), eps)
    }

    @Test fun onlyContainerFillsAreTranslucentContentStaysOpaque() {
        val scheme = lightColorScheme(surface = Color(0xFFFAF8FF), surfaceContainerHigh = Color(0xFFE8E7EF))
        val tokens = resolveFolkWallpaperTokens(scheme, 0.4f)
        assertEquals(scheme.surface, tokens.group.tint)
        assertEquals(scheme.surface.copy(alpha = 0.4f), tokens.group.fill)
        assertEquals(scheme.surfaceContainerHigh.copy(alpha = 0.96f), tokens.overlay.fill)
        for (role in WallpaperSurfaceRole.entries) {
            val material = tokens.material(role)
            assertEquals(1f, material.tint.alpha, eps)
            assertEquals(1f, material.contentColor.alpha, eps)
            assertEquals(1f, material.supportingColor.alpha, eps)
        }
        assertEquals(1f, tokens.chrome.contentColor.alpha, eps)
        assertTrue(tokens.chrome.alpha > tokens.group.alpha)
    }

    @Test fun importedBackgroundOpacityDrivesTheMaterials() {
        // FinaSU's backgroundOpacity is WallpaperConfig.opacity, the panel alpha of every material.
        val plan = ThemePackSchema.parse(JSONObject("""{"backgroundOpacity":0.35}"""), emptySet())
        val tokens = resolveFolkWallpaperTokens(lightColorScheme(), plan.background.opacity)
        assertEquals(0.35f, tokens.group.alpha, eps)
        assertEquals(0.47f, tokens.raised.alpha, eps)
        // A pack without the field imports as 0.5, like FinaSU's WallpaperThemeAssets.
        assertEquals(0.5f, ThemePackSchema.parse(JSONObject("{}"), emptySet()).background.opacity, eps)
    }
}
