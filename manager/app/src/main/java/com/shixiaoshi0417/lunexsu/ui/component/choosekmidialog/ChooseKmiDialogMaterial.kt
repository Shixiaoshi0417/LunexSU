package com.shixiaoshi0417.lunexsu.ui.component.choosekmidialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedColumn
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedRadioItem

@Composable
fun ChooseKmiDialogMaterial(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (String?) -> Unit,
    state: KmiDialogState,
) {
    if (!show) return

    val supportedKMIs = state.supportedKmis
    val currentKmi = state.currentKmi
    val selectedKmi = remember(currentKmi, show) { mutableStateOf(currentKmi) }

    AlertDialog(
        onDismissRequest = {
            onDismissRequest()
            selectedKmi.value = currentKmi
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!state.canConfirm(selectedKmi.value)) return@TextButton
                    onSelected(selectedKmi.value)
                    onDismissRequest()
                },
                enabled = state.canConfirm(selectedKmi.value)
            ) {
                Text(stringResource(id = R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismissRequest()
                selectedKmi.value = currentKmi
            }) {
                Text(stringResource(id = android.R.string.cancel))
            }
        },
        title = {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                text = stringResource(R.string.select_kmi),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.current_kmi, currentKmi.ifBlank { "Unknown" }), modifier = Modifier.padding(bottom = 12.dp))
                val message = when {
                    state.loading -> R.string.install_loading_kmis
                    state.failed -> R.string.install_load_kmis_failed
                    supportedKMIs.isEmpty() -> R.string.install_no_bundled_lkm
                    currentKmi.isNotBlank() && currentKmi !in supportedKMIs -> R.string.install_no_matching_lkm
                    else -> null
                }
                message?.let { Text(stringResource(it), modifier = Modifier.padding(bottom = 12.dp)) }
                SegmentedColumn(
                    content = supportedKMIs.map { kmi ->
                        {
                            SegmentedRadioItem(
                                title = kmi,
                                summary = if (kmi == currentKmi) stringResource(R.string.current_device_kmi) else null,
                                selected = selectedKmi.value == kmi,
                                onClick = { selectedKmi.value = kmi }
                            )
                        }
                    }
                )
            }
        }
    )
}
