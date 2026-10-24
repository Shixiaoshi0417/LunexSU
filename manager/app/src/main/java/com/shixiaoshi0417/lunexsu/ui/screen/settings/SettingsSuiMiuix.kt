package com.shixiaoshi0417.lunexsu.ui.screen.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.sui.SuiManager
import com.shixiaoshi0417.lunexsu.ui.screen.sui.SuiSwitchDialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
internal fun SuiCardMiuix(onOpenSui: () -> Unit) {
    val ui by SuiManager.state.collectAsStateWithLifecycle()
    var request by rememberSuiSwitchRequest()
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
    ) {
        SwitchPreference(
            title = stringResource(R.string.sui_switch_title),
            summary = suiSwitchSummary(ui),
            startAction = {
                Icon(
                    Icons.Rounded.Api,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = stringResource(R.string.sui_switch_title),
                    tint = if (ui.switchEnabled) colorScheme.onBackground else colorScheme.disabledOnSecondaryVariant,
                )
            },
            enabled = ui.switchEnabled,
            checked = ui.switchChecked,
            onCheckedChange = { request = it },
        )
        if (ui.managementVisible) {
            ArrowPreference(
                title = stringResource(R.string.sui_title),
                summary = stringResource(R.string.sui_separate_permissions),
                startAction = {
                    Icon(Icons.Rounded.ManageAccounts, null, Modifier.padding(end = 6.dp), tint = colorScheme.onBackground)
                },
                onClick = onOpenSui,
            )
        }
    }
    SuiSwitchDialog(request, ui) { request = null }
}
