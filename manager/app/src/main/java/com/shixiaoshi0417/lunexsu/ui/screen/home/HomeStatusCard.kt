// Adapted from FolkSU (https://github.com/LyraVoid/FolkSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.component.material.FolkButton
import com.shixiaoshi0417.lunexsu.ui.component.statustag.StatusTag
import com.shixiaoshi0417.lunexsu.ui.theme.FolkType

/**
 * The module states every layout reports, as `label to value` pairs: how the module is loaded and
 * the states that change how it behaves, in a fixed order. Empty when the module is not loaded.
 */
@Composable
internal fun statusStateTexts(state: HomeUiState): List<Pair<String, String>> {
    if (state.ksuVersion == null) return emptyList()
    return buildList {
        val workingMode = workingModeLabel(state)
        if (workingMode.isNotEmpty()) {
            add(stringResource(R.string.home_running_mode) to workingMode)
        }
        if (state.isSafeMode) {
            add(stringResource(R.string.safe_mode) to stringResource(R.string.home_state_on))
        }
        if (state.isLateLoadMode) {
            add(stringResource(R.string.jailbreak_mode) to stringResource(R.string.home_state_on))
        }
        if (state.showCustomLkmBadge) {
            add(stringResource(R.string.home_lkm_custom) to stringResource(R.string.home_state_on))
        }
    }
}

/** The install action for a kernel that has the module but never loaded it. */
@Composable
internal fun StatusInstallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.install),
) {
    FolkButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = ButtonDefaults.MediumContentPadding,
    ) {
        Text(label)
    }
}

/** The jailbreak action, offered while the module is installed but the kernel refuses to load it. */
@Composable
internal fun StatusJailbreakButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FolkButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(stringResource(R.string.home_jailbreak))
    }
}

/** The accent pair of the status card: secondary while working, error otherwise. */
@Composable
internal fun statusContainerColor(working: Boolean): Color = if (working) {
    MaterialTheme.colorScheme.secondaryContainer
} else {
    MaterialTheme.colorScheme.errorContainer
}

/** The status row card of the Circle and Stats layouts. */
@Composable
internal fun HomeStatusCard(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val ksuActive = state.ksuVersion != null
    val notInstalled = state.isNotInstalled()
    val containerColor = statusContainerColor(ksuActive)
    val statusTitle = state.statusTitle()
    val workingMode = workingModeLabel(state)

    val hideMode = LocalHomeLayoutOptions.current.hideWorkingMode
    val statusTrailing: (@Composable () -> Unit)? = if (ksuActive && workingMode.isNotEmpty() && !hideMode) {
        {
            StatusTag(
                label = workingMode,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                backgroundColor = MaterialTheme.colorScheme.primary
            )
        }
    } else if (notInstalled && state.isSELinuxPermissive) {
        {
            StatusJailbreakButton(onClick = actions.onJailbreakClick)
        }
    } else null

    HomeCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = containerColor,
        contentColor = contentColorFor(containerColor),
        onClick = {
            if (!state.isLateLoadMode) {
                actions.onInstallClick()
            }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(state.statusIcon(), contentDescription = statusTitle)
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusTitle,
                        style = FolkType.Title
                    )
                    if (ksuActive && state.isSafeMode) {
                        Spacer(Modifier.width(8.dp))
                        StatusTag(
                            label = stringResource(id = R.string.safe_mode),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            backgroundColor = MaterialTheme.colorScheme.errorContainer
                        )
                    }
                    if (ksuActive && state.isLateLoadMode) {
                        Spacer(Modifier.width(8.dp))
                        StatusTag(
                            label = stringResource(id = R.string.jailbreak_mode),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            backgroundColor = MaterialTheme.colorScheme.errorContainer
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.statusSummary(),
                        modifier = Modifier.weight(1f, fill = false),
                        style = FolkType.Summary,
                        color = contentColorFor(containerColor).copy(alpha = 0.75f)
                    )
                    if (state.showCustomLkmBadge) {
                        Spacer(Modifier.width(8.dp))
                        StatusTag(
                            label = stringResource(R.string.home_lkm_custom),
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                        )
                    }
                }
            }
            if (statusTrailing != null) {
                Spacer(Modifier.width(16.dp))
                statusTrailing()
            }
        }
    }
}
