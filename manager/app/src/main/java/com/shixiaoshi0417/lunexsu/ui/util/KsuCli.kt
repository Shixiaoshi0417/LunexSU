package com.shixiaoshi0417.lunexsu.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import com.shixiaoshi0417.lunexsu.data.registry.DownloadVerificationException
import com.shixiaoshi0417.lunexsu.data.registry.RegistryDownloadStore
import com.shixiaoshi0417.lunexsu.data.registry.copyForInstall
import com.shixiaoshi0417.lunexsu.BuildConfig
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.data.LkmFilePreparation
import com.shixiaoshi0417.lunexsu.data.SessionResourceCache
import com.shixiaoshi0417.lunexsu.core.tasks.BootKernelVersion
import com.shixiaoshi0417.lunexsu.core.tasks.ExtractImage
import com.shixiaoshi0417.lunexsu.core.tasks.ProbeResult
import com.shixiaoshi0417.lunexsu.core.utils.DataSourceChannel
import com.shixiaoshi0417.lunexsu.ksuApp
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.zip.ZipFile

/**
 * @author weishu
 * @date 2023/1/1.
 */
private const val TAG = "KsuCli"

internal fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"
}

data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}

object KsuCli {
    private class Shells(val normal: Shell, val global: Shell) : AutoCloseable {
        override fun close() {
            try { normal.close() } finally { global.close() }
        }
    }
    private val shells = SessionResourceCache(create = {
        val normal = createRootShell()
        try { Shells(normal, createRootShell(true)) }
        catch (e: Throwable) { normal.close(); throw e }
    }, onCloseFailure = { Log.w(TAG, "Could not close retired shells", it) })

    /** New users get a fresh session; already running users finish before old shells close. */
    internal fun refreshAfterAuthentication() {
        shells.invalidate()
        // The session calls this on its IO dispatcher, before notifying the UI. Avoid making the
        // first recomposition start two shell processes; a failed warm-up remains safely retryable.
        try { shells.use { } }
        catch (e: Exception) { Log.w(TAG, "Could not prepare authenticated shells", e) }
    }

    fun <T> withShell(globalMnt: Boolean, block: Shell.() -> T): T = shells.use {
        (if (globalMnt) it.global else it.normal).block()
    }

    fun <T> submit(globalMnt: Boolean, block: Shell.() -> T): Future<T> = shells.submit(Shell.EXECUTOR) {
        (if (globalMnt) it.global else it.normal).block()
    }
}

/** Do not let this shell, pending jobs, or streams escape the block. */
fun <T> withRootShell(globalMnt: Boolean = false, block: Shell.() -> T): T =
    KsuCli.withShell(globalMnt, block)

fun <T> submitRootShell(globalMnt: Boolean = false, block: Shell.() -> T): Future<T> =
    KsuCli.submit(globalMnt, block)

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return try {
        if (globalMnt) {
            builder.build(getKsuDaemonPath(), "debug", "su", "-g")
        } else {
            builder.build(getKsuDaemonPath(), "debug", "su")
        }
    } catch (e: Throwable) {
        Log.w(TAG, "ksu failed: ", e)
        try {
            if (globalMnt) {
                builder.build("su", "-mm")
            } else {
                builder.build("su")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "su failed: ", e)
            builder.build("sh")
        }
    }
}

fun execKsud(args: String, newShell: Boolean = false, globalMnt: Boolean = false): Boolean {
    return if (newShell) {
        withNewRootShell(globalMnt = globalMnt) {
            ShellUtils.fastCmdResult(this, "${getKsuDaemonPath()} $args")
        }
    } else {
        withRootShell(globalMnt) { ShellUtils.fastCmdResult(this, "${getKsuDaemonPath()} $args") }
    }
}

suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    withRootShell {
        val out = newJob()
            .add("${getKsuDaemonPath()} feature check $feature").to(ArrayList<String>(), null).exec().out
        out.firstOrNull()?.trim().orEmpty()
    }
}

