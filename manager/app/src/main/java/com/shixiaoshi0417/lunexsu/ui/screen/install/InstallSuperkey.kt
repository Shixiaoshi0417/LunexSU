package com.shixiaoshi0417.lunexsu.ui.screen.install

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedCheckboxItem
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal fun InstallSuperkey(state: InstallUiState, actions: InstallScreenActions) {
    Column {
        if (LocalUiMode.current == UiMode.Miuix) {
            CheckboxPreference(
                title = stringResource(R.string.superkey_install),
                summary = stringResource(R.string.superkey_install_summary),
                checked = state.superkeyEnabled,
                enabled = !state.preparing,
                onCheckedChange = actions.onSuperkeyEnabled,
            )
        } else {
            SegmentedCheckboxItem(
                title = stringResource(R.string.superkey_install),
                summary = stringResource(R.string.superkey_install_summary),
                checked = state.superkeyEnabled,
                enabled = !state.preparing,
                onCheckedChange = actions.onSuperkeyEnabled,
            )
        }
        if (state.superkeyEnabled) {
            SuperkeyField(state.superkey, actions.onSuperkeyChange, stringResource(R.string.superkey_set), !state.preparing)
            SuperkeyField(state.superkeyConfirmation, actions.onSuperkeyConfirmation, stringResource(R.string.superkey_confirm), !state.preparing)
            Text(stringResource(R.string.superkey_validation), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
fun SuperkeyField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    if (LocalUiMode.current == UiMode.Miuix) {
        TextField(value = value, onValueChange = onChange, label = label, singleLine = true,
            enabled = enabled, modifier = modifier, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    } else {
        OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            enabled = enabled, modifier = modifier, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    }
}
