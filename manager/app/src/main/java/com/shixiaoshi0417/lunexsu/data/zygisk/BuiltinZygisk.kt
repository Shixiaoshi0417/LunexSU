package com.shixiaoshi0417.lunexsu.data.zygisk

import android.os.Build
import android.util.Log
import com.shixiaoshi0417.lunexsu.BuildConfig
import com.shixiaoshi0417.lunexsu.ksuApp
import com.shixiaoshi0417.lunexsu.ui.util.getKsuDaemonPath
import com.shixiaoshi0417.lunexsu.ui.util.getRootShell
import com.shixiaoshi0417.lunexsu.ui.util.withNewRootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface ZygiskApplyResult {
    /** The next boot already uses the requested setup. */
    data object Unchanged : ZygiskApplyResult
    data class Changed(val status: ZygiskStatus) : ZygiskApplyResult
    data class Conflict(val moduleId: String) : ZygiskApplyResult
    data class Failed(val message: String) : ZygiskApplyResult
}

/** Installs and removes the bundled Zygisk backends through ksud's module commands. */
object BuiltinZygisk {
    private const val TAG = "BuiltinZygisk"
    private val mutex = Mutex()

    val supportedBackends: List<ZygiskBackend> by lazy {
        ZygiskBackend.supportedOn(Build.SUPPORTED_ABIS.firstOrNull())
    }

    /** The backend switching the feature on selects: VexZygisk where its installer accepts the device. */
    val defaultBackend: ZygiskBackend?
        get() = ZygiskBackend.Default.takeIf { it in supportedBackends } ?: supportedBackends.firstOrNull()

    suspend fun status(): ZygiskStatus = withContext(Dispatchers.IO) {
        val (flags, monitor) = readFlags()
        zygiskStatusOf(flags, monitor)
    }

    /** Ids of the modules this switch installed, which the module list leaves to the settings card. */
    suspend fun ownedModuleIds(): Set<String> = withContext(Dispatchers.IO) {
        readFlags().first.filterValues { it.isOwned }.keys.mapTo(mutableSetOf()) { it.moduleId }
    }

    suspend fun apply(target: ZygiskBackend?): ZygiskApplyResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (flags, monitor) = readFlags()
            val conflict = zygiskStatusOf(flags, monitor).conflictId
            if (target != null && conflict != null) return@withLock ZygiskApplyResult.Conflict(conflict)
            if (target != null && target !in supportedBackends) {
                return@withLock ZygiskApplyResult.Failed("${target.displayName}: ${Build.SUPPORTED_ABIS.firstOrNull()}")
            }
            val steps = planZygiskChange(flags, target)
            if (steps.isEmpty()) return@withLock ZygiskApplyResult.Unchanged
            val done = mutableListOf<ZygiskStep>()
            for (step in steps) {
                val error = when (step) {
                    is ZygiskStep.Install -> install(step.backend)
                    is ZygiskStep.Restore -> ksud("module undo-uninstall ${step.backend.moduleId}")
                        ?: ksud("module enable ${step.backend.moduleId}")
                    is ZygiskStep.Uninstall -> ksud("module uninstall ${step.backend.moduleId}")
                }
                if (error != null) {
                    val staged = step is ZygiskStep.Install && readFlags().first[step.backend]?.present == true
                    val rollbackErrors = zygiskRollback(flags, done, step, staged).mapNotNull { ksud(it) }
                    val message = (listOf(error) + rollbackErrors.map { "rollback: $it" }).joinToString("\n")
                    return@withLock ZygiskApplyResult.Failed(message)
                }
                done += step
            }
            val status = zygiskStatusOf(readFlags().first, monitor)
            if (status.configured != target) {
                ZygiskApplyResult.Failed("expected ${target?.displayName ?: "off"}, got ${status.configured?.displayName ?: "off"}")
            } else {
                ZygiskApplyResult.Changed(status)
            }
        }
    }

    private fun readFlags(): Pair<Map<ZygiskBackend, ZygiskModuleFlags>, Boolean> {
        val out = getRootShell().newJob().add(zygiskStatusScript()).to(ArrayList(), null).exec().out
        return parseZygiskFlags(out)
    }

    /** Runs a ksud command and returns null on success or the tail of its output on failure. */
    private fun ksud(args: String): String? {
        val out = ArrayList<String>()
        // Module installers run arbitrary scripts, so they get a fresh shell like flashModule does.
        val result = withNewRootShell { newJob().add("${getKsuDaemonPath()} $args").to(out, out).exec() }
        Log.i(TAG, "ksud $args: ${result.code}")
        return if (result.isSuccess) null else out.failureSummary(result.code)
    }

    private fun install(backend: ZygiskBackend): String? {
        val zip = File(ksuApp.cacheDir, "zygisk-${backend.assetName}.zip")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            ksuApp.assets.open("zygisk/${backend.assetName}.zip").use { input ->
                zip.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != backend.expectedSha256) return "${backend.displayName}: SHA-256 mismatch"
            ksud("module install ${zip.absolutePath}")?.let { return it }
            // Both copies carry the marker: modules/<id> holds the pending entry until the
            // reboot moves modules_update/<id> into its place.
            val marker = ZYGISK_OWNER_MARKER
            val id = backend.moduleId
            val result = getRootShell().newJob()
                .add("touch /data/adb/modules_update/$id/$marker /data/adb/modules/$id/$marker").exec()
            return if (result.isSuccess) null else "${backend.displayName}: marker"
        } catch (e: Exception) {
            Log.w(TAG, "install ${backend.displayName} failed", e)
            return e.message ?: e.javaClass.simpleName
        } finally {
            zip.delete()
        }
    }

    private val ZygiskBackend.expectedSha256: String
        get() = when (this) {
            ZygiskBackend.Vex -> BuildConfig.ZYGISK_VEX_SHA256
            ZygiskBackend.Neo -> BuildConfig.ZYGISK_NEO_SHA256
        }

    private fun List<String>.failureSummary(code: Int): String =
        filter { it.isNotBlank() }.takeLast(3).joinToString("\n").ifEmpty { "exit $code" }
}
