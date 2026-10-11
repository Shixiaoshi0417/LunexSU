package me.weishu.kernelsu

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import me.weishu.kernelsu.ui.theme.FontConfig
import me.weishu.kernelsu.ui.theme.FontMode
import me.weishu.kernelsu.ui.theme.pack.FptContainer
import me.weishu.kernelsu.ui.theme.pack.ThemePackImporter
import me.weishu.kernelsu.ui.theme.pack.ThemePackState
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Android parser/filesystem/preferences integration, in a separate private storage namespace. */
internal object ThemePackDeviceTests {
    private class TestContext(base: Context, val root: File) : ContextWrapper(base) {
        val prefix = "fpt-test-${UUID.randomUUID()}-"
        var failNextCommit = false
        override fun getFilesDir() = File(root, "files").also { it.mkdirs() }
        override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val backing = super.getSharedPreferences(prefix + name, mode)
            return object : SharedPreferences by backing {
                override fun edit(): SharedPreferences.Editor {
                    val editor = backing.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit(): Boolean {
                            val persisted = editor.commit()
                            if (failNextCommit) { failNextCommit = false; return false }
                            return persisted
                        }
                    }
                }
            }
        }
    }

    private fun fixture(context: Context, withFont: Boolean = true): File {
        val resources = linkedMapOf<String, File>()
        listOf("background_home" to Color.rgb(124, 93, 184),
            "background_superuser" to Color.rgb(52, 130, 154),
            "background_system_module" to Color.rgb(97, 152, 91),
            "background_settings" to Color.rgb(165, 111, 153)).forEach { (name, color) ->
            val file = File(context.cacheDir, "$name.png")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            resources[file.name] = file
        }
        if (withFont) {
            val font = File("/system/fonts").listFiles()?.firstOrNull { it.name.startsWith("Roboto") && it.extension == "ttf" }
                ?: File("/system/fonts").listFiles()?.firstOrNull { it.extension == "ttf" }
                ?: error("System font fixture missing")
            resources["font.ttf"] = font
        }
        val json = JSONObject().put("meta_name", "LunexSU FPT test").put("meta_author", "LunexSU tests")
            .put("meta_version", "1").put("meta_description", "FolkSU-compatible local import fixture")
            .put("customColor", "blue").put("useSystemDynamicColor", false).put("homeLayoutStyle", "stats")
            .put("nightModeFollowSys", false).put("nightModeEnabled", false)
            .put("isBackgroundEnabled", true).put("isMultiBackgroundEnabled", true)
            .put("backgroundOpacity", 0.65).put("backgroundDim", 0.15)
            .put("ui_mode", "evil").put("intent_token", "evil").put("auto_jailbreak", true)
            .put("allowSu", true).put("authorizationMode", 0)
        if (withFont) json.put("fontMode", "custom")
        return File(context.cacheDir, "fixture-${UUID.randomUUID()}.fpt").also {
            it.outputStream().use { output -> FptContainer.write(json, resources, output) }
        }
    }

    fun createUiFixture(context: Context, uiMode: String): Bundle {
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(uiMode in setOf("material", "miuix"))
        check(context.getSharedPreferences("settings", 0).edit().putString("ui_mode", uiMode).commit())
        return Bundle().apply { putString("fixture", fixture(context).path) }
    }

    fun run(instrumentation: Instrumentation): Bundle = runBlocking {
        val base = instrumentation.targetContext
        check(base.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val root = File(base.cacheDir, "theme-tests-${UUID.randomUUID()}").also { it.mkdirs() }
        val context = TestContext(base, root)
        val prefs = context.getSharedPreferences("settings", 0)
        val passed = mutableListOf<String>()
        try {
            check(prefs.edit().putString("ui_mode", "miuix").putString("intent_token", "original")
                .putBoolean("auto_jailbreak", false).commit())
            val source = fixture(context)
            val before = prefs.all.toMap()
            ThemePackImporter.prepare(context, Uri.fromFile(source)).use {
                check(it.plan.warnings.containsAll(listOf("allowSu", "authorizationMode", "ui_mode")))
            }
            check(prefs.all == before)
            passed += "cancel_prepared_import"

            ThemePackImporter.prepare(context, Uri.fromFile(source)).use {
                ThemePackImporter.apply(context, it).getOrThrow()
            }
            check(prefs.getInt("key_color", 0) == 0xFF0061A4.toInt())
            check(prefs.getString("home_layout_style", null) == "stats")
            check(prefs.getString("ui_mode", null) == "miuix" && prefs.getString("intent_token", null) == "original")
            check(!prefs.getBoolean("auto_jailbreak", true) && !prefs.contains("allowSu"))
            passed += "apply_palette_layout_and_settings_isolation"
            check(FontConfig.fontMode == FontMode.CUSTOM)
            val font = File(context.filesDir, requireNotNull(FontConfig.customFontFilename))
            check(font.isFile && FontConfig.getFontFamily(context) != androidx.compose.ui.text.font.FontFamily.Default)
            passed += "android_font_decode_and_live_reload"
            check(ThemePackState.active?.plan?.background?.image(3) == "background_settings.png")
            passed += "private_per_page_assets"
            instrumentation.runOnMainSync { ThemePackState.reload(context) }
            check(ThemePackState.hasBackground)
            passed += "persisted_selection_reload"

            val current = prefs.all.toMap()
            val broken = File(context.cacheDir, "broken.fpt").also {
                val bytes = source.readBytes(); it.writeBytes(bytes.copyOf(bytes.size - 1))
            }
            check(runCatching { ThemePackImporter.prepare(context, Uri.fromFile(broken)) }.isFailure)
            check(prefs.all == current)
            passed += "truncated_container_keeps_current_theme"

            fun invalidAsset(name: String, json: JSONObject): File {
                val data = File(context.cacheDir, name).also { it.writeBytes(byteArrayOf(1, 2, 3)) }
                return File(context.cacheDir, "invalid-$name.fpt").also {
                    it.writeBytes(FptContainer.write(json, mapOf(name to data)))
                }
            }
            val badImage = invalidAsset("background.png", JSONObject().put("isBackgroundEnabled", true))
            check(runCatching { ThemePackImporter.prepare(context, Uri.fromFile(badImage)) }.isFailure)
            check(prefs.all == current)
            passed += "invalid_android_image_keeps_current_theme"
            val badFont = invalidAsset("font.ttf", JSONObject().put("fontMode", "custom"))
            check(runCatching { ThemePackImporter.prepare(context, Uri.fromFile(badFont)) }.isFailure)
            check(prefs.all == current)
            passed += "invalid_android_font_keeps_current_theme"
            check(context.cacheDir.listFiles()?.none { it.isDirectory && it.name.startsWith("fpt-") } == true)
            passed += "failed_and_cancelled_staging_cleanup"

            context.failNextCommit = true
            ThemePackImporter.prepare(context, Uri.fromFile(source)).use {
                check(ThemePackImporter.apply(context, it).isFailure)
            }
            check(prefs.all == current && font.isFile)
            passed += "preference_failure_rolls_back_and_preserves_assets"

            ThemePackImporter.prepare(context, Uri.fromFile(source)).use {
                check(it.files.getValue("background_home.png").delete())
                check(ThemePackImporter.apply(context, it).isFailure)
            }
            check(prefs.all == current && font.isFile)
            passed += "asset_copy_failure_keeps_current_theme"

            val second = File(context.cacheDir, "palette-only.fpt").also {
                it.writeBytes(FptContainer.write(JSONObject().put("customColor", "green"), emptyMap()))
            }
            ThemePackImporter.prepare(context, Uri.fromFile(second)).use {
                ThemePackImporter.apply(context, it).getOrThrow()
            }
            check(font.isFile && FontConfig.fontMode == FontMode.CUSTOM)
            passed += "partial_pack_retains_previous_font_asset"
            ThemePackImporter.reset(context).getOrThrow()
            check(!prefs.contains(ThemePackImporter.ACTIVE_PACK) && FontConfig.fontMode == FontMode.SYSTEM_DEFAULT)
            check(!ThemePackState.hasBackground && !font.exists())
            check(prefs.getString("ui_mode", null) == "miuix" && prefs.getString("intent_token", null) == "original")
            passed += "reset_scoped_to_theme_and_garbage_collection"
            Bundle().apply {
                putString("theme_tests", "${passed.size} passed: ${passed.joinToString()}")
                putString("core", "recognized=${Natives.isManager} uapi=${Natives.kernelUAPIVersion}")
            }
        } finally {
            instrumentation.runOnMainSync {
                FontConfig.reloadFromPreferences(base)
                ThemePackState.reload(base)
            }
            base.deleteSharedPreferences(context.prefix + "settings")
            root.deleteRecursively()
        }
    }
}
