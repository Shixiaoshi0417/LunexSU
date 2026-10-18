package com.shixiaoshi0417.lunexsu.data.zygisk

import androidx.compose.runtime.Immutable

@Immutable
data class ZygiskModuleVersion(val installed: String? = null, val staged: String? = null)

enum class ZygiskOverviewState { Unavailable, SafeMode, Conflict, PendingReboot, MonitorDetected, Configured, Disabled }

/** A read-only snapshot. Neither module flags nor a monitor process prove successful injection. */
@Immutable
data class ZygiskOverview(
    val available: Boolean = false,
    val status: ZygiskStatus = ZygiskStatus(),
    val versions: Map<String, ZygiskModuleVersion> = emptyMap(),
) {
    fun state(safeMode: Boolean): ZygiskOverviewState = when {
        safeMode -> ZygiskOverviewState.SafeMode
        !available -> ZygiskOverviewState.Unavailable
        status.conflictId != null || status.legacyVex -> ZygiskOverviewState.Conflict
        status.pendingReboot -> ZygiskOverviewState.PendingReboot
        status.monitorRunning -> ZygiskOverviewState.MonitorDetected
        status.configured != null -> ZygiskOverviewState.Configured
        else -> ZygiskOverviewState.Disabled
    }
}

private const val OVERVIEW_END = "lunexsu-zygisk-overview-v1"

/** Fail closed on incomplete shell output instead of turning a query failure into 'disabled'. */
internal fun parseZygiskOverview(lines: List<String>): ZygiskOverview {
    if (lines.size !in 5..11 || lines.last() != OVERVIEW_END) return ZygiskOverview()
    val records = lines.dropLast(1)
    val modules = records.filter { it.startsWith("module ") }
    val monitors = records.filter { it.startsWith("monitor ") }
    val versions = records.filter { it.startsWith("version ") }
    if (modules.size != ZygiskModule.entries.size || monitors.size != 1 ||
        modules.size + monitors.size + versions.size != records.size ||
        monitors.single() !in listOf("monitor 0", "monitor 1")
    ) return ZygiskOverview()
    val ids = ZygiskModule.entries.map { it.moduleId }.toSet()
    val seen = mutableSetOf<String>()
    for (line in modules) {
        val fields = line.split(' ')
        if (fields.size != 8 || fields[1] !in ids || !seen.add(fields[1]) ||
            fields.drop(2).any { it != "0" && it != "1" }
        ) return ZygiskOverview()
    }
    val metadata = mutableMapOf<String, ZygiskModuleVersion>()
    val versionKeys = mutableSetOf<Pair<String, String>>()
    for (line in versions) {
        val fields = line.split(' ', limit = 4)
        if (fields.size != 4 || fields[1] !in ids || fields[2] !in listOf("installed", "staged") ||
            !versionKeys.add(fields[1] to fields[2]) || fields[3].isBlank() || fields[3].length > 120 ||
            fields[3].any { it.isISOControl() }
        ) return ZygiskOverview()
        val old = metadata[fields[1]] ?: ZygiskModuleVersion()
        metadata[fields[1]] = if (fields[2] == "installed") old.copy(installed = fields[3]) else old.copy(staged = fields[3])
    }
    val (flags, monitor) = parseZygiskFlags(modules + monitors)
    return ZygiskOverview(available = true, status = zygiskStatusOf(flags, monitor), versions = metadata)
}

internal fun zygiskOverviewScript(): String = buildString {
    // A denied directory must not look like three absent modules. Do not 'exit' a shared shell.
    appendLine("if [ -d /data/adb/modules ] && [ -r /data/adb/modules ] && [ -x /data/adb/modules ] && { [ ! -e /data/adb/modules_update ] || { [ -d /data/adb/modules_update ] && [ -r /data/adb/modules_update ] && [ -x /data/adb/modules_update ]; }; }; then")
    append(zygiskStatusScript())
    ZygiskModule.entries.forEach { module ->
        listOf("installed" to "modules", "staged" to "modules_update").forEach { (kind, directory) ->
            // Read data, never source module.prop; cap both input and the displayed value.
            appendLine("v=\$(head -c 8192 /data/adb/$directory/${module.moduleId}/module.prop 2>/dev/null | sed -n '/^version=/{s/^version=//;p;q;}' | head -c 120 | tr -d '\\r')")
            appendLine("[ -z \"\$v\" ] || printf 'version ${module.moduleId} $kind %s\\n' \"\$v\"")
        }
    }
    appendLine("printf '$OVERVIEW_END\\n'")
    appendLine("else false; fi")
}
