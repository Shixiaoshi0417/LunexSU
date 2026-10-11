// Adapted from FolkSU ThemeSettingsMapping/ThemeValidation, GPL-3.0.
// https://github.com/LyraVoid/FolkSU/tree/fa57d340
package me.weishu.kernelsu.ui.theme.pack

import com.materialkolor.PaletteStyle
import me.weishu.kernelsu.data.model.HomeLayoutStyle
import org.json.JSONObject

internal data class PackMetadata(val name: String, val author: String, val version: String, val description: String)

internal data class PackBackground(
    val enabled: Boolean,
    val multiple: Boolean,
    val opacity: Float,
    val blur: Float,
    val dim: Float,
    val dualDim: Boolean,
    val dayDim: Float,
    val nightDim: Float,
    val images: Map<Int, String>,
) {
    fun image(page: Int): String? = if (!enabled) null else images[if (multiple) page else -1]
}

internal data class ThemePackPlan(
    val metadata: PackMetadata,
    val settings: Map<String, Any>,
    val fontMode: String?,
    val font: String?,
    val background: PackBackground,
    val warnings: List<String>,
)

/** FPT fields are translated explicitly; arbitrary JSON keys never become preferences. */
internal object ThemePackSchema {
    private val colors = linkedMapOf(
        "indigo" to 0xFF4355B9, "blue" to 0xFF0061A4, "light_blue" to 0xFF006493,
        "cyan" to 0xFF006876, "teal" to 0xFF006A60, "green" to 0xFF006E1A,
        "light_green" to 0xFF006C48, "lime" to 0xFF5B6300, "yellow" to 0xFF695F00,
        "amber" to 0xFF785900, "orange" to 0xFF8B5000, "deep_orange" to 0xFFB02F00,
        "red" to 0xFFBB1614, "pink" to 0xFFBC004B, "purple" to 0xFF9A25AE,
        "deep_purple" to 0xFF6F43C0, "brown" to 0xFF9A4522, "blue_grey" to 0xFF00668A,
        "sakura" to 0xFF9B404F, "ink_wash" to 0xFF424242,
    ).mapValues { it.value.toInt() }
    private val styles = PaletteStyle.entries.associateBy {
        it.name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
    }
    val imageStems = setOf("background", "background_home", "background_superuser",
        "background_kernel", "background_system_module", "background_settings")
    val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif")

    fun parse(json: JSONObject, names: Set<String>): ThemePackPlan {
        val used = mutableSetOf<String>()
        val warnings = mutableListOf<String>()
        val settings = linkedMapOf<String, Any>()
        fun text(key: String, fallback: String = ""): String {
            used += key
            if (!json.has(key)) return fallback
            val value = json.get(key)
            require(value is String && value.length <= 4096) { "$key must be bounded text" }
            return value
        }
        fun boolean(key: String, fallback: Boolean = false): Boolean {
            used += key
            if (!json.has(key)) return fallback
            val value = json.get(key)
            require(value is Boolean) { "$key must be boolean" }
            return value
        }
        fun scalar(key: String, fallback: Float, max: Double = 1.0): Float {
            used += key
            if (!json.has(key)) return fallback
            val value = json.get(key)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..max) {
                "$key is outside its valid range"
            }
            return value.toFloat()
        }
        val metadata = PackMetadata(text("meta_name"), text("meta_author"),
            if (json.opt("meta_version") is Number) {
                used += "meta_version"
                json.get("meta_version").toString()
            } else text("meta_version", "1"), text("meta_description"))
        text("meta_type", "phone")

