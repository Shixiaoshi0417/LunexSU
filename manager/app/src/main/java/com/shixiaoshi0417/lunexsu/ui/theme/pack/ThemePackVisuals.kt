// FinaSU surface aliases and defaults, adapted under GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.theme.pack

import org.json.JSONObject

internal object PackSurfaceId {
    const val GRID = "layout.grid.workCard"
    const val DASHBOARD = "layout.dashboard.hero"
    const val FOCUS = "layout.focus"
    fun focus(kind: String) = "$FOCUS.card.$kind"
}

internal data class PackSurface(
    val enabled: Boolean = false,
    val image: String? = null,
    val opacity: Float = 1f,
    val dualOpacity: Boolean = false,
    val dayOpacity: Float = opacity,
    val nightOpacity: Float = opacity,
    val dim: Float = 0.3f,
    val dualDim: Boolean = false,
    val dayDim: Float = dim,
    val nightDim: Float = dim,
    val hideIcon: Boolean = false,
    val hideText: Boolean = false,
    val hideMode: Boolean = false,
) {
    fun opacity(dark: Boolean) = if (dualOpacity) { if (dark) nightOpacity else dayOpacity } else opacity
    fun dim(dark: Boolean) = if (dualDim) { if (dark) nightDim else dayDim } else dim
}

internal data class PackTitle(val image: String?, val dayOpacity: Float, val nightOpacity: Float,
    val dim: Float, val offsetX: Float)

internal data class PackVisuals(val surfaces: Map<String, PackSurface>, val title: PackTitle,
    val navIcons: Map<Int, String>) {
    val images: Set<String> get() = (surfaces.values.mapNotNull { it.image } +
        listOfNotNull(title.image) + navIcons.values).toSet()
}

internal class PackFieldReader(val json: JSONObject, private val used: MutableSet<String>) {
    fun bool(key: String, default: Boolean = false): Boolean {
        used += key
        if (!json.has(key)) return default
        return (json.get(key) as? Boolean) ?: error("$key must be boolean")
    }
    fun number(key: String, default: Float, min: Double = 0.0, max: Double = 1.0): Float {
        used += key
        if (!json.has(key)) return default
        val value = json.get(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() in min..max) {
            "$key is outside its valid range"
        }
        return value.toFloat()
    }
    fun text(key: String, default: String = ""): String {
        used += key
        if (!json.has(key)) return default
        val value = json.get(key)
        require(value is String && value.length <= 4096) { "$key must be bounded text" }
        return value
    }
    fun consume(key: String) { used += key }
}

internal object ThemePackVisualSchema {
    val imageStems = setOf("grid_working_card_background", "dashboard_card_bg", "title_image") +
        listOf("kernel", "app", "device", "storage").map { "focus_card_${it}_bg" }

