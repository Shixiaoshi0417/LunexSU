// FPT import workflow adapted from FolkSU (https://github.com/LyraVoid/FolkSU), GPL-3.0.
package me.weishu.kernelsu.ui.screen.colorpalette

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.dialog.ConfirmResult
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.component.dialog.rememberLoadingDialog
import me.weishu.kernelsu.ui.theme.pack.ThemePackImporter

internal data class ThemePackActions(val busy: Boolean, val onImport: () -> Unit, val onReset: () -> Unit)

/** One picker/confirmation pipeline; the existing dialog host selects Material or MIUIX. */
@Composable
internal fun rememberThemePackActions(onApplied: () -> Unit): ThemePackActions {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmDialog()
    val loading = rememberLoadingDialog()
    val latestOnApplied by rememberUpdatedState(onApplied)
    var busy by remember { mutableStateOf(false) }
    val title = stringResource(R.string.fpt_import)
    val applyText = stringResource(R.string.fpt_apply)
    val cancelText = stringResource(android.R.string.cancel)
    val resetTitle = stringResource(R.string.fpt_reset)
    val resetMessage = stringResource(R.string.fpt_reset_message)
    val warningHeader = stringResource(R.string.fpt_unsupported)
    val unknownName = stringResource(R.string.fpt_unnamed)
    val detailsFormat = stringResource(R.string.fpt_details)
    val moreFormat = stringResource(R.string.fpt_more)
    fun notify(ok: Boolean) {
        Toast.makeText(context, if (ok) R.string.fpt_applied else R.string.fpt_failed, Toast.LENGTH_LONG).show()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    // Keep expected format failures inside the loading job: its async child must
                    // not cancel the remembered dialog scope and prevent later imports.
                    val theme = loading.withLoading {
                        runCatching { ThemePackImporter.prepare(context, uri) }
                    }.getOrThrow()
                    theme.use {
                        val metadata = theme.plan.metadata
                        val warnings = theme.plan.warnings
                        val details = buildString {
                            append(String.format(detailsFormat, metadata.name.ifBlank { unknownName },
                                metadata.author, metadata.version, theme.files.size))
                            if (metadata.description.isNotBlank()) append("\n\n" + metadata.description.take(500))
                            if (warnings.isNotEmpty()) {
                                append("\n\n$warningHeader\n")
                                append(warnings.take(8).joinToString("\n") { it.take(120) })
                                if (warnings.size > 8) append("\n" + String.format(moreFormat, warnings.size - 8))
                            }
                        }
                        if (confirm.awaitConfirm(title, details, confirm = applyText, dismiss = cancelText) == ConfirmResult.Confirmed) {
                            val result = loading.withLoading { ThemePackImporter.apply(context, theme) }
                            if (result.isSuccess) latestOnApplied()
                            notify(result.isSuccess)
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    notify(false)
                } finally { busy = false }
            }
        }
    }
    return ThemePackActions(busy,
        onImport = { if (!busy) picker.launch(arrayOf("*/*")) },
        onReset = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        if (confirm.awaitConfirm(resetTitle, resetMessage, confirm = applyText, dismiss = cancelText) == ConfirmResult.Confirmed) {
                            val result = loading.withLoading { ThemePackImporter.reset(context) }
                            if (result.isSuccess) latestOnApplied()
                            notify(result.isSuccess)
                        }
                    } finally { busy = false }
                }
            }
        },
    )
}
