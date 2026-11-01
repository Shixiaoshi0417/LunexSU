package com.shixiaoshi0417.lunexsu.ui.screen.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.sui.SuiManager
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedColumn
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedListItem
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedSwitchItem
import com.shixiaoshi0417.lunexsu.ui.screen.sui.SuiSwitchDialog

/** The Sui switch, like built-in Zygisk; management appears only once it is switched on. */
@Composable
internal fun SuiCardMaterial(onOpenSui: () -> Unit) {
    val ui by SuiManager.state.collectAsStateWithLifecycle()
    var request by rememberSuiSwitchRequest()
    SegmentedColumn(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp)) {
        item {
            SegmentedSwitchItem(
                icon = Icons.Filled.Api,
                title = stringResource(R.string.sui_switch_title),
                summary = suiSwitchSummary(ui),
                enabled = ui.switchEnabled,
                checked = ui.switchChecked,
                onCheckedChange = { request = it },
            )
        }
        item(visible = ui.managementVisible) {
            SegmentedListItem(
                onClick = { if (SuiManager.state.value.managementVisible) onOpenSui() },
                headlineContent = { Text(stringResource(R.string.sui_title)) },
                supportingContent = { Text(stringResource(R.string.sui_separate_permissions)) },
                leadingContent = { Icon(Icons.Filled.ManageAccounts, null) },
            )
        }
    }
    SuiSwitchDialog(request, ui) { request = null }
}