suspend fun getFeaturePersistValue(feature: String): Long? = readFeatureValue("--config $feature")

// Current value in kernel
suspend fun getFeatureValue(feature: String): Long? = readFeatureValue(feature)

private suspend fun readFeatureValue(args: String): Long? = withContext(Dispatchers.IO) {
    withRootShell {
        val out = newJob()
            .add("${getKsuDaemonPath()} feature get $args").to(ArrayList<String>(), null).exec().out
        val valueLine = out.firstOrNull { it.trim().startsWith("Value:") } ?: return@withRootShell null
        valueLine.substringAfter("Value:").trim().toLongOrNull()
    }
}

fun install() {
    val start = SystemClock.elapsedRealtime()
    val libadbroot = File(ksuApp.applicationInfo.nativeLibraryDir, "libadbroot.so").absolutePath
    val result = execKsud("install --libadbroot $libadbroot --data-path ${ksuApp.applicationInfo.deviceProtectedDataDir}", true)
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
}

fun listModules(): String = withRootShell {
    val out = newJob()
        .add("${getKsuDaemonPath()} module list").to(ArrayList(), null).exec().out
    out.joinToString("\n").ifBlank { "[]" }
}

fun getModuleCount(): Int {
    val result = listModules()
    runCatching {
        val array = JSONArray(result)
        return array.length()
    }.getOrElse { return 0 }
}

fun getSuperuserCount(): Int {
    return Natives.getSuperuserCount()
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable $id"
    } else {
        "module disable $id"
    }
    val result = execKsud(cmd, true)
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun undoUninstallModule(id: String): Boolean {
    val cmd = "module undo-uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}

fun flashModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    // Registry downloads live in app-private storage and carry their expected size + SHA-256;
    // the exact copy handed to ksud is re-hashed right before installing.
    val storeRoot = File(ksuApp.filesDir, RegistryDownloadStore.DIR_NAME)
    val expected = if (uri.scheme == ContentResolver.SCHEME_FILE) {
        uri.path?.let { RegistryDownloadStore.expectedFor(storeRoot, File(it)) }
    } else {
        null
    }
    val file = File.createTempFile("module-", ".zip", ksuApp.cacheDir)
    try {
        try {
            val input = ksuApp.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
            input.use { copyForInstall(it, file, expected) }
        } catch (e: IOException) {
            Log.w("KernelSU", "module $uri not installed: ${e.message}")
            if (e is DownloadVerificationException) {
                // Corrupted or tampered after download: never keep it around for a retry.
                uri.path?.let { RegistryDownloadStore.delete(storeRoot, File(it)) }
            }
            val message = "! ${e.message ?: "Failed to read module"}"
            onStderr(message)
            return FlashResult(1, message, false)
        }
        val cmd = "module install ${file.absolutePath}"
        val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
        Log.i("LunexSU", "install module $uri result: $result")
        return FlashResult(result, result.isSuccess && moduleNeedsReboot(file))
    } finally {
        file.delete()
    }
}

private val MODULE_ID_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")

// A hot installed module goes live without leaving the "update" flag behind
private fun moduleNeedsReboot(zip: File): Boolean {
    val id = runCatching {
        ZipFile(zip).use { archive ->
            val entry = archive.getEntry("module.prop") ?: return@use null
            archive.getInputStream(entry).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.firstOrNull { it.startsWith("id=") }?.substringAfter('=')?.trim()
            }
        }
    }.getOrNull()
    if (id == null || !MODULE_ID_REGEX.matches(id)) return true
    val dir = "/data/adb/modules/$id"
    // Any failure to check counts as needing a reboot
    return withRootShell { !ShellUtils.fastCmdResult(this, "[ -d $dir ] && [ ! -e $dir/update ]") }
}

fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add("${getKsuDaemonPath()} module action $moduleId")
            .to(stdoutCallback, stderrCallback).exec()
    }

    Log.i("LunexSU", "Module runAction result: $result")

    return result.isSuccess
}

