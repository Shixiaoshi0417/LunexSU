package com.shixiaoshi0417.lunexsu.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MiuixWallpaperColorsTest {
    private val eps = 1e-5f
    private val base = lightColorScheme()
    private val colors = miuixWallpaperColors(base, opacity = 0.4f, textStyles = defaultTextStyles())

    @Test fun pageCanvasIsTransparentAndCardsUseTheGroupAlpha() {
        assertEquals(Color.Transparent, colors.page.surface)
        assertEquals(base.surfaceContainer.copy(alpha = 0.4f), colors.page.surfaceContainer)
    }

    @Test fun dialogsAndPopupsInTheRootHostStayReadable() {
        // MIUIX dialogs and bottom sheets draw `background`; list popups draw `surfaceContainer`.
        assertEquals(base.background, colors.host.background)
        assertEquals(1f, colors.host.background.alpha, eps)
        // sRGB colors store an 8-bit alpha, so compare within one quantization step.
        assertEquals(0.96f, colors.host.surfaceContainer.alpha, 1f / 255f)
        assertEquals(base.background, colors.page.background) // window dialogs opened from a page
    }

    @Test fun barsUseTheChromeMaterialAndContentStaysOpaque() {
        assertEquals(base.surface.copy(alpha = 0.10f + 0.9f * 0.4f), colors.chrome)
        assertEquals(base.onSurface, colors.page.onSurface)
        assertEquals(1f, colors.page.onSurface.alpha, eps)
        assertEquals(1f, colors.page.onSurfaceContainer.alpha, eps)
    }
}
