package com.shixiaoshi0417.lunexsu.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.Superkey
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveDialog
import com.shixiaoshi0417.lunexsu.ui.component.material.FolkTextButton
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedColumn
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedListItem
import com.shixiaoshi0417.lunexsu.ui.screen.install.SuperkeyField
import com.shixiaoshi0417.lunexsu.ui.util.KsuCli
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun SuperkeyEntry(onClick: () -> Unit) {
    if (LocalUiMode.current == UiMode.Miuix) {
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(title = stringResource(R.string.superkey_enter), onClick = onClick)
        }
    } else {
        SegmentedColumn(content = listOf({
            SegmentedListItem(headlineContent = { Text(stringResource(R.string.superkey_enter)) }, onClick = onClick)
        }))
    }
}

@Composable
internal fun SuperkeyDialog(onDismiss: () -> Unit, onAuthenticated: () -> Unit) {
    // Never save keys through configuration changes or navigation state.
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismiss = { if (!busy) { key = ""; onDismiss() } }
    val submit: () -> Unit = {
        if (!busy) {
            busy = true
            failed = false
            val chars = key.toCharArray()
            key = ""
            scope.launch {
                try {
                    val accepted = withContext(Dispatchers.IO) {
                        val salt = Natives.superkeySalt()
                        if (salt == null) false else {
                            val proof = Superkey.proof(chars, salt)
                            try { Natives.authenticateSuperkey(proof) } finally { proof.fill(0) }
                        }
                    }
                    if (accepted) {
                        withContext(Dispatchers.IO) { KsuCli.refreshAfterAuthentication() }
                        onAuthenticated()
                    } else failed = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed = true
                } finally {
                    chars.fill('\u0000')
                    busy = false
                }
            }
        }
    }
    val content: @Composable () -> Unit = {
        Column {
            SuperkeyField(key, { key = it }, stringResource(R.string.superkey_enter), enabled = !busy)
            if (failed) Text(stringResource(R.string.superkey_failed))
        }
    }
    if (LocalUiMode.current == UiMode.Miuix) {
        WindowDialog(show = true, title = stringResource(R.string.superkey_enter), onDismissRequest = dismiss) {
            content()
            Row {
                TextButton(stringResource(android.R.string.cancel), onClick = dismiss, enabled = !busy, modifier = Modifier.weight(1f))
                TextButton(stringResource(android.R.string.ok), onClick = submit, enabled = !busy && key.isNotEmpty(), modifier = Modifier.weight(1f))
            }
        }
    } else {
        ExpressiveDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.superkey_enter)) },
            text = content,
            confirmButton = { FolkTextButton(onClick = submit, enabled = !busy && key.isNotEmpty()) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { FolkTextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
