package com.shixiaoshi0417.lunexsu.data.zygisk

import androidx.compose.runtime.Immutable

/**
 * A built-in Zygisk implementation. Each one ships as its unmodified upstream module zip and is
 * installed through ksud's regular module flow, so every change only takes effect after a reboot.
 */
enum class ZygiskBackend(
    val moduleId: String,
    val displayName: String,
    internal val assetName: String,
    internal val abis: Set<String>,
) {
    Vex("rezygisk", "VexZygisk", "vex", setOf("arm64-v8a")),
    Neo("zygisksu", "NeoZygisk", "neo", setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"));

    companion object {
        val Default = Vex

        /** Backends whose upstream installer accepts a device with this primary ABI. */
        fun supportedOn(primaryAbi: String?): List<ZygiskBackend> = entries.filter { primaryAbi in it.abis }
    }
}

/** Marks a module directory as installed by the built-in Zygisk switch rather than by the user. */
internal const val ZYGISK_OWNER_MARKER = ".lunexsu_builtin_zygisk"

/** The flags ksud keeps for one module id. */
internal data class ZygiskModuleFlags(
    val present: Boolean = false,
    val owned: Boolean = false,
    val update: Boolean = false,
    val remove: Boolean = false,
    val disabled: Boolean = false,
) {
    val isOwned: Boolean get() = present && owned
}

@Immutable
data class ZygiskStatus(
    /** The backend the next boot starts, or null when the built-in Zygisk is off. */
    val configured: ZygiskBackend? = null,
    /** The backend this boot loaded. */
    val active: ZygiskBackend? = null,
    val monitorRunning: Boolean = false,
    /** A Zygisk module the user installed outside this switch; running both would inject twice. */
    val conflictId: String? = null,
) {
    val pendingReboot: Boolean get() = configured != active
}

internal fun zygiskStatusOf(flags: Map<ZygiskBackend, ZygiskModuleFlags>, monitorRunning: Boolean): ZygiskStatus {
    fun owned(backend: ZygiskBackend) = flags[backend]?.takeIf { it.isOwned }
    return ZygiskStatus(
        configured = ZygiskBackend.entries.firstOrNull { backend ->
            owned(backend)?.let { !it.remove && !it.disabled } == true
        },
        // A pending removal still runs until the reboot; a fresh install does not run yet.
        active = ZygiskBackend.entries.firstOrNull { backend ->
            owned(backend)?.let { !it.update && !it.disabled } == true
        },
        monitorRunning = monitorRunning,
        conflictId = ZygiskBackend.entries.firstOrNull { backend ->
            flags[backend]?.let { it.present && !it.owned } == true
        }?.moduleId,
    )
}

/**
 * Parses the output of [zygiskStatusScript]: one `module <id> <present> <owned> <update> <remove>
 * <disabled>` line per backend and a final `monitor <0|1>` line.
 */
internal fun parseZygiskFlags(lines: List<String>): Pair<Map<ZygiskBackend, ZygiskModuleFlags>, Boolean> {
    val flags = mutableMapOf<ZygiskBackend, ZygiskModuleFlags>()
    var monitor = false
    lines.map { it.trim().split(' ') }.forEach { fields ->
        when (fields.firstOrNull()) {
            "module" -> {
                val backend = ZygiskBackend.entries.firstOrNull { it.moduleId == fields.getOrNull(1) }
                if (backend != null && fields.size == 7) {
                    val bits = fields.drop(2).map { it == "1" }
                    flags[backend] = ZygiskModuleFlags(bits[0], bits[1], bits[2], bits[3], bits[4])
                }
            }
            "monitor" -> monitor = fields.getOrNull(1) == "1"
        }
    }
    return flags to monitor
}

internal fun zygiskStatusScript(): String = buildString {
    ZygiskBackend.entries.forEach { backend ->
        val id = backend.moduleId
        appendLine("m=/data/adb/modules/$id; u=/data/adb/modules_update/$id; p=0; o=0; up=0; rm=0; d=0")
        appendLine("[ -d \"\$m\" ] && p=1")
        appendLine("if [ -f \"\$m/$ZYGISK_OWNER_MARKER\" ] || [ -f \"\$u/$ZYGISK_OWNER_MARKER\" ]; then o=1; fi")
        appendLine("[ -e \"\$m/update\" ] && up=1; [ -e \"\$m/remove\" ] && rm=1; [ -e \"\$m/disable\" ] && d=1")
        appendLine("echo \"module $id \$p \$o \$up \$rm \$d\"")
    }
    appendLine("if pidof zygisk-ptrace64 >/dev/null 2>&1 || pidof zygisk-ptrace32 >/dev/null 2>&1; then echo 'monitor 1'; else echo 'monitor 0'; fi")
}

internal sealed interface ZygiskStep {
    /** Installs the bundled zip; it starts on the next boot. */
    data class Install(val backend: ZygiskBackend) : ZygiskStep

    /** Clears a pending removal or a safe-mode disable of an installed backend. */
    data class Restore(val backend: ZygiskBackend) : ZygiskStep

    /** Marks an installed backend for removal; its uninstall.sh runs on the next boot. */
    data class Uninstall(val backend: ZygiskBackend) : ZygiskStep
}

/**
 * The steps that make [target] the backend of the next boot. The new backend is staged before
 * the old one is marked for removal, so a failed install leaves the current setup in place.
 */
internal fun planZygiskChange(flags: Map<ZygiskBackend, ZygiskModuleFlags>, target: ZygiskBackend?): List<ZygiskStep> =
    buildList {
        if (target != null) {
            val current = flags[target]
            when {
                current?.isOwned != true -> add(ZygiskStep.Install(target))
                current.remove || current.disabled -> add(ZygiskStep.Restore(target))
            }
        }
        ZygiskBackend.entries.filter { it != target }.forEach { backend ->
            val other = flags[backend]
            if (other?.isOwned == true && !other.remove) add(ZygiskStep.Uninstall(backend))
        }
    }
