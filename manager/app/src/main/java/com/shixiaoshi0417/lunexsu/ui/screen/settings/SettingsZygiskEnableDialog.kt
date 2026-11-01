package com.shixiaoshi0417.lunexsu.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveDialog
import com.shixiaoshi0417.lunexsu.ui.component.material.FolkTextButton
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ZygiskEnableDialog(state: SettingsUiState, actions: SettingsScreenActions) {
    if (!state.showZygiskEnableDialog) return
    val title = stringResource(R.string.settings_zygisk_enable_title)
    val message = stringResource(R.string.settings_zygisk_enable_warning)
    val conflict = state.zygiskStatus?.conflictFor(state.zygiskSelected)
    val conflictMessage = if (state.zygiskStatus?.legacyVex == true) {
        stringResource(R.string.settings_zygisk_legacy_vex)
    } else conflict?.let { stringResource(R.string.settings_zygisk_conflict, it) }
    val canEnable = state.canConfirmZygiskEnable
    val continueText = stringResource(R.string.settings_zygisk_enable_confirm)
    if (LocalUiMode.current == UiMode.Miuix) {
        WindowDialog(show = true, title = title, onDismissRequest = actions.onDismissZygiskEnable) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                top.yukonga.miuix.kmp.basic.Text(message)
                if (conflictMessage != null) top.yukonga.miuix.kmp.basic.Text(conflictMessage)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(stringResource(android.R.string.cancel), onClick = actions.onDismissZygiskEnable,
                    modifier = Modifier.weight(1f))
                TextButton(continueText, onClick = actions.onConfirmZygiskEnable, enabled = canEnable,
                    modifier = Modifier.weight(1f))
            }
        }
    } else {
        // The common Expressive dialog also retains the Fina wallpaper/theme overlay.
        ExpressiveDialog(
            onDismissRequest = actions.onDismissZygiskEnable,
            title = { Text(title) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message)
                    if (conflictMessage != null) Text(conflictMessage)
                }
            },
            dismissButton = {
                FolkTextButton(onClick = actions.onDismissZygiskEnable) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                FolkTextButton(onClick = actions.onConfirmZygiskEnable, enabled = canEnable) { Text(continueText) }
            },
        )
    }
}
