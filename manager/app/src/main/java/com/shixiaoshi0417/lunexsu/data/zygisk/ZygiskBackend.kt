package com.shixiaoshi0417.lunexsu.data.zygisk

import androidx.compose.runtime.Immutable

/** The API loader is a companion module, never a third Zygisk backend. */
internal enum class ZygiskModule(val moduleId: String, val assetName: String) {
    Re("rezygisk", "re"),
    Neo("zygisksu", "neo"),
    NextNext("zygisknextsu", "nextnext");
}

enum class ZygiskBackend(internal val module: ZygiskModule, val displayName: String) {
    Re(ZygiskModule.Re, "ReZygisk"),
    Neo(ZygiskModule.Neo, "NeoZygisk");

    val moduleId: String get() = module.moduleId

    companion object {
        val Default = Re

        fun supportedOn(primaryAbi: String?): List<ZygiskBackend> =
            if (primaryAbi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) entries else emptyList()
    }
}

internal const val ZYGISK_OWNER_MARKER = ".lunexsu_builtin_zygisk"

internal data class ZygiskModuleFlags(
    val present: Boolean = false,
    val owned: Boolean = false,
    val update: Boolean = false,
    val remove: Boolean = false,
    val disabled: Boolean = false,
    val legacyVex: Boolean = false,
) {
    val isOwned: Boolean get() = present && owned
    val configured: Boolean get() = isOwned && !remove && !disabled
    // Removals run until reboot; staged installs only start on the next boot.
    val active: Boolean get() = isOwned && !update && !disabled
}

@Immutable
data class ZygiskStatus(
    val configured: ZygiskBackend? = null,
    val active: ZygiskBackend? = null,
    val monitorRunning: Boolean = false,
    val conflictId: String? = null,
    val nextNextConfigured: Boolean = false,
    val nextNextActive: Boolean = false,
    val legacyVex: Boolean = false,
) {
    val pendingReboot: Boolean get() = configured != active || nextNextConfigured != nextNextActive
}

internal fun zygiskStatusOf(flags: Map<ZygiskModule, ZygiskModuleFlags>, monitorRunning: Boolean): ZygiskStatus =
    ZygiskStatus(
        configured = ZygiskBackend.entries.firstOrNull { flags[it.module]?.configured == true },
        active = ZygiskBackend.entries.firstOrNull { flags[it.module]?.active == true },
        monitorRunning = monitorRunning,
        conflictId = ZygiskModule.entries.firstOrNull { flags[it]?.let { f -> f.present && !f.owned } == true }?.moduleId,
        nextNextConfigured = flags[ZygiskModule.NextNext]?.configured == true,
        nextNextActive = flags[ZygiskModule.NextNext]?.active == true,
        legacyVex = flags[ZygiskModule.Re]?.let { it.isOwned && it.legacyVex } == true,
    )

/** Lines: module id present owned update remove disabled legacyVex; monitor 0|1. */
internal fun parseZygiskFlags(lines: List<String>): Pair<Map<ZygiskModule, ZygiskModuleFlags>, Boolean> {
    val flags = mutableMapOf<ZygiskModule, ZygiskModuleFlags>()
    var monitor = false
    lines.map { it.trim().split(Regex("\\s+")) }.forEach { fields ->
        when (fields.firstOrNull()) {
            "module" -> {
                val module = ZygiskModule.entries.firstOrNull { it.moduleId == fields.getOrNull(1) }
                if (module != null && fields.size == 8) {
                    val bits = fields.drop(2).map { it == "1" }
                    flags[module] = ZygiskModuleFlags(bits[0], bits[1], bits[2], bits[3], bits[4], bits[5])
                }
            }
            "monitor" -> monitor = fields.getOrNull(1) == "1"
        }
    }
    return flags to monitor
}

