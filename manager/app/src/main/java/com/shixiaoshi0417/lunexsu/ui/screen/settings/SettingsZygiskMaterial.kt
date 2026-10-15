package com.shixiaoshi0417.lunexsu.ui.screen.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveDialog
import com.shixiaoshi0417.lunexsu.ui.component.material.FolkTextButton
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedColumn
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedDropdownItem
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedSwitchItem

/**
 * The built-in Zygisk card. Under a FinaSU theme the segmented group and the dialog take the
 * pack's palette and wallpaper overlay, like the rest of the settings page.
 */
@Composable
internal fun ZygiskCardMaterial(uiState: SettingsUiState, actions: SettingsScreenActions) {
    val choices = uiState.zygiskBackends
    SegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        content = listOf(
            {
                SegmentedSwitchItem(
                    icon = Icons.Filled.Hub,
                    title = stringResource(id = R.string.settings_zygisk),
                    summary = zygiskSummary(uiState),
                    enabled = uiState.zygiskSwitchEnabled,
                    checked = uiState.zygiskEnabled,
                    onCheckedChange = actions.onSetZygiskEnabled
                )
            },
            {
                SegmentedDropdownItem(
                    icon = Icons.Filled.SwapHoriz,
                    title = stringResource(id = R.string.settings_zygisk_backend),
                    summary = zygiskBackendSummary(uiState),
                    items = choices.ifEmpty { listOf(uiState.zygiskSelected) }.map { it.displayName },
                    enabled = uiState.zygiskBackendEnabled,
                    selectedIndex = choices.indexOf(uiState.zygiskSelected).coerceAtLeast(0),
                    onItemSelected = { index -> choices.getOrNull(index)?.let(actions.onSetZygiskBackend) }
                )
            },
            {
                SegmentedSwitchItem(
                    icon = Icons.Filled.Extension,
                    title = stringResource(R.string.settings_zygisk_nextnext),
                    summary = zygiskNextNextSummary(uiState),
                    enabled = uiState.zygiskNextNextEnabled,
                    checked = uiState.zygiskNextNext,
                    onCheckedChange = actions.onSetZygiskNextNext,
                )
            },
        )
    )
    ZygiskApplyingDialog(uiState.zygiskBusy)
    ZygiskRebootDialogMaterial(uiState.showZygiskRebootDialog, actions)
}

@Composable
private fun ZygiskRebootDialogMaterial(show: Boolean, actions: SettingsScreenActions) {
    if (!show) return
    ExpressiveDialog(
        onDismissRequest = actions.onZygiskRebootLater,
        icon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_zygisk_reboot_title)) },
        text = { Text(stringResource(R.string.settings_zygisk_reboot_message)) },
        confirmButton = {
            FolkTextButton(onClick = actions.onZygiskRebootNow) {
                Text(stringResource(R.string.settings_zygisk_reboot_now))
            }
        },
        dismissButton = {
            FolkTextButton(onClick = actions.onZygiskRebootLater) {
                Text(stringResource(R.string.settings_zygisk_reboot_later))
            }
        },
    )
}