/**
 * Runs a prepared one-shot script command (see ScriptCommand) in a new root shell in the
 * global mount namespace, like a module action. Returns the exit code.
 */
fun runOneshotScript(
    command: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Int {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add(command).to(stdoutCallback, stderrCallback).exec()
    }
    Log.i(TAG, "one-shot script exit code: ${result.code}")
    return result.code
}

/** Removes a script's extraction directory, including files the script created as root. */
fun removeAsRoot(path: String): Boolean {
    val escaped = "'" + path.replace("'", "'\\''") + "'"
    return withRootShell { ShellUtils.fastCmdResult(this, "rm -rf $escaped") }
}

fun restoreBoot(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} boot-restore -f", onStdout, onStderr)
    return FlashResult(result)
}

fun uninstallPermanently(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} uninstall --package-name ${BuildConfig.APPLICATION_ID}", onStdout, onStderr)
    return FlashResult(result)
}

@Parcelize
sealed class LkmSelection : Parcelable {
    @Parcelize
    data class LkmUri(val uri: Uri) : LkmSelection()

    @Parcelize
    data class KmiString(val value: String) : LkmSelection()

    @Parcelize
    data object KmiNone : LkmSelection()
}

private fun writeLkmFile(lkm: LkmSelection, record: ByteArray? = null, detectedKmi: String? = null): File? {
    if (lkm !is LkmSelection.LkmUri && record == null) return null
    return LkmFilePreparation.prepare(ksuApp.cacheDir, record) { source ->
        if (lkm is LkmSelection.LkmUri) {
            requireNotNull(ksuApp.contentResolver.openInputStream(lkm.uri)).use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            val kmi = LkmFilePreparation.requireKmi((lkm as? LkmSelection.KmiString)?.value, detectedKmi)
            val result = Shell.getShell().newJob()
                .add("${getKsuDaemonPath()} debug extract-binary ${kmi}_kernelsu.ko ${source.absolutePath}").exec()
            check(result.isSuccess && source.isFile) { "Cannot extract matching LKM" }
        }
    }
}

// A single module name as accepted by ksud's --block-module parser.
private fun isValidBlockModuleName(name: String): Boolean =
    name.isNotEmpty() && name.length <= 64 &&
        name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }

/** Parse the comma-separated block-modules field into the non-empty names. */
fun parseBlockModules(input: String): List<String> =
    input.split(',').map { it.trim() }.filter { it.isNotEmpty() }

// The kernel copies the block_modules= value into a 256-byte buffer, leaving
// 255 bytes; a longer list would be silently truncated, leaving some modules
// unblocked. Reject it here too.
private const val MAX_BLOCK_MODULES_LEN = 255

/** Whether every name in the block-modules field is valid (empty is valid). */
fun isBlockModulesValid(input: String): Boolean {
    val names = parseBlockModules(input)
    return names.all(::isValidBlockModuleName) &&
        names.joinToString(",").length <= MAX_BLOCK_MODULES_LEN
}

private fun bootPatchFlags(
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    blockModules: String,
): String = buildString {
    if (allowShell) append(" --allow-shell")
    if (enableAdb) append(" --enable-adbd")
    if (forceBackup) append(" --backup")
    // Names are validated in the UI before install; pass them through without
    // mutating. Drop the flag entirely (never inject) if anything slips through.
    val names = parseBlockModules(blockModules)
    if (names.isNotEmpty() && names.all(::isValidBlockModuleName)) {
        append(" --block-module ${names.joinToString(",")}")
    }
}

fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    ota: Boolean,
    partition: String?,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    blockModules: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
    superkeyRecord: ByteArray? = null,
): FlashResult {
    val resolver = ksuApp.contentResolver

    val bootFile = bootUri?.let { uri ->
        with(resolver.openInputStream(uri)) {
            val bootFile = File(ksuApp.cacheDir, "boot.img")
            bootFile.outputStream().use { output ->
                this?.copyTo(output)
            }

            bootFile
        }
    }

    var cmd = "boot-patch"

    cmd += if (bootFile == null) {
        // no boot.img, use -f to flash
        " -f"
    } else {
        " -b ${bootFile.absolutePath}"
    }
    cmd += bootPatchFlags(allowShell, enableAdb, forceBackup, blockModules)

    if (ota) {
        cmd += " -u"
    }

    val lkmFile = try {
        writeLkmFile(lkm, superkeyRecord, if (superkeyRecord != null && lkm is LkmSelection.KmiNone) {
            ShellUtils.fastCmd("${getKsuDaemonPath()} boot-info current-kmi").trim()
        } else null)
    } catch (e: Exception) {
        bootFile?.delete()
        return FlashResult(-1, "Cannot prepare superkey LKM: ${e.message}", false)
    }
    if (lkmFile != null) {
        cmd += " -m ${lkmFile.absolutePath}"
    } else if (lkm is LkmSelection.KmiString) {
        cmd += " --kmi ${lkm.value}"
    }

    if (bootFile != null) {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        cmd += " -o $downloadsDir"
    }

    partition?.let { part ->
        cmd += " --partition $part"
    }

    val result = try {
        flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
    } finally {
        bootFile?.delete()
        lkmFile?.delete()
    }
    Log.i("LunexSU", "install boot result: ${result.isSuccess}")

    // if boot uri is empty, it is direct install, when success, we should show reboot button
    val showReboot = bootUri == null && result.isSuccess // we create a temporary val here, to avoid calc showReboot double
    if (showReboot) { // because we decide do not update ksud when startActivity
        install() // install ksud here
    }
    return FlashResult(result, showReboot)
}

fun downloadBoot(
    url: String,
    partition: String,
    lkm: LkmSelection,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    blockModules: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
    superkeyRecord: ByteArray? = null,
): FlashResult {
    val bootFile = File(ksuApp.cacheDir, "download-boot.img")
    var probedKmi: String? = null
    try {
        onStdout("- Downloading and extracting boot image")
        val channel = DataSourceChannel(newDownloadClient(), url)
        val magic = readMagic(channel)
        val image = ExtractImage(bootFile, onStdout)
        // Extract the KMI here while the payload is open. ZipFile closes the
        // channel it is built on, so probe on a separate channel.
        val probeChannel = DataSourceChannel(newDownloadClient(), url)
        probedKmi = try {
            if (magic == "CrAU") {
                ExtractImage.probePayload(
                    probeChannel,
                    withKmi = lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            } else {
                ExtractImage.probe(
                    probeChannel,
                    withKmi = lkm is LkmSelection.KmiNone,
                    onProgress = onStdout,
                ).kmi
            }
        } finally {
            probeChannel.close()
        }
        if (magic == "CrAU") {
            image.consumePayload(channel, partition)
        } else {
            image.consume(channel, partition)
        }
    } catch (e: Exception) {
        bootFile.delete()
        return FlashResult(-1, e.message ?: "Download failed", false)
    }

    // init_boot/vendor_boot carry no kernel, so their KMI comes from the
    // payload's boot probe and must be passed explicitly. A remote download
    // is unrelated to this device, so ksud must not use the local kernel.
    val autoKmi = if (lkm is LkmSelection.KmiNone) {
        (probedKmi ?: BootKernelVersion.parseKmiFromBoot(bootFile))?.also {
            onStdout("- Auto detected KMI: $it")
        }
    } else {
        null
    }
    if (autoKmi == null && lkm is LkmSelection.KmiNone) {
        bootFile.delete()
        return FlashResult(-1, "Failed to determine KMI from the package", false)
    }

    var cmd = "${getKsuDaemonPath()} boot-patch -b ${bootFile.absolutePath}"
    cmd += bootPatchFlags(allowShell, enableAdb, forceBackup, blockModules)

    val lkmFile = try {
        writeLkmFile(lkm, superkeyRecord, autoKmi)
    } catch (e: Exception) {
        bootFile.delete()
        return FlashResult(-1, "Cannot prepare superkey LKM: ${e.message}", false)
    }
    if (lkmFile != null) {
        cmd += " -m ${lkmFile.absolutePath}"
    } else if (lkm is LkmSelection.KmiString) {
        cmd += " --kmi ${lkm.value}"
    }
    if (autoKmi != null) cmd += " --kmi $autoKmi"
    cmd += " --partition $partition"
    // ksud defaults to cwd, which is read-only in the su session; use Downloads.
    val downloadsDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    cmd += " -o $downloadsDir"

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }
    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = try {
        Shell.getShell().newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    } finally {
        lkmFile?.delete()
        bootFile.delete()
    }
    return FlashResult(result, false)
}

