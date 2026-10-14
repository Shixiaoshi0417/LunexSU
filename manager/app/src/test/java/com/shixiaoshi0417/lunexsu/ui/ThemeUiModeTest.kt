package com.shixiaoshi0417.lunexsu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeUiModeTest {
    @Test fun themeUsesFinaMaterialWithoutLosingThePreferredUi() {
        UiMode.entries.forEach { preferred ->
            assertEquals(preferred, effectiveUiMode(preferred, false))
            assertEquals(UiMode.Material, effectiveUiMode(preferred, true))
            assertEquals(preferred, effectiveUiMode(preferred, false))
        }
    }
}
