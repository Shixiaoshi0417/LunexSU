package com.shixiaoshi0417.lunexsu.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.sui.SuiUiState
import com.shixiaoshi0417.lunexsu.ui.screen.sui.suiStatusText

/** What the Sui switch does next boot, or why it cannot change now. */
@Composable
internal fun suiSwitchSummary(ui: SuiUiState): String {
    val status = ui.status
    val idle = !ui.reconciling && !status.desiredEnabled && !status.pendingReboot && status.payloadAvailable
    return ui.error ?: if (idle) stringResource(R.string.sui_switch_summary) else suiStatusText(ui)
}

/** The direction awaiting confirmation; leaving or backgrounding Settings discards it. */
@Composable
internal fun rememberSuiSwitchRequest(): MutableState<Boolean?> {
    val request = remember { mutableStateOf<Boolean?>(null) }
    LifecycleResumeEffect(Unit) { onPauseOrDispose { request.value = null } }
    return request
}
