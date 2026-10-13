// Adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.home

import com.shixiaoshi0417.lunexsu.ui.theme.pack.PackSurfaceId

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.theme.FolkType

/**
 * DashboardUI: one wide hero banner over the counters and the system facts. The facts card always
 * sits under the horizontal pair of counters, so the board reads the same on a phone and a tablet.
 */
@Composable
internal fun DashboardHomeContent(
    state: HomeUiState,
    actions: HomeActions,
    superuserCount: Int,
    moduleEnabledCount: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TileSpacing)) {
        DashboardHeroCard(state = state, actions = actions)
        if (state.isFullFeatured) {
            CountCardPair(
                superuserCount = superuserCount,
                moduleEnabledCount = moduleEnabledCount,
                onOpenSuperUser = actions.onOpenSuperUser,
                onOpenModule = actions.onOpenModule,
                layout = CountCardLayout.Horizontal,
                emphasis = CountCardEmphasis.Value,
            )
        }
        HomeInfoCard(systemInfo = state.systemInfo)
    }
}

/**
 * The Dashboard banner: the state icon and title over the three facts the hero was built to carry
 * (the working version, the kernel and SELinux). It is a plain card like the ones under it: the
 * state is read from the icon and the wording.
 */
@Composable
private fun DashboardHeroCard(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val ksuActive = state.ksuVersion != null
    val jailbreak = state.isNotInstalled() && state.isSELinuxPermissive
    val statusTitle = state.statusTitle()

    HomeCard(
        modifier = modifier.fillMaxWidth(),
        surfaceId = PackSurfaceId.DASHBOARD,
        onClick = {
            if (!state.isLateLoadMode) {
                actions.onInstallClick()
            }
        },
    ) {
        val subColor = MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = state.statusIcon(),
                    contentDescription = statusTitle,
                    modifier = Modifier.size(36.dp),
                    tint = if (ksuActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = statusTitle, style = FolkType.Title)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.statusSummary(),
                        style = FolkType.Summary,
                        color = subColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (jailbreak) {
                    Spacer(Modifier.width(16.dp))
                    StatusJailbreakButton(onClick = actions.onJailbreakClick)
                }
            }
            val stateTexts = statusStateTexts(state)
            if (stateTexts.isNotEmpty()) {
                Text(
                    text = stateTexts.joinToString(" · ") { (label, value) -> "$label: $value" },
                    style = FolkType.Caption,
                    color = subColor,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroFact(
                    label = stringResource(R.string.home_version),
                    value = if (ksuActive) workingVersionLabel(state) else DEFAULT_METRIC,
                    modifier = Modifier.weight(1f),
                )
                HeroFact(
                    label = stringResource(R.string.home_kernel),
                    value = state.systemInfo.kernelVersion,
                    modifier = Modifier.weight(1f),
                )
                HeroFact(
                    label = stringResource(R.string.home_selinux_status),
                    value = selinuxDisplayName(state.systemInfo.selinuxStatus),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One label over its value, the column unit of the hero banner. */
@Composable
private fun HeroFact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = FolkType.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = FolkType.Summary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