    fun parse(r: PackFieldReader, names: Set<String>, warnings: MutableList<String>): PackVisuals {
        fun image(stem: String): String? = names.singleOrNull { it.substringBefore('.') == stem }
        fun surface(id: String, prefix: String?, enable: String?, dualPrefix: String?,
            stem: String?, default: PackSurface = PackSurface(), fallbackEnabled: Boolean = false): PackSurface {
            fun b(field: String, alias: String?, fallback: Boolean): Boolean {
                val legacy = alias?.let { r.bool(it, fallback) } ?: fallback
                return r.bool("surface.$id.$field", legacy)
            }
            fun n(field: String, alias: String?, fallback: Float): Float {
                val legacy = alias?.let { r.number(it, fallback) } ?: fallback
                return r.number("surface.$id.$field", legacy)
            }
            fun alias(suffix: String) = prefix?.let { "$it$suffix" }
            fun dual(suffix: String) = dualPrefix?.let { "$it${suffix}Enabled" }
            val opacity = n("opacity", alias("Opacity"), default.opacity)
            val dim = n("dim", alias("Dim"), default.dim)
            val enabled = b("enabled", enable, fallbackEnabled)
            return default.copy(enabled = enabled, image = stem?.let(::image), opacity = opacity,
                dualOpacity = b("dual_opacity", dual("Opacity"), default.dualOpacity),
                dayOpacity = n("day_opacity", alias("DayOpacity"), if (prefix == null) default.dayOpacity else opacity),
                nightOpacity = n("night_opacity", alias("NightOpacity"), if (prefix == null) default.nightOpacity else opacity), dim = dim,
                dualDim = b("dual_dim", dual("Dim"), default.dualDim),
                dayDim = n("day_dim", alias("DayDim"), if (prefix == null) default.dayDim else dim),
                nightDim = n("night_dim", alias("NightDim"), if (prefix == null) default.nightDim else dim))
        }
        val grid = surface(PackSurfaceId.GRID, "gridWorkingCardBackground",
            "isGridWorkingCardBackgroundEnabled", "isGridDual", "grid_working_card_background")
        fun flag(field: String, legacy: String) = r.bool("surface.${PackSurfaceId.GRID}.flag.$field", r.bool(legacy))
        val gridWithFlags = grid.copy(hideIcon = flag("hide_icon", "isGridWorkingCardCheckHidden"),
            hideText = flag("hide_text", "isGridWorkingCardTextHidden"),
            hideMode = flag("hide_mode", "isGridWorkingCardModeHidden"))
        val dashboard = surface(PackSurfaceId.DASHBOARD, "dashboardCardBg",
            "isDashboardCardBackgroundEnabled", "isDashboardCardDual", "dashboard_card_bg")
        val legacyDashboardPresent = r.bool("hasDashboardCardBg", dashboard.image != null)
        val dashboardPresent = if (r.json.has("surface.${PackSurfaceId.DASHBOARD}.enabled"))
            dashboard.enabled else legacyDashboardPresent
        val kinds = listOf("kernel", "app", "device", "storage")
        val inferredFocus = kinds.any { kind ->
            val modern = "surface.${PackSurfaceId.focus(kind)}.enabled"
            if (r.json.has(modern)) r.json.optBoolean(modern)
            else r.json.optBoolean("hasFocusCard${kind.replaceFirstChar { it.uppercase() }}Bg")
        }
        val focus = surface(PackSurfaceId.FOCUS, "focusCardBg", "isFocusCardBackgroundEnabled",
            "isFocusCardDual", null, fallbackEnabled = inferredFocus)
        val surfaces = linkedMapOf(PackSurfaceId.GRID to gridWithFlags,
            PackSurfaceId.DASHBOARD to dashboard.copy(image = dashboard.image.takeIf { dashboardPresent }))
        kinds.forEach { kind ->
            val id = PackSurfaceId.focus(kind)
            val stem = "focus_card_${kind}_bg"
            val presence = r.bool("hasFocusCard${kind.replaceFirstChar { it.uppercase() }}Bg", image(stem) != null)
            // Children inherit all group fields, but modern child fields can override each one.
            val child = surface(id, null, null, null,
                stem, focus, presence)
            surfaces[id] = child.copy(enabled = focus.enabled && child.enabled)
        }
        surfaces.forEach { (id, config) ->
            if (config.enabled && config.image == null) warnings += "$id: image missing"
        }
        val titleEnabled = r.bool("isAdvancedTitleStyleEnabled")
        val title = PackTitle(image("title_image").takeIf { titleEnabled },
            r.number("titleImageDayOpacity", 1f), r.number("titleImageNightOpacity", 1f),
            r.number("titleImageDim", 0f), r.number("titleImageOffsetX", 0f, -1.0))
        if (titleEnabled && title.image == null) warnings += "title_image: image missing"
        val icons = linkedMapOf<Int, String>()
        val modernNav = r.bool("navIconCustomEnabled")
        val legacyNav = r.bool("nav_icon_custom_enabled")
        val navEnabled = modernNav || legacyNav
        r.consume("navIcons")
        val nav = if (r.json.has("navIcons")) {
            requireNotNull(r.json.optJSONObject("navIcons")) { "navIcons must be an object" }
        } else null
        listOf("Home", "SuperUser", "AModule", "Settings").forEachIndexed { index, slot ->
            if (nav?.has(slot) == true) {
                val name = nav.get(slot)
                require(name is String && name.length <= 255 && '/' !in name && '\\' !in name) { "Invalid nav icon" }
                if (navEnabled) {
                    require(name in names && name.substringAfterLast('.').lowercase() in ThemePackSchema.imageExtensions) {
                        "Missing or invalid nav icon: $slot"
                    }
                    icons[index] = name
                }
            }
        }
        nav?.keys()?.asSequence()?.filter { it !in setOf("Home", "SuperUser", "AModule", "Settings") }
            ?.forEach { warnings += "navIcons.$it" }
        return PackVisuals(surfaces, title, icons)
    }
}
