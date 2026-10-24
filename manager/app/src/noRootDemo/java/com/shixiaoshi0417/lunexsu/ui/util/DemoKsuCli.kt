@file:Suppress("UNUSED_PARAMETER")
package com.shixiaoshi0417.lunexsu.ui.util

import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.core.tasks.ProbeResult
import com.shixiaoshi0417.lunexsu.demo.DemoShell
import com.shixiaoshi0417.lunexsu.demo.DemoState
import com.topjohnwu.superuser.Shell
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

fun getKsuDaemonPath() = "demo://no-executable"
data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}
object KsuCli {
    fun refreshAfterAuthentication() = Unit
    fun <T> withShell(globalMnt: Boolean, block: Shell.() -> T): T = DemoShell().use(block)
    fun <T> submit(globalMnt: Boolean, block: Shell.() -> T): Future<T> =
        CompletableFuture.completedFuture(withShell(globalMnt, block))
}
fun <T> withRootShell(globalMnt: Boolean = false, block: Shell.() -> T): T = KsuCli.withShell(globalMnt, block)
fun <T> submitRootShell(globalMnt: Boolean = false, block: Shell.() -> T): Future<T> = KsuCli.submit(globalMnt, block)
inline fun <T> withNewRootShell(globalMnt: Boolean = false, block: Shell.() -> T): T = DemoShell().use(block)
fun createRootShell(globalMnt: Boolean = false): Shell = DemoShell()
fun Uri.getFileName(context: Context): String? = context.contentResolver.query(this, null, null, null, null)?.use {
    if (it.moveToFirst()) it.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { index -> index >= 0 }?.let(it::getString) else null
}
fun execKsud(args: String, newShell: Boolean = false, globalMnt: Boolean = false): Boolean {
    val parts = args.split(' ')
    if (parts.size == 4 && parts.take(2) == listOf("feature", "set")) {
        DemoState.features[parts[2]] = parts[3].toLongOrNull() ?: return false
        return true
    }
    return args == "feature save"
}
suspend fun getFeatureStatus(feature: String) = "supported"
suspend fun getFeaturePersistValue(feature: String): Long? = DemoState.features.getOrDefault(feature, 1)
suspend fun getFeatureValue(feature: String): Long? = getFeaturePersistValue(feature)
fun install() = Unit
fun listModules(): String = JSONArray().apply {
    DemoState.moduleRows().forEach { (id, enabled, removed) -> put(JSONObject()
        .put("id", id).put("name", "[DEMO] $id").put("author", "LunexSU UI Demo")
        .put("version", "1.0-demo").put("versionCode", 1).put("description", DemoState.NOTICE)
        .put("enabled", enabled).put("remove", removed).put("update", false).put("action", true)
        .put("web", false).put("metamodule", false).put("updateJson", "")) }
}.toString()
fun getModuleCount() = DemoState.moduleRows().count { it.second && !it.third }
fun getSuperuserCount() = Natives.getSuperuserCount()
fun toggleModule(id: String, enable: Boolean) = DemoState.toggle(id, enable)
fun undoUninstallModule(id: String) = DemoState.remove(id, false)
fun uninstallModule(id: String) = DemoState.remove(id, true)
private fun preview(onStdout: (String) -> Unit): FlashResult {
    onStdout(DemoState.NOTICE)
    return FlashResult(0, "", false) // Successful UI simulation, never a successful flash.
}
fun flashModule(uri: Uri, onStdout: (String) -> Unit, onStderr: (String) -> Unit) = preview(onStdout)
fun runModuleAction(moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit): Boolean {
    onStdout(DemoState.NOTICE); return true
}
fun runOneshotScript(command: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit): Int {
    onStdout(DemoState.NOTICE); return 0
}
fun removeAsRoot(path: String) = false
fun restoreBoot(onStdout: (String) -> Unit, onStderr: (String) -> Unit) = preview(onStdout)
fun uninstallPermanently(onStdout: (String) -> Unit, onStderr: (String) -> Unit) = preview(onStdout)
@Parcelize sealed class LkmSelection : Parcelable {
    data class LkmUri(val uri: Uri) : LkmSelection()
    data class KmiString(val value: String) : LkmSelection()
    data object KmiNone : LkmSelection()
}
fun installBoot(bootUri: Uri?, lkm: LkmSelection, ota: Boolean, partition: String?, allowShell: Boolean,
    enableAdb: Boolean, forceBackup: Boolean, onStdout: (String) -> Unit,
    onStderr: (String) -> Unit, superkeyRecord: ByteArray? = null) = preview(onStdout)
fun downloadBoot(url: String, partition: String, lkm: LkmSelection, allowShell: Boolean, enableAdb: Boolean,
    forceBackup: Boolean, onStdout: (String) -> Unit, onStderr: (String) -> Unit,
    superkeyRecord: ByteArray? = null) = preview(onStdout)
suspend fun probeRemoteBootPartitions(url: String) = ProbeResult(listOf("boot", "init_boot", "vendor_boot"), "android14-6.1")
fun reboot(reason: String = "") = Unit
fun rootAvailable() = true // UI simulation only; DemoShell deliberately reports NON_ROOT_SHELL.
suspend fun getCurrentKmi() = "android14-6.1"
suspend fun getSupportedKmis() = listOf("android14-6.1", "android15-6.6")
suspend fun isAbDevice() = true
suspend fun getDefaultPartition() = "init_boot"
suspend fun getSlotSuffix(ota: Boolean) = if (ota) "_b" else "_a"
suspend fun getAvailablePartitions() = listOf("boot", "init_boot", "vendor_boot")
fun hasMagisk() = false
fun isSepolicyValid(rules: String?) = !rules.isNullOrBlank()
fun getSepolicy(pkg: String) = DemoState.policies[pkg].orEmpty()
fun setSepolicy(pkg: String, rules: String): Boolean { DemoState.policies[pkg] = rules; return true }
fun listAppProfileTemplates() = DemoState.templates.keys.toList()
fun getAppProfileTemplate(id: String) = DemoState.templates[id].orEmpty()
fun setAppProfileTemplate(id: String, template: String): Boolean { DemoState.templates[id] = template; return true }
fun deleteAppProfileTemplate(id: String) = DemoState.templates.remove(id) != null
fun forceStopApp(packageName: String, userId: Int? = null) = Unit
fun launchApp(packageName: String, userId: Int? = null) = Unit
fun restartApp(packageName: String, userId: Int? = null) = Unit
