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

/** A theme changes presentation, not the user's persisted choice. */
internal fun effectiveUiMode(preferred: UiMode, hasThemePack: Boolean): UiMode =
    if (hasThemePack) UiMode.Material else preferred
