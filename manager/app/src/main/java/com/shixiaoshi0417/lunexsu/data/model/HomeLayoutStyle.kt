// Adapted from FolkSU (https://github.com/LyraVoid/FolkSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.data.model

/**
 * Tokens persisted under the `home_layout_style` preference key. The tokens match FolkSU's, so a
 * value carried over from there maps onto the same layout. Unknown tokens fall back to [CIRCLE].
 *
 * The layouts are rendered by the Material UI; the MIUIX home keeps its own layout.
 */
object HomeLayoutStyle {
    /** The standard single-column home. */
    const val CIRCLE = "circle"

    /** The two-column card grid home. */
    const val GRID = "kernelsu"

    /** The responsive tile board that becomes a 2x2 grid on a wide landscape screen. */
    const val FOCUS = "focus"

    /** The hero-first home with a single wide status banner. */
    const val DASHBOARD = "dashboard_ui"

    /** The monitoring home built around live metrics, a module/superuser donut and system facts. */
    const val STATS = "stats"

    /** Layout used when the preference is missing or holds an unsupported token. */
    const val DEFAULT = CIRCLE

    /** Every supported token, in display order. */
    val supported: List<String> = listOf(CIRCLE, GRID, FOCUS, DASHBOARD, STATS)

    /** Normalise a stored value to a supported token. */
    fun fromValue(value: String?): String = if (value in supported) value!! else DEFAULT

    /**
     * Whether a layout draws live device metrics, and therefore has to keep the sampler polled.
     * The others read a single snapshot of the module state and leave the sampler idle.
     */
    fun usesMetrics(value: String): Boolean = value == FOCUS || value == STATS

    /** The two headers the Stats layout can open with. */
    const val STATS_TOP_STATUS = "status"
    const val STATS_TOP_GRID = "grid"

    fun statsTopFromValue(value: String?): String = if (value == STATS_TOP_GRID) STATS_TOP_GRID else STATS_TOP_STATUS
}
