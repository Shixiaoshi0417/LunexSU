package com.shixiaoshi0417.lunexsu.ui.component

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.SavedSuperkey
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveDialog
import com.shixiaoshi0417.lunexsu.ui.component.material.FolkTextButton
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedCheckboxItem
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedColumn
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedListItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun RememberSuperkeyCheckbox(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    if (LocalUiMode.current == UiMode.Miuix) {
        CheckboxPreference(title = stringResource(R.string.superkey_remember),
            summary = stringResource(R.string.superkey_remember_hint),
            checked = checked, enabled = enabled, onCheckedChange = onChange)
    } else {
        SegmentedCheckboxItem(title = stringResource(R.string.superkey_remember),
            summary = stringResource(R.string.superkey_remember_hint),
            checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
internal fun SavedSuperkeyCard(modifier: Modifier = Modifier) {
    val saved by SavedSuperkey.isSaved.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SavedSuperkey.refresh() }
    LifecycleResumeEffect(Unit) {
        scope.launch { SavedSuperkey.refresh() }
        onPauseOrDispose { }
    }
    val title = stringResource(R.string.superkey_saved)
    val summary = stringResource(if (saved) R.string.superkey_saved_hint else R.string.superkey_not_saved)
    if (LocalUiMode.current == UiMode.Miuix) {
        Card(modifier.fillMaxWidth()) {
            BasicComponent(title = title, summary = summary, enabled = saved && !busy,
                onClick = { confirm = true })
        }
    } else {
        SegmentedColumn(modifier = modifier, content = listOf({
            SegmentedListItem(headlineContent = { Text(title) }, supportingContent = { Text(summary) },
                enabled = saved && !busy, onClick = { confirm = true })
        }))
    }
    if (confirm) {
        val dismiss = { if (!busy) confirm = false }
        val clear: () -> Unit = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        SavedSuperkey.forget()
                        confirm = false
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { Toast.makeText(context, R.string.superkey_storage_failed, Toast.LENGTH_LONG).show() }
                    finally { busy = false }
                }
            }
        }
        if (LocalUiMode.current == UiMode.Miuix) {
            WindowDialog(show = true, title = stringResource(R.string.superkey_forget), onDismissRequest = dismiss) {
                top.yukonga.miuix.kmp.basic.Text(stringResource(R.string.superkey_forget_hint))
                Row {
                    TextButton(stringResource(android.R.string.cancel), onClick = dismiss, enabled = !busy, modifier = Modifier.weight(1f))
                    TextButton(stringResource(R.string.superkey_forget), onClick = clear, enabled = !busy, modifier = Modifier.weight(1f))
                }
            }
        } else {
            ExpressiveDialog(onDismissRequest = dismiss,
                title = { Text(stringResource(R.string.superkey_forget)) },
                text = { Text(stringResource(R.string.superkey_forget_hint)) },
                confirmButton = { FolkTextButton(onClick = clear, enabled = !busy) { Text(stringResource(R.string.superkey_forget)) } },
                dismissButton = { FolkTextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } })
        }
    }
}
