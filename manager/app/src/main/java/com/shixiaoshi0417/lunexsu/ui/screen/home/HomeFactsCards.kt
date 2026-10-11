// Adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.theme.FolkType

/** The manager build and the policy it runs under, or the system facts the stats board closes with. */
@Composable
internal fun HomeFactsTile(
    state: HomeUiState,
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    HomeTileCard(
        title = title,
        icon = icon,
        modifier = modifier.fillMaxWidth(),
    ) {
        HomeFactRow(
            label = stringResource(R.string.home_manager_version),
            value = state.systemInfo.managerVersion,
        )
        HomeFactRow(
            label = stringResource(R.string.home_kernel),
            value = state.systemInfo.kernelVersion,
        )
        HomeFactRow(
            label = stringResource(R.string.home_device_model),
            value = state.systemInfo.deviceModel,
        )
        HomeFactRow(
            label = stringResource(R.string.home_fingerprint),
            value = state.systemInfo.fingerprint,
        )
        HomeFactRow(
            label = stringResource(R.string.home_selinux_status),
            value = selinuxDisplayName(state.systemInfo.selinuxStatus),
        )
        HomeFactRow(
            label = stringResource(R.string.home_seccomp_status),
            value = seccompDisplayName(state.systemInfo.seccompStatus),
        )
    }
}

/** One `label: value` line, the shape every row of the Focus board takes. */
@Composable
internal fun HomeFactRow(
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "$label: ",
            style = FolkType.Summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = FolkType.Summary.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The system facts card the Circle, Grid and Dashboard layouts end with. */
@Composable
internal fun HomeInfoCard(
    systemInfo: SystemInfo,
    modifier: Modifier = Modifier,
) {
    HomeCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HomeInfoRow(
                icon = Icons.Filled.Tag,
                label = stringResource(R.string.home_manager_version),
                value = systemInfo.managerVersion,
            )
            HomeInfoRow(
                icon = Icons.Filled.DeveloperBoard,
                label = stringResource(R.string.home_kernel),
                value = systemInfo.kernelVersion,
            )
            HomeInfoRow(
                icon = Icons.Filled.Smartphone,
                label = stringResource(R.string.home_device_model),
                value = systemInfo.deviceModel,
            )
            HomeInfoRow(
                icon = Icons.Filled.Fingerprint,
                label = stringResource(R.string.home_fingerprint),
                value = systemInfo.fingerprint,
            )
            HomeInfoRow(
                icon = Icons.Filled.Security,
                label = stringResource(R.string.home_selinux_status),
                value = selinuxDisplayName(systemInfo.selinuxStatus),
            )
            HomeInfoRow(
                icon = Icons.Filled.FilterList,
                label = stringResource(R.string.home_seccomp_status),
                value = seccompDisplayName(systemInfo.seccompStatus),
            )
        }
    }
}

@Composable
private fun HomeInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text = label, style = FolkType.Summary)
            Text(
                text = value,
                style = FolkType.Machine,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
