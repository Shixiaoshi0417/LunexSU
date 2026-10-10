package me.weishu.kernelsu.ui.component.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/** Embedded in the existing pre-management page. Never invoked by a su request. */
@Composable
fun AuthorizationConfig(profile: Natives.Profile, onProfileChange: (Natives.Profile) -> Unit) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    val temporarySupported = Natives.kernelUAPIVersion >= 6 && profile.currentUid % 100000 in 10000..19999
    var selectedMode by rememberSaveable(profile.authorizationMode) { mutableStateOf(profile.authorizationMode) }
    var minutes by rememberSaveable(profile.authorizationDurationSeconds) {
        mutableStateOf((profile.authorizationDurationSeconds / 60).coerceAtLeast(1).toString())
    }
    val modes = listOf(
        stringResource(R.string.authorization_permanent),
        stringResource(R.string.authorization_once),
        stringResource(R.string.authorization_timed)
    )
    val status = when {
        !profile.allowSu -> stringResource(R.string.authorization_revoked)
        profile.authorizationState == 2 -> stringResource(R.string.authorization_busy)
        profile.authorizationMode != 0 && profile.authorizationState != 1 -> stringResource(R.string.authorization_inactive)
        profile.authorizationMode == 2 -> stringResource(R.string.authorization_remaining, profile.authorizationRemainingSeconds)
        else -> modes.getOrElse(profile.authorizationMode) { modes[0] }
    }
    val validMinutes = minutes.toLongOrNull()?.takeIf { it in 1..525600 }
    val busy = profile.authorizationState == 2
    val grant = {
        onProfileChange(profile.copy(
            allowSu = true,
            authorizationMode = selectedMode,
            authorizationDurationSeconds = (validMinutes ?: 5) * 60,
            authorizationOperation = 1
        ))
    }
    val revoke = { onProfileChange(profile.copy(allowSu = false, authorizationOperation = 0)) }
    val grantLabel = stringResource(
        if (profile.allowSu && profile.authorizationMode == 2 && selectedMode == 2) R.string.authorization_renew
        else R.string.authorization_grant
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (miuix) {
            MiuixText(status)
            OverlayDropdownPreference(
                title = stringResource(R.string.authorization_mode), items = if (temporarySupported) modes else modes.take(1),
                selectedIndex = if (temporarySupported) selectedMode else 0,
                onSelectedIndexChange = { selectedMode = it }, enabled = !busy
            )
        } else {
            Text(stringResource(R.string.authorization_mode))
            Text(status)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEachIndexed { index, title ->
                    FilterChip(selected = selectedMode == index, onClick = { selectedMode = index },
                        label = { Text(title) }, enabled = !busy && (index == 0 || temporarySupported))
                }
            }
        }
        if (selectedMode == 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 15, 60).forEach { preset ->
                    if (miuix) {
                        MiuixTextButton(text = stringResource(R.string.authorization_minutes, preset), onClick = { minutes = preset.toString() })
                    } else {
                        TextButton(onClick = { minutes = preset.toString() }) {
                            Text(stringResource(R.string.authorization_minutes, preset))
                        }
                    }
                }
            }
            if (miuix) {
                MiuixTextField(value = minutes, onValueChange = { minutes = it },
                    label = stringResource(R.string.authorization_custom_minutes),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            } else {
                OutlinedTextField(value = minutes, onValueChange = { minutes = it }, singleLine = true,
                    label = { Text(stringResource(R.string.authorization_custom_minutes)) },
                    isError = validMinutes == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
        val enabled = !busy && (selectedMode == 0 || temporarySupported) && (selectedMode != 2 || validMinutes != null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (miuix) {
                MiuixTextButton(text = grantLabel, onClick = grant, enabled = enabled)
                MiuixTextButton(text = stringResource(R.string.authorization_revoke), onClick = revoke, enabled = profile.allowSu)
            } else {
                TextButton(onClick = grant, enabled = enabled) { Text(grantLabel) }
                TextButton(onClick = revoke, enabled = profile.allowSu) { Text(stringResource(R.string.authorization_revoke)) }
            }
        }
        if (miuix) MiuixText(stringResource(R.string.authorization_boot_scope))
        else Text(stringResource(R.string.authorization_boot_scope))
        if (!temporarySupported) {
            if (miuix) MiuixText(stringResource(R.string.authorization_permanent_only))
            else Text(stringResource(R.string.authorization_permanent_only))
        }
    }
}
