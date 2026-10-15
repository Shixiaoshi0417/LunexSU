package com.shixiaoshi0417.lunexsu.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ZygiskCardMiuix(uiState: SettingsUiState, actions: SettingsScreenActions) {
    val choices = uiState.zygiskBackends
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        SwitchPreference(
            title = stringResource(id = R.string.settings_zygisk),
            summary = zygiskSummary(uiState),
            startAction = {
                Icon(
                    Icons.Rounded.Hub,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = stringResource(id = R.string.settings_zygisk),
                    tint = if (uiState.zygiskSwitchEnabled) colorScheme.onBackground else colorScheme.disabledOnSecondaryVariant
                )
            },
            enabled = uiState.zygiskSwitchEnabled,
            checked = uiState.zygiskEnabled,
            onCheckedChange = actions.onSetZygiskEnabled
        )
        OverlayDropdownPreference(
            title = stringResource(id = R.string.settings_zygisk_backend),
            summary = zygiskBackendSummary(uiState),
            items = choices.ifEmpty { listOf(uiState.zygiskSelected) }.map { it.displayName },
            startAction = {
                Icon(
                    Icons.Rounded.SwapHoriz,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = stringResource(id = R.string.settings_zygisk_backend),
                    tint = if (uiState.zygiskBackendEnabled) colorScheme.onBackground else colorScheme.disabledOnSecondaryVariant
                )
            },
            enabled = uiState.zygiskBackendEnabled,
            selectedIndex = choices.indexOf(uiState.zygiskSelected).coerceAtLeast(0),
            onSelectedIndexChange = { index -> choices.getOrNull(index)?.let(actions.onSetZygiskBackend) }
        )
        SwitchPreference(
            title = stringResource(R.string.settings_zygisk_nextnext),
            summary = zygiskNextNextSummary(uiState),
            startAction = {
                Icon(
                    Icons.Rounded.Extension,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = null,
                    tint = if (uiState.zygiskNextNextEnabled) colorScheme.onBackground else colorScheme.disabledOnSecondaryVariant,
                )
            },
            enabled = uiState.zygiskNextNextEnabled,
            checked = uiState.zygiskNextNext,
            onCheckedChange = actions.onSetZygiskNextNext,
        )
    }
    ZygiskApplyingDialog(uiState.zygiskBusy)
    WindowDialog(
        show = uiState.showZygiskRebootDialog,
        title = stringResource(R.string.settings_zygisk_reboot_title),
        onDismissRequest = actions.onZygiskRebootLater,
    ) {
        Text(stringResource(R.string.settings_zygisk_reboot_message))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.padding(top = 16.dp)) {
            TextButton(
                text = stringResource(R.string.settings_zygisk_reboot_later),
                onClick = actions.onZygiskRebootLater,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.settings_zygisk_reboot_now),
                onClick = actions.onZygiskRebootNow,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
