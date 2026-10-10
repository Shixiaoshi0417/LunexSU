package me.weishu.kernelsu.ui.screen.font

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.dialog.rememberConfirmDialog
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.ui.theme.FontConfig
import me.weishu.kernelsu.ui.theme.FontMode

/** Sample text shown in the font preview, so Latin, CJK and digits can all be judged at once. */
internal const val FONT_PREVIEW_SAMPLE = "Aa Bb Cc 123 · 字体预览 · フォント"

@Immutable
data class FontScreenState(
    val fontMode: FontMode,
    val hasCustomFont: Boolean,
)

@Immutable
data class FontScreenActions(
    val onBack: () -> Unit,
    val onSelectSystem: () -> Unit,
    val onSelectCustom: () -> Unit,
    val onImportFont: () -> Unit,
    val onRestoreDefault: () -> Unit,
)

/**
 * The font setting shared by both UIs (adapted from FolkSU's FontScreen, GPL-3.0): the system font
 * or an imported one. Selecting "custom" before a file was imported opens the picker, so the mode
 * never silently renders the system font. The change applies at once through [FontConfig].
 */
@Composable
fun FontScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    val restoreTitle = stringResource(R.string.font_restore_default)
    val restoreMessage = stringResource(R.string.font_restore_default_confirm)
    val confirmText = stringResource(android.R.string.ok)
    val cancelText = stringResource(android.R.string.cancel)
    val restoreDialog = rememberConfirmDialog(onConfirm = {
        FontConfig.restoreDefault(context)
        Toast.makeText(context, R.string.font_restored, Toast.LENGTH_SHORT).show()
    })

    val pickFontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importFont(context, scope, uri)
    }

    FontConfig.ensureLoaded(context)
    val state = FontScreenState(
        fontMode = FontConfig.fontMode,
        hasCustomFont = FontConfig.customFontFilename != null,
    )
    val actions = FontScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onSelectSystem = { FontConfig.setFontMode(context, FontMode.SYSTEM_DEFAULT) },
        onSelectCustom = {
            if (!FontConfig.setFontMode(context, FontMode.CUSTOM)) pickFontLauncher.launch("*/*")
        },
        onImportFont = { pickFontLauncher.launch("*/*") },
        onRestoreDefault = {
            restoreDialog.showConfirm(
                title = restoreTitle,
                content = restoreMessage,
                confirm = confirmText,
                dismiss = cancelText,
            )
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> FontScreenMiuix(state, actions)
        UiMode.Material -> FontScreenMaterial(state, actions)
    }
}

private fun importFont(context: Context, scope: CoroutineScope, uri: Uri) {
    scope.launch {
        val ok = withContext(Dispatchers.IO) { FontConfig.importFont(context, uri) }
        Toast.makeText(
            context,
            if (ok) R.string.font_applied else R.string.font_apply_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }
}
