package com.shixiaoshi0417.lunexsu.ui

import androidx.compose.runtime.staticCompositionLocalOf

enum class UiMode(val value: String) {
    Miuix("miuix"),
    Material("material");

    companion object {
        fun fromValue(value: String): UiMode = when (value) {
            Material.value -> Material
            else -> Miuix
        }

        val DEFAULT_VALUE = Miuix.value
    }
}

val LocalUiMode = staticCompositionLocalOf { UiMode.Miuix }

internal data class ManagerUiPresentation(val uiMode: UiMode, val finaThemeUi: Boolean)

/** Installation must remain visible even if a previously imported theme hides its status card. */
internal fun managerUiPresentation(
    preferred: UiMode,
    hasThemePack: Boolean,
    coreInstalled: Boolean,
): ManagerUiPresentation = when {
    !coreInstalled -> ManagerUiPresentation(UiMode.Miuix, false)
    hasThemePack -> ManagerUiPresentation(UiMode.Material, true)
    else -> ManagerUiPresentation(preferred, false)
}