internal fun zygiskStatusScript(): String = buildString {
    ZygiskModule.entries.forEach { module ->
        val id = module.moduleId
        appendLine("m=/data/adb/modules/$id; u=/data/adb/modules_update/$id; p=0; o=0; up=0; rm=0; d=0; v=0")
        appendLine("[ -d \"\$m\" ] && p=1")
        appendLine("if [ -f \"\$m/$ZYGISK_OWNER_MARKER\" ] || [ -f \"\$u/$ZYGISK_OWNER_MARKER\" ]; then o=1; fi")
        appendLine("[ -e \"\$m/update\" ] && up=1; [ -e \"\$m/remove\" ] && rm=1; [ -e \"\$m/disable\" ] && d=1")
        if (module == ZygiskModule.Re) {
            appendLine("prop=\"\$m/module.prop\"; [ -f \"\$u/module.prop\" ] && prop=\"\$u/module.prop\"")
            appendLine("grep -q '^name=VexZygisk' \"\$prop\" 2>/dev/null && v=1")
        }
        appendLine("echo \"module $id \$p \$o \$up \$rm \$d \$v\"")
    }
    appendLine("if pidof zygisk-ptrace64 >/dev/null 2>&1 || pidof zygisk-ptrace32 >/dev/null 2>&1; then echo 'monitor 1'; else echo 'monitor 0'; fi")
}

internal sealed interface ZygiskStep {
    val module: ZygiskModule
    data class Install(override val module: ZygiskModule) : ZygiskStep
    data class Restore(override val module: ZygiskModule) : ZygiskStep
    data class Uninstall(override val module: ZygiskModule) : ZygiskStep
}

/** Never overwrite Vex's active/staged directory with Re's same-id module. */
internal fun requiresVexRemoval(flags: Map<ZygiskModule, ZygiskModuleFlags>, target: ZygiskBackend?): Boolean =
    target == ZygiskBackend.Re && flags[ZygiskModule.Re]?.let { it.isOwned && it.legacyVex } == true

internal fun planZygiskChange(
    flags: Map<ZygiskModule, ZygiskModuleFlags>,
    target: ZygiskBackend?,
    nextNext: Boolean = false,
): List<ZygiskStep> = buildList {
    require(!nextNext || target != null) { "ZygiskNextNext requires a backend" }
    require(!requiresVexRemoval(flags, target)) { "Disable Vex and reboot before installing ReZygisk" }
    fun enable(module: ZygiskModule) {
        val current = flags[module]
        when {
            current?.isOwned != true -> add(ZygiskStep.Install(module))
            current.remove || current.disabled -> add(ZygiskStep.Restore(module))
        }
    }
    fun disable(module: ZygiskModule) {
        if (flags[module]?.let { it.isOwned && !it.remove } == true) add(ZygiskStep.Uninstall(module))
    }
    // Stage the backend before its dependent addon; remove the addon before disabling its backend.
    if (target != null) enable(target.module)
    if (nextNext) enable(ZygiskModule.NextNext) else disable(ZygiskModule.NextNext)
    ZygiskBackend.entries.filter { it != target }.forEach { disable(it.module) }
}

internal fun zygiskRollback(
    before: Map<ZygiskModule, ZygiskModuleFlags>,
    done: List<ZygiskStep>,
    failed: ZygiskStep,
    staged: Boolean,
): List<String> {
    val undo = done.asReversed().toMutableList()
    if (failed is ZygiskStep.Restore || (failed is ZygiskStep.Install && staged)) undo.add(0, failed)
    return undo.flatMap { step ->
        val id = step.module.moduleId
        when (step) {
            is ZygiskStep.Install -> listOf("module uninstall $id")
            is ZygiskStep.Uninstall -> listOf("module undo-uninstall $id")
            is ZygiskStep.Restore -> buildList {
                val flags = before[step.module]
                if (flags?.remove == true) add("module uninstall $id")
                if (flags?.disabled == true) add("module disable $id")
            }
        }
    }
}
