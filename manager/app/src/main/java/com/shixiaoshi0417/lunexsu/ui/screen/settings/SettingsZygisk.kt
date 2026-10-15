package com.shixiaoshi0417.lunexsu.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.component.dialog.rememberLoadingDialog

/** What the next boot does, and what this boot runs, for the built-in Zygisk switch. */
@Composable
internal fun zygiskSummary(state: SettingsUiState): String {
    val status = state.zygiskStatus
    val conflict = status?.conflictId
    return when {
        state.isLateLoadMode -> stringResource(R.string.settings_zygisk_late_load)
        state.zygiskBackends.isEmpty() -> stringResource(R.string.settings_zygisk_unsupported)
        status == null -> stringResource(R.string.settings_zygisk_summary)
        conflict != null && !state.zygiskEnabled -> stringResource(R.string.settings_zygisk_conflict, conflict)
        else -> {
            val configured = status.configured
            val active = status.active
            when {
                configured == null && active == null -> stringResource(R.string.settings_zygisk_summary)
                configured == null -> stringResource(R.string.settings_zygisk_pending_disable, active!!.displayName)
                active == null -> stringResource(R.string.settings_zygisk_pending_enable, configured.displayName)
                configured != active -> stringResource(
                    R.string.settings_zygisk_pending_switch, active.displayName, configured.displayName,
                )
                status.monitorRunning -> stringResource(R.string.settings_zygisk_running, configured.displayName)
                else -> stringResource(R.string.settings_zygisk_not_running, configured.displayName)
            }
        }
    }
}

@Composable
internal fun zygiskBackendSummary(state: SettingsUiState): String = when (state.zygiskBackends.size) {
    0 -> stringResource(R.string.settings_zygisk_unsupported)
    1 -> stringResource(R.string.settings_zygisk_backend_single, state.zygiskBackends.single().displayName)
    else -> stringResource(R.string.settings_zygisk_backend_summary)
}

/** Shows the platform loading dialog while a backend installs or is marked for removal. */
@Composable
internal fun ZygiskApplyingDialog(busy: Boolean) {
    val loadingDialog = rememberLoadingDialog()
    LaunchedEffect(busy) {
        if (busy) loadingDialog.show() else loadingDialog.hide()
    }
}
