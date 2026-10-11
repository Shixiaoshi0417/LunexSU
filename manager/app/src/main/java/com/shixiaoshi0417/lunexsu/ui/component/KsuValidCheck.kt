package com.shixiaoshi0417.lunexsu.ui.component

import androidx.compose.runtime.Composable
import com.shixiaoshi0417.lunexsu.Natives

@Composable
fun KsuIsValid(
    content: @Composable () -> Unit
) {
    val isManager = Natives.isManager
    val ksuVersion = if (isManager) Natives.version else null

    if (ksuVersion != null) {
        content()
    }
}