suspend fun probeRemoteBootPartitions(url: String): ProbeResult = withContext(Dispatchers.IO) {
    Log.d(TAG, "probe start: $url")
    val channel = DataSourceChannel(newDownloadClient(), url)
    Log.d(TAG, "probe connected, size=${channel.size()}")
    val magic = readMagic(channel)
    Log.d(TAG, "probe magic: $magic")
    // Only list the partitions here; the KMI is extracted later when the
    // payload is downloaded for patching.
    val result = if (magic == "CrAU") {
        ExtractImage.probePayload(channel, withKmi = false)
    } else {
        ExtractImage.probe(channel, withKmi = false)
    }
    Log.d(TAG, "probe partitions: ${result.partitions}")
    result
}

private fun newDownloadClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}

private fun readMagic(channel: DataSourceChannel): String {
    val buffer = ByteBuffer.allocate(4)
    channel.read(buffer)
    channel.position(0)
    return String(buffer.array(), StandardCharsets.ISO_8859_1)
}

fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        execKsud("soft-reboot", true, true)
        return
    }
    withRootShell {
        if (reason == "recovery") {
            // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
            ShellUtils.fastCmd(this, "/system/bin/input keyevent 26")
        }
        ShellUtils.fastCmd(this, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
    }
}

fun rootAvailable(): Boolean = withRootShell { isRoot }

suspend fun getCurrentKmi(): String = withContext(Dispatchers.IO) {
    withRootShell {
        val cmd = "boot-info current-kmi"
        ShellUtils.fastCmd(this, "${getKsuDaemonPath()} $cmd")
    }
}

suspend fun getSupportedKmis(): List<String> = withContext(Dispatchers.IO) {
    withRootShell {
        val cmd = "boot-info supported-kmis"
        val result = newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), ArrayList()).exec()
        check(result.isSuccess) { "Unable to read bundled KMI list (exit ${result.code})" }
        result.out.filter { it.isNotBlank() }.map { it.trim() }.distinct().sorted()
    }
}

suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    withRootShell {
        val cmd = "boot-info is-ab-device"
        ShellUtils.fastCmd(this, "${getKsuDaemonPath()} $cmd").trim().toBoolean()
    }
}

suspend fun getDefaultPartition(): String = withContext(Dispatchers.IO) {
    withRootShell {
        if (isRoot) {
            val cmd = "boot-info default-partition"
            ShellUtils.fastCmd(this, "${getKsuDaemonPath()} $cmd").trim()
        } else {
            if (!Os.uname().release.contains("android12-")) "init_boot" else "boot"
        }
    }
}

suspend fun getSlotSuffix(ota: Boolean): String = withContext(Dispatchers.IO) {
    withRootShell {
        val cmd = if (ota) {
            "boot-info slot-suffix --ota"
        } else {
            "boot-info slot-suffix"
        }
        ShellUtils.fastCmd(this, "${getKsuDaemonPath()} $cmd").trim()
    }
}

suspend fun getAvailablePartitions(): List<String> = withContext(Dispatchers.IO) {
    withRootShell {
        val cmd = "boot-info available-partitions"
        val out = newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
        out.filter { it.isNotBlank() }.map { it.trim() }
    }
}

