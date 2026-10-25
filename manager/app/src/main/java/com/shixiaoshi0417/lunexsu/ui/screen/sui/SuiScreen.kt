package com.shixiaoshi0417.lunexsu.ui.screen.sui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.sui.*
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.navigation3.LocalNavigator
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.AlertDialog
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.RadioButton
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.TextButton

/** Permission management only; the Sui switch lives in Settings next to built-in Zygisk. */
@Composable
fun SuiScreen() {
    val ui by SuiManager.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<SuiApp?>(null) }
    LaunchedEffect(Unit) { SuiManager.refresh() }
    val apps = remember(ui.apps, query) {
        ui.apps.filter { query.isBlank() || it.label.contains(query, true) || it.packages.contains(query, true) }
    }
    val editable = ui.connected && !ui.busy && !ui.reconciling
    val search: @Composable (Modifier) -> Unit = { modifier -> SuiSearchField(query, { query = it.take(256) }, modifier) }
    if (LocalUiMode.current == UiMode.Miuix) {
        SuiScreenMiuix(ui, apps, editable, { navigator.pop() }, { SuiManager.refresh() }, { editing = it }, search)
    } else {
        SuiScreenMaterial(ui, apps, editable, { navigator.pop() }, { SuiManager.refresh() }, { editing = it }, search)
    }
    editing?.let { app ->
        var selected by remember(app) { mutableStateOf(app.permission) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.sui_apps)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                // List previews are bounded; keep the complete shared-UID identity scrollable here.
                Text(app.label)
                Text("UID ${app.uid}\n${app.packages}", Modifier.padding(top = 4.dp))
                Text(stringResource(R.string.sui_shared_uid), Modifier.padding(top = 8.dp, bottom = 4.dp))
                SuiPermission.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected = selected == mode, role = Role.RadioButton,
                        onClick = { selected = mode }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == mode, onClick = null)
                        Text(stringResource(mode.label()), Modifier.padding(start = 12.dp))
                    }
                }
            } }, confirmButton = { TextButton(enabled = editable, onClick = {
                editing = null; SuiManager.setPermission(app, selected)
            }) { Text(stringResource(R.string.sui_apply)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.sui_cancel)) } })
    }
}

/** Shared by the Settings switch and the management page, in every UI style. */
@Composable
internal fun suiStatusText(ui: SuiUiState): String = stringResource(when {
    ui.reconciling -> R.string.sui_outcome_pending
    ui.status.pendingReboot -> R.string.sui_pending_reboot
    ui.status.serviceHealthy && ui.connected -> R.string.sui_ready
    ui.status.desiredEnabled -> R.string.sui_unavailable
    !ui.status.payloadAvailable -> R.string.sui_payload_missing
    else -> R.string.sui_disabled
})

/** Both directions explain the reboot boundary; the request is rechecked when confirmed. */
@Composable
internal fun SuiSwitchDialog(enable: Boolean?, ui: SuiUiState, onDismiss: () -> Unit) {
    if (enable == null) return
    LaunchedEffect(enable) { SuiManager.refresh(false) }
    if (enable && !ui.status.zygisk.ready) {
        AlertDialog(onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.sui_zygisk_required)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(suiZygiskText(ui))
                Text(stringResource(R.string.sui_zygisk_instructions), Modifier.padding(top = 12.dp))
            } },
            confirmButton = { TextButton(enabled = !ui.busy && !ui.reconciling,
                onClick = { SuiManager.refresh(false) }) { Text(stringResource(R.string.sui_refresh)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sui_cancel)) } })
        return
    }
    val action = stringResource(if (enable) R.string.sui_enable else R.string.sui_disable)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(action) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(if (enable) R.string.sui_enable_warning else R.string.sui_disable_warning))
        } },
        confirmButton = { TextButton(enabled = ui.canSwitchTo(enable), onClick = {
            onDismiss()
            if (SuiManager.state.value.canSwitchTo(enable)) {
                if (enable) SuiManager.enable() else SuiManager.disable()
            }
        }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sui_cancel)) } })
}

@Composable
internal fun suiZygiskText(ui: SuiUiState): String = stringResource(when (ui.status.zygisk.state) {
    "missing" -> R.string.sui_zygisk_missing
    "pending_reboot" -> R.string.sui_zygisk_pending
    "conflict" -> R.string.sui_zygisk_conflict
    else -> R.string.sui_zygisk_unavailable
})

@Composable
private fun SuiSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier) {
    if (LocalUiMode.current == UiMode.Miuix) {
        top.yukonga.miuix.kmp.basic.TextField(value = query, onValueChange = onQueryChange,
            modifier = modifier.fillMaxWidth(), label = stringResource(R.string.sui_search), singleLine = true)
    } else {
        androidx.compose.material3.OutlinedTextField(value = query, onValueChange = onQueryChange,
            modifier = modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text(stringResource(R.string.sui_search)) })
    }
}

internal fun SuiPermission.label() = when (this) {
    SuiPermission.ASK -> R.string.sui_ask
    SuiPermission.ROOT -> R.string.sui_root
    SuiPermission.SHELL -> R.string.sui_shell
    SuiPermission.DENY -> R.string.sui_deny
    SuiPermission.HIDDEN -> R.string.sui_hidden
}
