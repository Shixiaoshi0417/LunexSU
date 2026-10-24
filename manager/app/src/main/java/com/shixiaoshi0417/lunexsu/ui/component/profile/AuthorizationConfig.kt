package com.shixiaoshi0417.lunexsu.ui.component.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.screen.appprofile.authorizationGrant
import com.shixiaoshi0417.lunexsu.ui.screen.appprofile.authorizationSelectionUpdate
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/** The switch and duration share a draft: choosing while off never authorizes Root. */
@Composable
fun AuthorizationConfig(
    profile: Natives.Profile,
    onProfileChange: (Natives.Profile) -> Unit,
    saving: Boolean = false,
    superuserSwitch: @Composable (enabled: Boolean, onCheckedChange: (Boolean) -> Unit) -> Unit,
) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    val focusManager = LocalFocusManager.current
    val temporarySupported = profile.authorizationSupported && profile.currentUid % 100000 in 10000..19999
    var selectedMode by rememberSaveable(profile.currentUid, profile.authorizationMode) {
        mutableStateOf(profile.authorizationMode)
    }
    var minutes by rememberSaveable(profile.currentUid, profile.authorizationDurationSeconds) {
        mutableStateOf(if (profile.authorizationDurationSeconds > 0)
            (profile.authorizationDurationSeconds / 60).coerceAtLeast(1).toString() else "5")
    }
    LaunchedEffect(profile.authorizationGeneration, profile.authorizationReadError, saving) {
        // Failed/rejected active edits must show the read-back policy, not optimistic success.
        // Off-state drafts survive polling; only the switch may turn them into a grant.
        if (!saving && profile.allowSu) {
            selectedMode = profile.authorizationMode
            if (profile.authorizationDurationSeconds > 0) {
                minutes = (profile.authorizationDurationSeconds / 60).coerceAtLeast(1).toString()
            }
        }
    }
    val modes = listOf(
        stringResource(R.string.authorization_permanent),
        stringResource(R.string.authorization_once),
        stringResource(R.string.authorization_timed),
    )
    val busy = saving || profile.authorizationState == 2 || profile.authorizationReadError != 0
    val validMinutes = minutes.toLongOrNull()?.takeIf { it in 1..525600 }
    val canGrant = !busy && (selectedMode == 0 || temporarySupported) &&
        (selectedMode != 2 || validMinutes != null)
    fun select(mode: Int, duration: String = minutes) {
        if (busy || (mode != 0 && !temporarySupported)) return
        selectedMode = mode
        minutes = duration
        authorizationSelectionUpdate(profile, mode, duration)?.let(onProfileChange)
    }
    superuserSwitch(!busy && (profile.allowSu || canGrant)) { enabled ->
        if (!busy) {
            if (enabled && canGrant) authorizationGrant(profile, selectedMode, minutes)?.let(onProfileChange)
            else if (!enabled) onProfileChange(profile.copy(allowSu = false, authorizationOperation = 0))
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (miuix) {
            OverlayDropdownPreference(
                title = stringResource(R.string.authorization_mode),
                items = if (temporarySupported) modes else modes.take(1),
                selectedIndex = selectedMode.coerceIn(0, if (temporarySupported) 2 else 0),
                onSelectedIndexChange = { select(it) },
                enabled = !busy && (temporarySupported || profile.authorizationMode == 0),
            )
        } else {
            Text(stringResource(R.string.authorization_mode))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEachIndexed { index, title ->
                    FilterChip(selected = selectedMode == index, onClick = { select(index) },
                        label = { Text(title) }, enabled = !busy && (index == 0 || temporarySupported))
                }
            }
        }
        if (selectedMode == 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 15, 60).forEach { preset ->
                    if (miuix) {
                        MiuixTextButton(modifier = Modifier.weight(1f), text = stringResource(R.string.authorization_minutes, preset),
                            onClick = { select(2, preset.toString()) }, enabled = !busy)
                    } else {
                        TextButton(onClick = { select(2, preset.toString()) }, enabled = !busy) {
                            Text(stringResource(R.string.authorization_minutes, preset))
                        }
                    }
                }
            }
            val keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
            val keyboardActions = KeyboardActions(onDone = {
                select(2)
                focusManager.clearFocus()
            })
            if (miuix) {
                MiuixTextField(value = minutes, onValueChange = { minutes = it }, enabled = !busy,
                    label = stringResource(R.string.authorization_custom_minutes),
                    keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
            } else {
                OutlinedTextField(value = minutes, onValueChange = { minutes = it }, singleLine = true,
                    enabled = !busy, label = { Text(stringResource(R.string.authorization_custom_minutes)) },
                    isError = validMinutes == null, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
            }
            if (validMinutes == null) {
                if (miuix) MiuixText(stringResource(R.string.authorization_invalid_duration))
                else Text(stringResource(R.string.authorization_invalid_duration))
            }
        }
    }
}
