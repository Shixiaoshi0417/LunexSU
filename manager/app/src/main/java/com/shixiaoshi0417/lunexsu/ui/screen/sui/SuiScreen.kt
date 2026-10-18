package com.shixiaoshi0417.lunexsu.ui.screen.sui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shixiaoshi0417.lunexsu.BuildConfig
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.sui.*
import com.shixiaoshi0417.lunexsu.ui.navigation3.LocalNavigator
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.AlertDialog
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.OutlinedTextField
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.SegmentedColumn
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.SegmentedListItem
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.SusfsScaffold
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.TextButton

@Composable
fun SuiScreen() {
    val ui by SuiManager.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<SuiApp?>(null) }
    var confirmEnable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { SuiManager.refresh() }
    SusfsScaffold(ui.busy, { navigator.pop() }, { SuiManager.refresh() }, title = stringResource(R.string.sui_title)) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)
            .navigationBarsPadding().imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SegmentedColumn(title = stringResource(R.string.sui_status)) {
                    item { SegmentedListItem(headlineContent = { Text(stringResource(R.string.sui_payload_version, ui.status.version.ifEmpty { "Sui" })) },
                        supportingContent = { Text(stringResource(when {
                            ui.status.pendingReboot -> R.string.sui_pending_reboot
                            ui.status.serviceHealthy && ui.hostAttached -> R.string.sui_ready
                            ui.status.desiredEnabled -> R.string.sui_unavailable
                            else -> R.string.sui_disabled
                        })) }) }
                    item { Text(stringResource(R.string.sui_separate_permissions), Modifier.padding(16.dp)) }
                    if (ui.status.reason.isNotBlank()) item {
                        Text(ui.status.reason, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { TextButton(enabled = !ui.busy && ui.status.payloadAvailable && !ui.status.desiredEnabled,
                        onClick = { confirmEnable = true }) { Text(stringResource(R.string.sui_enable)) } }
                    item { TextButton(enabled = !ui.busy, onClick = SuiManager::disable) { Text(stringResource(R.string.sui_disable)) } }
                    item { Text(stringResource(R.string.sui_disable_warning), Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (BuildConfig.NO_ROOT_DEMO) item { Text(stringResource(R.string.sui_demo)) }
            ui.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            item {
                OutlinedTextField(query, { query = it.take(256) }, Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text(stringResource(R.string.sui_search)) })
            }
            if (!ui.hostAttached) item { Text(stringResource(R.string.sui_host_required)) }
            items(ui.apps.filter { query.isBlank() || it.label.contains(query, true) || it.packages.contains(query, true) }, key = { it.uid }) { app ->
                SegmentedColumn(title = app.label) {
                    item { SegmentedListItem(enabled = ui.hostAttached && !ui.busy, onClick = { editing = app },
                        headlineContent = { Text(app.packages) }, supportingContent = {
                            Text("UID ${app.uid} · ${stringResource(app.permission.label())}")
                        }) }
                }
            }
        }
    }
    if (confirmEnable) AlertDialog(onDismissRequest = { confirmEnable = false },
        title = { Text(stringResource(R.string.sui_enable)) },
        text = { Text(stringResource(R.string.sui_enable_warning)) },
        confirmButton = { TextButton(onClick = { confirmEnable = false; SuiManager.enable() }) {
            Text(stringResource(R.string.sui_enable)) } },
        dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text(stringResource(R.string.sui_cancel)) } })
    editing?.takeIf { ui.requests.isEmpty() }?.let { app ->
        var selected by remember(app) { mutableStateOf(app.permission) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(app.label) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.sui_shared_uid))
                SuiPermission.entries.forEach { mode -> TextButton(onClick = { selected = mode }) {
                    Text((if (selected == mode) "✓ " else "") + stringResource(mode.label()))
                } }
            } }, confirmButton = { TextButton(onClick = {
                editing = null; SuiManager.setPermission(app, selected)
            }) { Text(stringResource(R.string.sui_apply)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.sui_cancel)) } })
    }
}

@Composable
fun SuiPermissionPrompt() {
    val state by SuiManager.state.collectAsStateWithLifecycle()
    val request = state.requests.firstOrNull() ?: return
    var decision by remember(request) { mutableStateOf(SuiDecision.DENY_ONCE) }
    AlertDialog(onDismissRequest = { SuiManager.decide(request, SuiDecision.DENY_ONCE) },
        title = { Text(stringResource(R.string.sui_permission_request)) },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("${request.packageName}\nUID ${request.uid} · PID ${request.pid}")
            Text(stringResource(R.string.sui_separate_permissions))
            SuiDecision.entries.forEach { option -> TextButton(onClick = { decision = option }) {
                Text((if (decision == option) "✓ " else "") + stringResource(when (option) {
                    SuiDecision.ROOT -> R.string.sui_root
                    SuiDecision.ROOT_ONCE -> R.string.sui_root_once
                    SuiDecision.SHELL -> R.string.sui_shell
                    SuiDecision.DENY -> R.string.sui_deny
                    SuiDecision.DENY_ONCE -> R.string.sui_deny_once
                }))
            } }
        } }, confirmButton = { TextButton(onClick = { SuiManager.decide(request, decision) }) { Text(stringResource(R.string.sui_apply)) } })
}

private fun SuiPermission.label() = when (this) {
    SuiPermission.ASK -> R.string.sui_ask
    SuiPermission.ROOT -> R.string.sui_root
    SuiPermission.SHELL -> R.string.sui_shell
    SuiPermission.DENY -> R.string.sui_deny
    SuiPermission.HIDDEN -> R.string.sui_hidden
}