// Known vendor anti-root kernel modules that `--block-module` is meant for.
private val KNOWN_BLOCKABLE_MODULES = listOf(
    "vr", "vklp", "oplus_secure_guard", "oplus_secure_guard_new", "mkp"
)

// Return the known anti-root modules currently loaded on this device (per
// /proc/modules), so the install screen can pre-fill only the relevant ones.
suspend fun getLoadedBlockableModules(): List<String> = withContext(Dispatchers.IO) {
    withRootShell {
        val out = newJob().add("cat /proc/modules").to(ArrayList(), null).exec().out
        val loaded = out.mapNotNull { it.trim().substringBefore(' ').takeIf(String::isNotBlank) }.toHashSet()
        KNOWN_BLOCKABLE_MODULES.filter { it in loaded }
    }
}

fun hasMagisk(): Boolean = withRootShell(true) {
    val result = newJob().add("which magisk").exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    result.isSuccess
}

fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) {
        return true
    }
    if ('\u0000' in rules) return false
    val command = ProfileCommand.build(getKsuDaemonPath(), "sepolicy", "check", "--", rules)
    return withRootShell {
        val result =
            newJob().add(command).to(ArrayList(), null)
                .exec()
        result.isSuccess
    }
}

fun getSepolicy(pkg: String): String {
    if (!ProfileCommand.isValidId(pkg)) return ""
    val command = ProfileCommand.profile(getKsuDaemonPath(), "get-sepolicy", pkg)
    return withRootShell {
        val result = newJob().add(command).to(ArrayList(), null).exec()
        Log.i(TAG, "code: ${result.code}, out: ${result.out}, err: ${result.err}")
        result.out.joinToString("\n")
    }
}

fun setSepolicy(pkg: String, rules: String): Boolean {
    if (!ProfileCommand.isValidId(pkg) || '\u0000' in rules) return false
    val command = ProfileCommand.profile(getKsuDaemonPath(), "set-sepolicy", pkg, rules)
    return withRootShell {
        val result = newJob().add(command).to(ArrayList(), null).exec()
        Log.i(TAG, "set sepolicy result: ${result.code}")
        result.isSuccess
    }
}

fun listAppProfileTemplates(): List<String> = withRootShell {
    newJob().add(ProfileCommand.build(getKsuDaemonPath(), "profile", "list-templates"))
        .to(ArrayList(), null).exec().out.filter(ProfileCommand::isValidId)
}

fun getAppProfileTemplate(id: String): String {
    if (!ProfileCommand.isValidId(id)) return ""
    val command = ProfileCommand.profile(getKsuDaemonPath(), "get-template", id)
    return withRootShell {
        newJob().add(command).to(ArrayList(), null).exec().out.joinToString("\n")
    }
}

fun setAppProfileTemplate(id: String, template: String): Boolean {
    if (!ProfileCommand.isValidId(id) || '\u0000' in template) return false
    val command = ProfileCommand.profile(getKsuDaemonPath(), "set-template", id, template)
    return withRootShell {
        newJob().add(command).to(ArrayList(), null).exec().isSuccess
    }
}

fun deleteAppProfileTemplate(id: String): Boolean {
    if (!ProfileCommand.isValidId(id)) return false
    val command = ProfileCommand.profile(getKsuDaemonPath(), "delete-template", id)
    return withRootShell {
        newJob().add(command).to(ArrayList(), null).exec().isSuccess
    }
}

fun forceStopApp(packageName: String, userId: Int? = null): Unit = withRootShell {
    val userArg = userId?.let { " --user $it" } ?: ""
    val result = newJob().add("am force-stop$userArg $packageName").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

fun launchApp(packageName: String, userId: Int? = null): Unit = withRootShell {
    val userArg = userId?.let { " --user $it" } ?: ""
    val result =
        newJob()
            .add("cmd package resolve-activity --brief$userArg $packageName | tail -n 1 | xargs cmd activity start-activity$userArg -n")
            .exec()
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String, userId: Int? = null) {
    forceStopApp(packageName, userId)
    launchApp(packageName, userId)
}
