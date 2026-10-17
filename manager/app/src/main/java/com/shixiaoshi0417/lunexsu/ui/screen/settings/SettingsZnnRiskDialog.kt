package com.shixiaoshi0417.lunexsu.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveDialog
import com.shixiaoshi0417.lunexsu.ui.component.material.FolkTextButton
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedCheckboxItem
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.window.WindowDialog

// A safety color, deliberately independent of theme-pack or user-selected accent colors.
private val ZnnDangerRed = Color(0xFFB3261E)

@Composable
internal fun ZnnRiskDialog(state: SettingsUiState, actions: SettingsScreenActions) {
    if (!state.showZnnRiskDialog) return
    val title = stringResource(R.string.settings_zygisk_nextnext_risk_title)
    val message = stringResource(R.string.settings_zygisk_nextnext_risk_message)
    val recovery = stringResource(R.string.settings_zygisk_nextnext_risk_recovery)
    val recoveryHint = stringResource(R.string.settings_zygisk_nextnext_risk_recovery_hint)
    val continueText = if (state.znnRiskSecondsRemaining > 0) {
        stringResource(R.string.settings_zygisk_nextnext_risk_countdown, state.znnRiskSecondsRemaining)
    } else {
        stringResource(R.string.settings_zygisk_nextnext_risk_continue)
    }
    if (LocalUiMode.current == UiMode.Miuix) {
        WindowDialog(show = true, title = title, onDismissRequest = actions.onDismissZnnRisk) {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                top.yukonga.miuix.kmp.basic.Text(message)
                CheckboxPreference(
                    title = recovery, summary = recoveryHint, checked = state.znnRecoveryConfirmed,
                    onCheckedChange = actions.onSetZnnRecoveryConfirmed,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    stringResource(android.R.string.cancel), onClick = actions.onDismissZnnRisk,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    continueText, onClick = actions.onConfirmZnnRisk, enabled = state.canConfirmZnnRisk,
                    modifier = Modifier.weight(1f),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors(
                        color = ZnnDangerRed, textColor = Color.White,
                    ),
                )
            }
        }
    } else {
        // ExpressiveDialog also preserves the dedicated Fina dialog theme and wallpaper overlay.
        ExpressiveDialog(
            onDismissRequest = actions.onDismissZnnRisk,
            title = { Text(title) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(message)
                    SegmentedCheckboxItem(
                        title = recovery, summary = recoveryHint, checked = state.znnRecoveryConfirmed,
                        onCheckedChange = actions.onSetZnnRecoveryConfirmed,
                    )
                }
            },
            dismissButton = {
                FolkTextButton(onClick = actions.onDismissZnnRisk) { Text(stringResource(android.R.string.cancel)) }
            },
            confirmButton = {
                FolkTextButton(
                    onClick = actions.onConfirmZnnRisk, enabled = state.canConfirmZnnRisk,
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = ZnnDangerRed, contentColor = Color.White,
                        disabledContainerColor = ZnnDangerRed.copy(alpha = 0.38f),
                        disabledContentColor = Color.White.copy(alpha = 0.7f),
                    ),
                ) { Text(continueText) }
            },
        )
    }
}
