// Adapted from LyraVoid/FinaSU f80852b9 (GPL-3.0); see docs/lunexsu-susfs.md.
package com.shixiaoshi0417.lunexsu.ui.screen.susfs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.susfs.SusfsInputs
import com.shixiaoshi0417.lunexsu.data.susfs.SusfsStatus
import com.shixiaoshi0417.lunexsu.ui.component.material.*
import com.shixiaoshi0417.lunexsu.ui.viewmodel.SusfsViewModel
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun SusfsBasicPage(config: JSONObject?, status: SusfsStatus, writable: Boolean, vm: SusfsViewModel) {
    var selected by remember { mutableStateOf<String?>(null) }
    val options = listOf(Triple("hide_mounts", R.string.susfs_hide_mounts, status.supports("SUS_MOUNT")),
        Triple("logging", R.string.susfs_logging, status.supports("ENABLE_LOG")),
        Triple("avc_spoof", R.string.susfs_avc, status.proc("susfs_avc_spoof") ||
            (status.implementation != "lkm" && status.supports("AVC_LOG_SPOOFING"))))
    SegmentedColumn(title = stringResource(R.string.susfs_settings)) {
        options.forEach { (key, title, supported) -> item(key = key) {
            val settings = config?.optJSONObject("settings")
            val mode = if (settings == null || settings.isNull(key)) 0 else if (settings.optBoolean(key)) 1 else 2
            SegmentedListItem(enabled = writable && supported, onClick = { selected = key },
                headlineContent = { Text(stringResource(title)) },
                supportingContent = { Text(susfsDisabledReason(status, supported) ?: stringResource(R.string.susfs_three_state_hint)) },
                trailingContent = { Text(stringResource(listOf(R.string.susfs_unmanaged, R.string.susfs_on, R.string.susfs_off)[mode]),
                    color = MaterialTheme.colorScheme.primary) })
        } }
    }
    selected?.let { key ->
        val option = options.first { it.first == key }
        val settings = config?.optJSONObject("settings")
        val current = if (settings == null || settings.isNull(key)) 0 else if (settings.optBoolean(key)) 1 else 2
        SusfsChoiceDialog(stringResource(option.second), listOf(R.string.susfs_unmanaged, R.string.susfs_on, R.string.susfs_off),
            current, writable && option.third, { selected = null }) { index ->
            vm.change { it.getJSONObject("settings").put(key, when (index) { 1 -> true; 2 -> false; else -> JSONObject.NULL }) }
            selected = null
        }
    }
}

