package com.shixiaoshi0417.lunexsu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeUiModeTest {
    @Test fun themeUsesFinaMaterialWithoutLosingThePreferredUi() {
        UiMode.entries.forEach { preferred ->
            assertEquals(ManagerUiPresentation(preferred, false), managerUiPresentation(preferred, false, true))
            assertEquals(ManagerUiPresentation(UiMode.Material, true), managerUiPresentation(preferred, true, true))
            assertEquals(ManagerUiPresentation(preferred, false), managerUiPresentation(preferred, false, true))
        }
    }

    @Test fun uninstalledCoreAlwaysUsesNativeMiuixEvenWithASavedTheme() {
        UiMode.entries.forEach { preferred ->
            listOf(false, true).forEach { hasThemePack ->
                assertEquals(
                    ManagerUiPresentation(UiMode.Miuix, false),
                    managerUiPresentation(preferred, hasThemePack, coreInstalled = false),
                )
            }
        }
    }

    @Test fun uninstallAndReinstallSuspendAndRestoreTheSameTheme() {
        UiMode.entries.forEach { preferred ->
            val installed = managerUiPresentation(preferred, hasThemePack = true, coreInstalled = true)
            assertEquals(ManagerUiPresentation(UiMode.Material, true), installed)
            assertEquals(
                ManagerUiPresentation(UiMode.Miuix, false),
                managerUiPresentation(preferred, hasThemePack = true, coreInstalled = false),
            )
            assertEquals(installed, managerUiPresentation(preferred, hasThemePack = true, coreInstalled = true))
        }
    }
}
