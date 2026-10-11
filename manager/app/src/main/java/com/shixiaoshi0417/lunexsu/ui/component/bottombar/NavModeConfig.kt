// Adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.component.bottombar

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.shixiaoshi0417.lunexsu.ksuApp

/**
 * Material navigation form, aligned with the FolkPatch `nav_mode` preference.
 *
 * [Auto] follows the window size (large windows get the side rail), while the other values force a
 * form. The MIUIX UI keeps its own floating bottom bar setting.
 */
enum class NavMode {
    Auto, Bottom, Rail, Floating;

    val storageValue: String get() = name.lowercase()

    companion object {
        /** A fresh install (no stored value) uses the floating bar, like FinaSU and FolkPatch. */
        val Default = Floating

        fun fromValue(value: String?): NavMode =
            if (value == null) Default else entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: Auto
    }
}

/** Whether this mode shows the side rail; [Auto] does so only in a large window. */
fun NavMode.usesRail(largeWindow: Boolean): Boolean = when (this) {
    NavMode.Rail -> true
    NavMode.Bottom, NavMode.Floating -> false
    NavMode.Auto -> largeWindow
}

/**
 * Persisted navigation mode. [revision] bumps on every write so the navigation chrome
 * recomposes immediately.
 */
object NavModeConfig {

    private const val PREFS = "settings"
    private const val KEY_MODE = "nav_mode"

    private val prefs get() = ksuApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    var mode: NavMode
        get() = NavMode.fromValue(prefs.getString(KEY_MODE, null))
        set(value) {
            prefs.edit { putString(KEY_MODE, value.storageValue) }
            _revision.value++
        }
}
