package com.shixiaoshi0417.lunexsu.ui.component.choosekmidialog

import androidx.compose.runtime.Composable
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode

@Composable
fun ChooseKmiDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (String?) -> Unit,
    state: KmiDialogState = rememberKmiDialogState(show),
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> ChooseKmiDialogMiuix(show, onDismissRequest, onSelected, state)
        UiMode.Material -> ChooseKmiDialogMaterial(show, onDismissRequest, onSelected, state)
    }
}
