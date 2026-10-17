package com.shixiaoshi0417.lunexsu.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskModuleVersion
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskOverview
import com.shixiaoshi0417.lunexsu.data.zygisk.ZygiskOverviewState
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFinaThemeUi
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** An informational card, not a second route for enabling ZNN or changing modules. */
@Composable
internal fun HomeZygiskCard(overview: ZygiskOverview, safeMode: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val native = LocalUiMode.current == UiMode.Miuix && !LocalFinaThemeUi.current
    val description = stringResource(if (expanded) R.string.home_zygisk_collapse else R.string.home_zygisk_expand)
    val modifier = Modifier.fillMaxWidth().semantics {
        role = Role.Button
        stateDescription = description
    }
    val content: @Composable () -> Unit = { ZygiskCardContent(overview, safeMode, expanded, native) }
    if (native) {
        MiuixCard(
            modifier = modifier,
            onClick = { expanded = !expanded },
            pressFeedbackType = PressFeedbackType.Tilt,
        ) { content() }
    } else {
        HomeCard(modifier = modifier, onClick = { expanded = !expanded }, content = content)
    }
}

@Composable
private fun ZygiskCardContent(overview: ZygiskOverview, safeMode: Boolean, expanded: Boolean, native: Boolean) {
    val foreground = if (native) MiuixTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    val summary = if (native) MiuixTheme.colorScheme.onSurfaceVariantSummary else MaterialTheme.colorScheme.onSurfaceVariant
    val primary = if (native) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary
    val error = if (native) MiuixTheme.colorScheme.error else MaterialTheme.colorScheme.error
    val state = overview.state(safeMode)
    val tone by animateColorAsState(
        targetValue = when (state) {
            ZygiskOverviewState.Conflict, ZygiskOverviewState.SafeMode -> error
            ZygiskOverviewState.Unavailable, ZygiskOverviewState.Disabled -> summary
            else -> primary
        },
        label = "zygiskStatusTone",
    )
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(260), label = "zygiskExpandArrow")
    val status = stringResource(when (state) {
        ZygiskOverviewState.Unavailable -> R.string.home_zygisk_unavailable
        ZygiskOverviewState.SafeMode -> R.string.home_zygisk_safe_mode
        ZygiskOverviewState.Conflict -> R.string.home_zygisk_conflict
        ZygiskOverviewState.PendingReboot -> R.string.home_zygisk_pending
        ZygiskOverviewState.MonitorDetected -> R.string.home_zygisk_monitor_detected
        ZygiskOverviewState.Configured -> R.string.home_zygisk_configured
        ZygiskOverviewState.Disabled -> R.string.home_zygisk_disabled
    })
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Outlined.Memory,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.background(tone.copy(alpha = 0.12f), CircleShape).padding(10.dp).size(24.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ZygiskText(stringResource(R.string.home_zygisk_title), native, foreground, title = true)
                ZygiskText(status, native, tone)
            }
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = summary, modifier = Modifier.rotate(rotation))
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(300)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(260)) + fadeOut(tween(180)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(Modifier.padding(top = 16.dp), color = summary.copy(alpha = 0.18f))
                if (overview.available) {
                    ZygiskDetail(R.string.home_zygisk_backend, overview.status.configured?.displayName
                        ?: stringResource(R.string.home_zygisk_none), native, foreground, summary)
                    ZygiskDetail(R.string.home_zygisk_monitor, stringResource(if (overview.status.monitorRunning)
                        R.string.home_zygisk_detected else R.string.home_zygisk_not_detected), native, foreground, summary)
                    ZygiskDetail(R.string.home_zygisk_znn, stringResource(if (overview.status.nextNextConfigured)
                        R.string.home_zygisk_znn_configured else R.string.home_zygisk_none), native, foreground, summary)
                    listOf("rezygisk" to "ReZygisk", "zygisksu" to "NeoZygisk", "zygisknextsu" to "ZNN").forEach { (id, name) ->
                        ZygiskDetailText(name, versionText(overview.versions[id]), native, foreground, summary)
                    }
                    overview.status.conflictId?.let { ZygiskDetail(R.string.home_zygisk_external, it, native, foreground, error) }
                    if (overview.status.legacyVex) {
                        ZygiskText(stringResource(R.string.settings_zygisk_legacy_vex), native, error)
                    }
                    ZygiskText(stringResource(R.string.home_zygisk_observation_hint), native, summary)
                } else {
                    ZygiskText(stringResource(R.string.home_zygisk_query_hint), native, summary)
                }
                ZygiskText(stringResource(R.string.settings_zygisk_nextnext_risk_summary), native, error)
            }
        }
    }
}

@Composable
private fun versionText(version: ZygiskModuleVersion?): String {
    val installed = version?.installed?.let { stringResource(R.string.home_zygisk_installed_version, it) }
    val staged = version?.staged?.let { stringResource(R.string.home_zygisk_staged_version, it) }
    return listOfNotNull(installed, staged).joinToString("\n").ifEmpty { stringResource(R.string.home_zygisk_version_unknown) }
}

@Composable
private fun ZygiskDetail(label: Int, value: String, native: Boolean, foreground: Color, summary: Color) =
    ZygiskDetailText(stringResource(label), value, native, foreground, summary)

@Composable
private fun ZygiskDetailText(label: String, value: String, native: Boolean, foreground: Color, summary: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZygiskText(label, native, summary)
        ZygiskText(value, native, foreground)
    }
}

@Composable
private fun ZygiskText(text: String, native: Boolean, color: Color, title: Boolean = false) {
    if (native) {
        MiuixText(text, color = color, fontSize = if (title) MiuixTheme.textStyles.headline1.fontSize else MiuixTheme.textStyles.body2.fontSize,
            fontWeight = if (title) FontWeight.SemiBold else FontWeight.Normal)
    } else {
        Text(text, color = color, style = if (title) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (title) FontWeight.SemiBold else FontWeight.Normal)
    }
}