@Composable
internal fun SusfsUnameSection(config: JSONObject?, status: SusfsStatus, writable: Boolean, vm: SusfsViewModel) {
    var editing by remember { mutableStateOf(false) }
    val uname = config?.optJSONObject("settings")?.optJSONObject("uname")
    val supported = status.supports("SPOOF_UNAME")
    SegmentedColumn(title = stringResource(R.string.susfs_uname)) {
        item { SegmentedListItem(enabled = writable && supported, onClick = { editing = true },
            headlineContent = { Text(stringResource(R.string.susfs_uname)) },
            supportingContent = { Text(susfsDisabledReason(status, supported) ?: if (uname == null)
                stringResource(R.string.susfs_unmanaged) else
                "${stringResource(R.string.susfs_release)}: ${uname.optString("release")}\n${stringResource(R.string.susfs_version)}: ${uname.optString("version")}") },
            leadingContent = { Icon(Icons.Rounded.Tune, null) }) }
        item { SegmentedListItem(enabled = writable && supported, onClick = {
            vm.change { it.getJSONObject("settings").put("uname", JSONObject().put("release", "default").put("version", "default")) }
        }, headlineContent = { Text(stringResource(R.string.susfs_restore_uname)) },
            supportingContent = { Text(stringResource(R.string.susfs_restore_hint)) }) }
    }
    if (editing) {
        var release by remember { mutableStateOf(uname?.optString("release") ?: "default") }
        var version by remember { mutableStateOf(uname?.optString("version") ?: "default") }
        val valid = SusfsInputs.validText(release, 65) && SusfsInputs.validText(version, 65)
        SusfsForm(stringResource(R.string.susfs_uname), { editing = false }, writable && valid, {
            vm.change { it.getJSONObject("settings").put("uname", JSONObject().put("release", release).put("version", version)) }
            editing = false
        }) {
            Text(stringResource(R.string.susfs_uname_hint))
            OutlinedTextField(release, { release = it }, label = stringResource(R.string.susfs_release),
                modifier = Modifier.fillMaxWidth(), singleLine = true, isError = !SusfsInputs.validText(release, 65))
            TextButton(onClick = { release = "default" }) { Text(stringResource(R.string.susfs_use_real_field)) }
            OutlinedTextField(version, { version = it }, label = stringResource(R.string.susfs_version),
                modifier = Modifier.fillMaxWidth(), singleLine = true, isError = !SusfsInputs.validText(version, 65))
            TextButton(onClick = { version = "default" }) { Text(stringResource(R.string.susfs_use_real_field)) }
            if (!valid) Text(stringResource(R.string.susfs_uname_invalid), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun SusfsCmdlineSection(config: JSONObject?, status: SusfsStatus, writable: Boolean, vm: SusfsViewModel) {
    var editing by remember { mutableStateOf(false) }
    val settings = config?.optJSONObject("settings")
    val value = if (settings == null || settings.isNull("cmdline")) "" else settings.optString("cmdline")
    val supported = status.supports("SPOOF_CMDLINE_OR_BOOTCONFIG")
    SegmentedColumn(title = stringResource(R.string.susfs_cmdline)) {
        item { SegmentedListItem(enabled = writable && supported, onClick = { editing = true },
            headlineContent = { Text(stringResource(R.string.susfs_cmdline)) },
            supportingContent = {
                val reason = susfsDisabledReason(status, supported)
                if (reason != null) Text(reason)
                else Text(value.ifEmpty { stringResource(R.string.susfs_unmanaged) },
                    maxLines = if (writable && supported) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            }) }
    }
    if (editing) {
        var text by remember { mutableStateOf(value) }
        val valid = SusfsInputs.validText(text, 8192)
        SusfsForm(stringResource(R.string.susfs_cmdline), { editing = false }, writable && valid, {
            vm.change { it.getJSONObject("settings").put("cmdline", text) }; editing = false
        }) {
            Text(stringResource(R.string.susfs_cmdline_hint))
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                label = stringResource(R.string.susfs_cmdline), isError = !valid)
            if (!valid) Text(stringResource(R.string.susfs_text_invalid), color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun ruleTitle(kind: String) = when (kind) {
    "path" -> R.string.susfs_path_rules
    "map" -> R.string.susfs_map_rules
    "kstat" -> R.string.susfs_kstat_rules
    else -> R.string.susfs_redirect_rules
}

@Composable
internal fun SusfsRuleSection(kind: String, config: JSONObject?, status: SusfsStatus, writable: Boolean, vm: SusfsViewModel) {
    var editing by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val capability = when (kind) { "path" -> "SUS_PATH"; "map" -> "SUS_MAP"; "kstat" -> "SUS_KSTAT"; else -> "OPEN_REDIRECT" }
    val supported = status.supports(capability)
    val hint = when (kind) { "path" -> R.string.susfs_path_hint; "map" -> R.string.susfs_map_hint
        "kstat" -> R.string.susfs_kstat_hint; else -> R.string.susfs_redirect_hint }
    val rules = config?.optJSONArray("rules") ?: JSONArray()
    val entries = (0 until rules.length()).map { rules.getJSONObject(it) }.filter { it.getString("kind") == kind }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SegmentedColumn(title = stringResource(ruleTitle(kind))) {
            item { SegmentedListItem(enabled = writable && supported, onClick = { original = null; editing = true },
                headlineContent = { Text(stringResource(R.string.susfs_add_rule)) },
                supportingContent = { Text(susfsDisabledReason(status, supported) ?: stringResource(hint)) },
                leadingContent = { Icon(Icons.Rounded.Add, null) }) }
            entries.forEach { rule -> item(key = rule.getString("path")) {
                SegmentedListItem(enabled = writable && supported, onClick = { original = rule.toString(); editing = true },
                    headlineContent = { Text(rule.getString("path"),
                        maxLines = if (writable && supported) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        if (kind == "redirect") Column {
                            Text("→ ${rule.getString("destination")}",
                                maxLines = if (writable && supported) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                            Text(susfsUidLabel(rule.optInt("uid_scheme")))
                        } else Text(stringResource(when (kind) {
                            "path" -> if (rule.optBoolean("looping")) R.string.susfs_loop else R.string.susfs_path_once
                            "map" -> R.string.susfs_map_hint
                            else -> if (rule.optBoolean("full_clone")) R.string.susfs_clone else R.string.susfs_kstat_normal
                        }))
                    }, leadingContent = { Icon(when (kind) { "path" -> Icons.Rounded.FolderOff
                        "map" -> Icons.Rounded.Memory; "kstat" -> Icons.Rounded.DataObject; else -> Icons.AutoMirrored.Rounded.AltRoute }, null) },
                    trailingContent = { SusfsRemoveButton(enabled = writable, onClick = { deleting = rule.toString() }) })
            } }
            if (entries.isEmpty()) item { SegmentedListItem(headlineContent = { Text(stringResource(R.string.susfs_empty)) }) }
        }
        if (entries.isNotEmpty()) Text(stringResource(R.string.susfs_removal_hint),
            modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
    if (editing) SusfsRuleDialog(kind, original, status, writable && supported, { editing = false }) { replacement ->
        val expected = original
        vm.change { SusfsInputs.replaceRule(it, expected, replacement) }
        editing = false
    }
    deleting?.let { text -> SusfsDeleteDialog(JSONObject(text).getString("path"), writable, { deleting = null }) {
        vm.change { SusfsInputs.replaceRule(it, text, null) }; deleting = null
    } }
}

@Composable
private fun susfsUidLabel(uid: Int): String = stringResource(when (uid) {
    0 -> R.string.susfs_uid_non_app; 1 -> R.string.susfs_uid_root; 2 -> R.string.susfs_uid_non_su
    3 -> R.string.susfs_uid_builtin_3; 4 -> R.string.susfs_uid_builtin_4
    else -> R.string.susfs_uid_unsupported
})

@Composable
private fun SusfsRuleDialog(kind: String, original: String?, status: SusfsStatus, writable: Boolean,
    dismiss: () -> Unit, save: (JSONObject) -> Unit) {
    val rule = remember(original) { original?.let(::JSONObject) }
    var path by remember { mutableStateOf(rule?.optString("path").orEmpty()) }
    var destination by remember { mutableStateOf(rule?.optString("destination").orEmpty()) }
    var looping by remember { mutableStateOf(rule?.optBoolean("looping") ?: false) }
    var clone by remember { mutableStateOf(rule?.optBoolean("full_clone") ?: false) }
    var uid by remember { mutableIntStateOf(rule?.optInt("uid_scheme") ?: 0) }
    val cloneSupported = status.implementation != "lkm" || status.proc("susfs_kstat")
    val uidSchemes = SusfsInputs.redirectUidSchemes(status)
    val valid = SusfsInputs.validPath(path) && (kind != "redirect" ||
        SusfsInputs.validRedirect(path, destination, uid, status)) &&
        (kind != "kstat" || !clone || cloneSupported)
    SusfsForm(stringResource(if (original == null) R.string.susfs_add_rule else R.string.susfs_edit_rule), dismiss, writable && valid, {
        val result = JSONObject().put("kind", kind).put("path", path)
        when (kind) { "path" -> result.put("looping", looping)
            "kstat" -> result.put("full_clone", clone)
            "redirect" -> result.put("destination", destination).put("uid_scheme", uid) }
        save(result)
    }) {
        OutlinedTextField(path, { path = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = stringResource(R.string.susfs_path), placeholder = { Text("/data/local/tmp/example") },
            isError = path.isNotEmpty() && !SusfsInputs.validPath(path))
        if (!SusfsInputs.validPath(path)) Text(stringResource(R.string.susfs_path_invalid), style = MaterialTheme.typography.bodySmall)
        if (kind == "redirect") {
            OutlinedTextField(destination, { destination = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = stringResource(R.string.susfs_destination), isError = destination.isNotEmpty() &&
                    (!SusfsInputs.validPath(destination) || destination == path))
            if (destination.isNotEmpty() && !SusfsInputs.validPath(destination)) {
                Text(stringResource(R.string.susfs_path_invalid), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.susfs_uid_selection), style = MaterialTheme.typography.titleSmall)
            SegmentedColumn { uidSchemes.forEach { value -> item {
                SegmentedListItem(selected = uid == value, enabled = writable, onClick = { uid = value },
                    headlineContent = { Text(susfsUidLabel(value)) },
                    trailingContent = { RadioButton(uid == value, null, enabled = writable) })
            } } }
            if (uid !in uidSchemes) Text(stringResource(R.string.susfs_uid_unsupported), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.susfs_uid_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (kind == "path") {
            SegmentedColumn { item { SegmentedSwitchItem(title = stringResource(R.string.susfs_loop),
                summary = stringResource(R.string.susfs_loop_hint), checked = looping, onCheckedChange = { looping = it }) } }
        }
        if (kind == "kstat") {
            Text(stringResource(R.string.susfs_kstat_hint))
            SegmentedColumn { item { SegmentedSwitchItem(title = stringResource(R.string.susfs_clone),
                summary = stringResource(if (cloneSupported) R.string.susfs_clone_hint else R.string.susfs_proc_hint),
                checked = clone, enabled = cloneSupported || clone, onCheckedChange = { clone = it && cloneSupported }) } }
        }
        if (kind == "map") Text(stringResource(R.string.susfs_map_hint))
        Text(stringResource(R.string.susfs_removal_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SusfsExtensionsPage(config: JSONObject?, status: SusfsStatus, writable: Boolean, vm: SusfsViewModel) {
    if (status.implementation != "lkm") {
        SegmentedColumn(title = stringResource(R.string.susfs_lkm)) { item { SegmentedListItem(
            headlineContent = { Text(stringResource(R.string.susfs_lkm_only)) },
            supportingContent = { Text(stringResource(R.string.susfs_lkm_only_hint)) }) } }
        return
    }
    SusfsExtensionSection("hidden_modules", R.string.susfs_modules, "susfs_hide_modules", config, status, writable, vm)
    SusfsExtensionSection("mount_prefixes", R.string.susfs_prefixes, "susfs_hide_mounts", config, status, writable, vm)
}

@Composable
private fun SusfsExtensionSection(key: String, title: Int, node: String, config: JSONObject?, status: SusfsStatus,
    writable: Boolean, vm: SusfsViewModel) {
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val enabled = writable && status.proc(node)
    val array = config?.optJSONObject("lkm")?.optJSONArray(key) ?: JSONArray()
    SegmentedColumn(title = stringResource(title)) {
        item { SegmentedListItem(enabled = enabled, onClick = { adding = true },
            headlineContent = { Text(stringResource(R.string.susfs_add_entry)) },
            supportingContent = { Text(susfsDisabledReason(status, true) ?: stringResource(if (!status.proc(node))
                R.string.susfs_proc_hint else if (key == "hidden_modules") R.string.susfs_module_hint else R.string.susfs_prefix_hint)) },
            leadingContent = { Icon(Icons.Rounded.Add, null) }) }
        for (index in 0 until array.length()) item(key = array.getString(index)) {
            val value = array.getString(index)
            SegmentedListItem(headlineContent = { Text(value,
                maxLines = if (writable) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis) }, trailingContent = {
                SusfsRemoveButton(enabled = writable, onClick = { deleting = value })
            })
        }
        if (array.length() == 0) item { SegmentedListItem(headlineContent = { Text(stringResource(R.string.susfs_empty)) }) }
    }
    if (adding) {
        var value by remember { mutableStateOf("") }
        val valid = if (key == "hidden_modules") value.length in 1..63 && value.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_'
        } else SusfsInputs.validPath(value)
        SusfsForm(stringResource(title), { adding = false }, enabled && valid, {
            vm.change {
                val current = it.getJSONObject("lkm").getJSONArray(key)
                require((0 until current.length()).none { index -> current.getString(index) == value }) { "Duplicate entry" }
                current.put(value)
            }; adding = false
        }) {
            OutlinedTextField(value, { value = it }, label = stringResource(title), singleLine = true,
                modifier = Modifier.fillMaxWidth(), isError = value.isNotEmpty() && !valid)
            Text(stringResource(if (key == "hidden_modules") R.string.susfs_module_hint else R.string.susfs_path_invalid))
        }
    }
    deleting?.let { value -> SusfsDeleteDialog(value, writable, { deleting = null }) {
        vm.change {
            val current = it.getJSONObject("lkm").getJSONArray(key)
            val index = (0 until current.length()).firstOrNull { index -> current.getString(index) == value }
                ?: error("Entry changed elsewhere; refresh before editing")
            current.remove(index)
        }; deleting = null
    } }
}

@Composable
private fun SusfsChoiceDialog(title: String, choices: List<Int>, selected: Int, enabled: Boolean,
    dismiss: () -> Unit, choose: (Int) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        SegmentedColumn(modifier = Modifier.verticalScroll(rememberScrollState())) { choices.forEachIndexed { index, label -> item {
            SegmentedListItem(selected = selected == index, enabled = enabled, onClick = { choose(index) },
                headlineContent = { Text(stringResource(label)) }, trailingContent = { RadioButton(selected == index, null, enabled = enabled) })
        } } }
    }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.susfs_cancel)) } })
}

@Composable
private fun SusfsDeleteDialog(value: String, enabled: Boolean, dismiss: () -> Unit, delete: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.susfs_remove)) },
        text = { Text("$value\n\n${stringResource(R.string.susfs_removal_hint)}",
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(enabled = enabled, onClick = delete) { Text(stringResource(R.string.susfs_remove)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.susfs_cancel)) } })
}

@Composable
private fun SusfsForm(title: String, dismiss: () -> Unit, valid: Boolean, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }, confirmButton = { TextButton(enabled = valid, onClick = save) { Text(stringResource(R.string.susfs_save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.susfs_cancel)) } })
}
