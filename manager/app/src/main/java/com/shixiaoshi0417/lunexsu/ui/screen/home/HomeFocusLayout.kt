// Adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.HomeMetrics
import com.shixiaoshi0417.lunexsu.ui.theme.pack.PackSurfaceId
import kotlin.math.roundToInt

/**
 * FocusUI: a board of tiles - status, manager, device metrics and storage - that becomes a 2x2 grid
 * on a wide landscape screen and a plain stack otherwise. Every fact is told once.
 */
@Composable
internal fun FocusHomeContent(
    state: HomeUiState,
    actions: HomeActions,
    metrics: HomeMetrics,
) {
    val statusTile = @Composable { modifier: Modifier -> FocusStatusTile(state, actions, modifier) }
    val managerTile = @Composable { modifier: Modifier ->
        HomeFactsTile(
            state = state,
            title = stringResource(R.string.app_name),
            icon = Icons.Outlined.AdminPanelSettings,
            modifier = modifier,
            surfaceId = PackSurfaceId.focus("app"),
        )
    }
    val deviceTile = @Composable { modifier: Modifier -> FocusDeviceTile(metrics, modifier) }
    val storageTile = @Composable { modifier: Modifier -> FocusStorageTile(metrics, modifier) }

    if (!state.isFullFeatured || !isWideLayout(withOrientation = true)) {
        Column(verticalArrangement = Arrangement.spacedBy(TileSpacing)) {
            statusTile(Modifier)
            managerTile(Modifier)
            deviceTile(Modifier)
            storageTile(Modifier)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(TileSpacing)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(TileSpacing),
            ) {
                statusTile(Modifier.weight(1f).fillMaxHeight())
                managerTile(Modifier.weight(1f).fillMaxHeight())
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(TileSpacing),
            ) {
                deviceTile(Modifier.weight(1f).fillMaxHeight())
                storageTile(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** The Focus board's status card: the state of the module, its version and how it is loaded. */
@Composable
private fun FocusStatusTile(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val ksuActive = state.ksuVersion != null
    val notInstalled = state.isNotInstalled()
    val jailbreak = notInstalled && state.isSELinuxPermissive

    val cardAction: (@Composable () -> Unit)? = when {
        state.showKernelUpdateAction -> {
            {
                StatusInstallButton(
                    onClick = actions.onInstallClick,
                    label = stringResource(R.string.module_update),
                )
            }
        }

        jailbreak -> {
            { StatusJailbreakButton(onClick = actions.onJailbreakClick) }
        }

        notInstalled && !state.isLateLoadMode -> {
            { StatusInstallButton(onClick = actions.onInstallClick) }
        }

        else -> null
    }

    HomeTileCard(
        title = stringResource(R.string.home_tile_kernel),
        icon = Icons.Outlined.VerifiedUser,
        modifier = modifier.fillMaxWidth(),
        action = cardAction,
        surfaceId = PackSurfaceId.focus("kernel"),
    ) {
        HomeFactRow(
            label = stringResource(R.string.home_running_status),
            value = state.statusTitle(),
        )
        HomeFactRow(
            label = stringResource(R.string.home_version),
            value = if (ksuActive) workingVersionLabel(state) else stringResource(R.string.home_not_installed),
        )
        statusStateTexts(state).forEach { (label, value) ->
            HomeFactRow(label = label, value = value)
        }
    }
}

/** The live battery and CPU metrics of the device the module is running on. */
@Composable
private fun FocusDeviceTile(
    metrics: HomeMetrics,
    modifier: Modifier = Modifier,
) {
    val device = metrics.device
    val cpuTemperature = device?.cpuTemperatureC

    HomeTileCard(
        title = stringResource(R.string.home_tile_device),
        icon = Icons.Outlined.Memory,
        modifier = modifier.fillMaxWidth(),
        surfaceId = PackSurfaceId.focus("device"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusCircle(
                value = device?.batteryTemperatureC?.let { "${it.roundToInt()}°C" } ?: DEFAULT_METRIC,
                label = stringResource(R.string.home_metric_battery_temperature),
                progress = device?.batteryTemperatureC?.let { (it / BATTERY_TEMPERATURE_MAX_C).coerceIn(0f, 1f) },
                color = MaterialTheme.colorScheme.primary,
            )
            StatusCircle(
                value = cpuTemperature?.let { "${it.roundToInt()}°C" } ?: DEFAULT_METRIC,
                label = stringResource(R.string.home_metric_cpu_temperature),
                progress = cpuTemperature?.let { (it / CPU_TEMPERATURE_MAX_C).coerceIn(0f, 1f) },
                color = MaterialTheme.colorScheme.secondary,
            )
            StatusCircle(
                value = device?.batteryLevelPercent?.let { "$it%" } ?: DEFAULT_METRIC,
                label = stringResource(R.string.home_metric_battery_level),
                progress = device?.batteryLevelPercent?.let { it / 100f },
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** The live internal storage and memory usage. */
@Composable
private fun FocusStorageTile(
    metrics: HomeMetrics,
    modifier: Modifier = Modifier,
) {
    val storage = metrics.storage

    HomeTileCard(
        title = stringResource(R.string.home_tile_storage),
        icon = Icons.Outlined.SdStorage,
        modifier = modifier.fillMaxWidth(),
        surfaceId = PackSurfaceId.focus("storage"),
    ) {
        StorageBar(
            label = stringResource(R.string.home_metric_storage_internal),
            usedBytes = storage?.dataUsedBytes ?: 0L,
            totalBytes = storage?.dataTotalBytes ?: 0L,
            color = MaterialTheme.colorScheme.primary,
        )
        StorageBar(
            label = stringResource(R.string.home_metric_storage_ram),
            usedBytes = storage?.ramUsedBytes ?: 0L,
            totalBytes = storage?.ramTotalBytes ?: 0L,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}