        if (json.has("customColor")) {
            colors[text("customColor")]?.let { settings["key_color"] = it }
                ?: warnings.add("customColor=${json.getString("customColor")}")
        }
        if (json.has("folksu_keyColor")) {
            used += "folksu_keyColor"
            val value = json.get("folksu_keyColor")
            require(value is Number && value.toDouble() in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() &&
                value.toDouble() == value.toInt().toDouble()) { "Invalid folksu_keyColor" }
            settings["key_color"] = value.toInt()
        }
        if (json.has("nightModeEnabled") || json.has("nightModeFollowSys")) {
            val dark = boolean("nightModeEnabled")
            settings["color_mode"] = if (boolean("nightModeFollowSys")) 0 else if (dark) 2 else 1
        }
        if (json.has("useSystemDynamicColor")) {
            val dynamic = boolean("useSystemDynamicColor")
            settings["use_dynamic_color"] = dynamic
            settings["miuix_monet"] = dynamic
            if (dynamic) settings["key_color"] = 0
        }
        if (json.has("colorStyle")) {
            styles[text("colorStyle")]?.let { settings["color_style"] = it.name }
                ?: warnings.add("colorStyle=${json.getString("colorStyle")}")
        }
        if (json.has("colorStandard")) {
            when (val standard = text("colorStandard")) {
                "M3E_2025" -> settings["color_spec"] = "SPEC_2025"
                "MD3_2021" -> settings["color_spec"] = "SPEC_2021"
                else -> warnings += "colorStandard=$standard"
            }
        }
        if (json.has("colorContrast")) {
            val contrast = text("colorContrast")
            if (contrast in setOf("STANDARD", "MEDIUM", "HIGH")) settings["color_contrast"] = contrast
            else warnings += "colorContrast=$contrast"
        }
        if (json.has("statsTopLayout")) {
            val layout = text("statsTopLayout")
            when (layout) {
                "list" -> settings["stats_top_layout"] = HomeLayoutStyle.STATS_TOP_STATUS
                "grid" -> settings["stats_top_layout"] = HomeLayoutStyle.STATS_TOP_GRID
                else -> warnings += "statsTopLayout=$layout"
            }
        }
        if (json.has("homeLayoutStyle")) {
            val layout = text("homeLayoutStyle")
            when (layout) {
                "circle", "kernelsu", "focus", "dashboard_ui", "stats" -> settings["home_layout_style"] = layout
                "default", "sign" -> { settings["home_layout_style"] = "circle"; warnings += "homeLayoutStyle=$layout -> circle" }
                else -> warnings += "homeLayoutStyle=$layout"
            }
        }
        if (json.has("isListWorkingCardModeHidden")) {
            settings["home_hide_working_mode"] = boolean("isListWorkingCardModeHidden")
        }

        val groups = names.groupBy { it.substringBefore('.') }
        (imageStems + "font").forEach { stem ->
            require((groups[stem]?.size ?: 0) <= 1) { "Ambiguous resource: $stem" }
            groups[stem]?.firstOrNull()?.let { name ->
                val extension = name.substringAfterLast('.').lowercase()
                require(extension in if (stem == "font") setOf("ttf", "otf") else imageExtensions) {
                    "Invalid resource format: $name"
                }
            }
        }
        val fontFile = groups["font"]?.singleOrNull()
        val fontEnabled = boolean("isFontEnabled")
        var fontMode = if (json.has("fontMode")) text("fontMode") else
            if (json.has("isFontEnabled")) { if (fontEnabled) "custom" else "system" } else null
        if (fontMode == "custom" && fontFile == null) {
            require(!json.has("fontMode")) { "Custom font is missing" }
            warnings += "isFontEnabled: font missing -> system"
            fontMode = "system"
        }
        if (fontMode == "app") { warnings += "fontMode=app -> system"; fontMode = "system" }
        if (fontMode != null && fontMode !in setOf("system", "custom")) {
            warnings += "fontMode=$fontMode"
            fontMode = null
        }

        val enabled = boolean("isBackgroundEnabled")
        val multiple = boolean("isMultiBackgroundEnabled")
        val images = linkedMapOf<Int, String>()
        listOf(-1 to "background", 0 to "background_home", 1 to "background_superuser",
            2 to "background_system_module", 3 to "background_settings").forEach { (page, stem) ->
            groups[stem]?.singleOrNull()?.let { images[page] = it }
        }
        if (images[1] == null) groups["background_kernel"]?.singleOrNull()?.let { images[1] = it }
        require(!enabled || if (multiple) images.keys.any { it >= 0 } else images.containsKey(-1)) {
            "Enabled wallpaper requires a background for its selected mode"
        }
        val background = PackBackground(enabled, multiple, scalar("backgroundOpacity", 0.6f),
            scalar("backgroundBlur", 0f, 100.0), scalar("backgroundDim", 0.2f),
            boolean("isDualBackgroundDimEnabled"), scalar("backgroundDayDim", 0.2f),
            scalar("backgroundNightDim", 0.4f), images)
        json.keys().asSequence().filter { it !in used }.forEach { warnings += it }
        names.filter { it !in images.values && !(fontMode == "custom" && it == fontFile) }
            .forEach { warnings += it }
        return ThemePackPlan(metadata, settings, fontMode, fontFile, background, warnings.distinct())
    }
}
