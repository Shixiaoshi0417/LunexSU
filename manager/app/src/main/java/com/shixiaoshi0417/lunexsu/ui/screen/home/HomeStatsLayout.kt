// Adapted from FolkSU (https://github.com/LyraVoid/FolkSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.HomeMetrics
import com.shixiaoshi0417.lunexsu.data.model.HomeLayoutStyle
import com.shixiaoshi0417.lunexsu.ui.component.FolkMetricValue
import com.shixiaoshi0417.lunexsu.ui.component.chart.ModulePieChart
import com.shixiaoshi0417.lunexsu.ui.component.chart.PieSlice
import com.shixiaoshi0417.lunexsu.ui.component.chart.WaveChart
import com.shixiaoshi0417.lunexsu.ui.theme.FolkType
import kotlin.math.roundToInt

/**
 * StatsUI: the status header, the live monitor, the module/superuser donut, then the system facts.
 * On a wide screen the four tiles split into two columns.
 */
@Composable
internal fun StatsHomeContent(
    state: HomeUiState,
    actions: HomeActions,
    superuserCount: Int,
    moduleEnabledCount: Int,
    metrics: HomeMetrics,
) {
    val systemTile = @Composable {
        HomeFactsTile(
            state = state,
            title = stringResource(R.string.home_tile_system),
            icon = Icons.Outlined.Info,
        )
    }
    if (isWideLayout(withOrientation = false)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TileSpacing),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TileSpacing),
            ) {
                StatsStatusSection(state, actions, superuserCount, moduleEnabledCount)
                StatsMonitorTile(metrics = metrics)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TileSpacing),
            ) {
                StatsModuleTile(state, superuserCount, moduleEnabledCount)
                systemTile()
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(TileSpacing)) {
        StatsStatusSection(state, actions, superuserCount, moduleEnabledCount)
        StatsMonitorTile(metrics = metrics)
        StatsModuleTile(state, superuserCount, moduleEnabledCount)
        systemTile()
    }
}

/** The Stats header: the status row card, or the Grid board when the user picked it. */
@Composable
private fun StatsStatusSection(
    state: HomeUiState,
    actions: HomeActions,
    superuserCount: Int,
    moduleEnabledCount: Int,
) {
    if (LocalHomeLayoutOptions.current.statsTopLayout == HomeLayoutStyle.STATS_TOP_GRID) {
        GridHomeBoard(state, actions, superuserCount, moduleEnabledCount)
    } else {
        HomeStatusCard(state, actions)
    }
}

/** The live metrics the stats board is built around: CPU, memory and battery. */
@Composable
private fun StatsMonitorTile(
    metrics: HomeMetrics,
    modifier: Modifier = Modifier,
) {
    val device = metrics.device
    val storage = metrics.storage
    val history = metrics.history
    val cpuTemperature = device?.cpuTemperatureC
    val batteryLevel = device?.batteryLevelPercent
    val cpuFrequencies = device?.cpuFrequencies.orEmpty()
    val clusters = remember(cpuFrequencies) { cpuClusters(cpuFrequencies) }

    HomeTileCard(
        title = stringResource(R.string.home_tile_monitor),
        icon = Icons.Outlined.Speed,
        modifier = modifier.fillMaxWidth(),
    ) {
        WaveChart(
            label = stringResource(R.string.home_metric_cpu_temperature),
            value = cpuTemperature?.let { "${it.roundToInt()}°C" } ?: DEFAULT_METRIC,
            samples = history.cpuTemperature,
            color = MaterialTheme.colorScheme.secondary,
        )
        WaveChart(
            label = stringResource(R.string.home_metric_storage_ram),
            value = storage?.takeIf { it.ramTotalBytes > 0L }
                ?.let { "${(it.ramUsedFraction * 100f).roundToInt()}%" } ?: DEFAULT_METRIC,
            samples = history.memoryUsage,
            color = MaterialTheme.colorScheme.primary,
        )
        if (clusters.isNotEmpty()) {
            Text(
                text = stringResource(R.string.home_metric_cpu_frequency),
                style = FolkType.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            clusters.forEach { cluster ->
                MetricBar(
                    label = cluster.label,
                    value = formatFrequency(cluster.averageFreqKHz),
                    progress = cluster.usedFraction,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        MetricBar(
            label = stringResource(R.string.home_metric_battery_level),
            value = batteryLevel?.let { "$it%" } ?: DEFAULT_METRIC,
            progress = batteryLevel?.let { it / 100f } ?: 0f,
            color = when {
                batteryLevel == null -> MaterialTheme.colorScheme.primary
                batteryLevel <= 20 -> MaterialTheme.colorScheme.error
                batteryLevel <= 50 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }
}

/**
 * The counts of the stats board as a donut: modules against superusers. Without a full-featured
 * kernel the counts are unknown, so the tile says so instead of drawing an empty ring.
 */
@Composable
private fun StatsModuleTile(
    state: HomeUiState,
    superuserCount: Int,
    moduleEnabledCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val moduleLabel = stringResource(R.string.module)
    val superuserLabel = stringResource(R.string.superuser)

    HomeTileCard(
        title = stringResource(R.string.home_tile_stats),
        icon = Icons.Outlined.PieChart,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (!state.isFullFeatured) {
            FolkMetricValue(
                value = null,
                unavailableLabel = stringResource(R.string.metric_unavailable),
                unavailableStyle = FolkType.Summary,
            )
            return@HomeTileCard
        }
        val slices = remember(moduleEnabledCount, superuserCount, colors) {
            listOf(
                PieSlice(value = moduleEnabledCount, color = colors.primary),
                PieSlice(value = superuserCount, color = colors.tertiary),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModulePieChart(
                slices = slices,
                centerLabel = (superuserCount + moduleEnabledCount).toString(),
                modifier = Modifier.size(140.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChartLegend(label = moduleLabel, count = moduleEnabledCount, color = colors.primary)
                ChartLegend(label = superuserLabel, count = superuserCount, color = colors.tertiary)
            }
        }
    }
}

/** One donut legend line: the slice colour, its name and its count. */
@Composable
private fun ChartLegend(
    label: String,
    count: Int,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = FolkType.Summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = count.toString(),
            style = FolkType.Summary.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
