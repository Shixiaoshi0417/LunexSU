package com.shixiaoshi0417.lunexsu.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode

/** Visibility and persisted dismissal are owned by the demo activity. */
@Composable
fun NoRootDemoBanner(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF713500)).statusBarsPadding().padding(12.dp, 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = stringResource(R.string.no_root_demo_notice)
        if (LocalUiMode.current == UiMode.Miuix) {
            top.yukonga.miuix.kmp.basic.Text(text, modifier = Modifier.weight(1f), color = Color(0xFFFFF4DE))
        } else {
            androidx.compose.material3.Text(text, modifier = Modifier.weight(1f), color = Color(0xFFFFF4DE))
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color(0xFFFFF4DE))
        }
    }
}
