package com.shixiaoshi0417.lunexsu.ui.screen.flash

import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.RemoveModerator
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.util.FlashResult
import com.shixiaoshi0417.lunexsu.ui.util.LkmSelection
import com.shixiaoshi0417.lunexsu.ui.util.downloadBoot
import com.shixiaoshi0417.lunexsu.ui.util.flashModule
import com.shixiaoshi0417.lunexsu.ui.util.installBoot
import com.shixiaoshi0417.lunexsu.ui.util.restoreBoot
import com.shixiaoshi0417.lunexsu.ui.util.uninstallPermanently
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

enum class UninstallType(val icon: ImageVector, val title: Int, val message: Int) {
    TEMPORARY(
        Icons.Rounded.RemoveModerator,
        R.string.settings_uninstall_temporary,
        R.string.settings_uninstall_temporary_message
    ),
    PERMANENT(
        Icons.Rounded.DeleteForever,
        R.string.settings_uninstall_permanent,
        R.string.settings_uninstall_permanent_message
    ),
    RESTORE_STOCK_IMAGE(
        Icons.Rounded.RestartAlt,
        R.string.settings_restore_stock_image,
        R.string.settings_restore_stock_image_message
    ),
    NONE(Icons.Rounded.Adb, 0, 0)
}

@Parcelize
sealed class FlashIt : Parcelable {
    @Parcelize
    data class FlashBoot(
        val boot: Uri? = null,
        val lkm: LkmSelection,
        val ota: Boolean,
        val partition: String? = null,
        val allowShell: Boolean = false,
        val enableAdb: Boolean = false,
        val backup: Boolean = false,
        val superkeyRecord: ByteArray? = null,
    ) : FlashIt()

    @Parcelize
    data class DownloadBoot(
        val url: String,
        val partition: String,
        val lkm: LkmSelection,
        val allowShell: Boolean = false,
        val enableAdb: Boolean = false,
        val backup: Boolean = false,
        val superkeyRecord: ByteArray? = null,
    ) : FlashIt()

    @Parcelize
    data class FlashModules(val uris: List<Uri>) : FlashIt()

    @Parcelize
    data object FlashRestore : FlashIt()

    @Parcelize
    data object FlashUninstall : FlashIt()
}

fun flashModulesSequentially(
    uris: List<Uri>,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    var needsReboot = false
    for (uri in uris) {
        flashModule(uri, onStdout, onStderr).apply {
            needsReboot = needsReboot || showReboot
            if (code != 0) {
                return FlashResult(code, err, needsReboot)
            }
        }
    }
    return FlashResult(0, "", needsReboot)
}

fun flashIt(
    flashIt: FlashIt,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    return when (flashIt) {
        is FlashIt.FlashBoot -> installBoot(
            flashIt.boot,
            flashIt.lkm,
            flashIt.ota,
            flashIt.partition,
            flashIt.allowShell,
            flashIt.enableAdb,
            flashIt.backup,
            onStdout,
            onStderr,
            flashIt.superkeyRecord,
        )

        is FlashIt.DownloadBoot -> downloadBoot(
            flashIt.url,
            flashIt.partition,
            flashIt.lkm,
            flashIt.allowShell,
            flashIt.enableAdb,
            flashIt.backup,
            onStdout,
            onStderr,
            flashIt.superkeyRecord,
        )

        is FlashIt.FlashModules -> {
            flashModulesSequentially(flashIt.uris, onStdout, onStderr)
        }

        FlashIt.FlashRestore -> restoreBoot(onStdout, onStderr)
        FlashIt.FlashUninstall -> uninstallPermanently(onStdout, onStderr)
    }
}

fun saveLog(
    logContent: () -> String,
    scope: CoroutineScope,
    showMessage: (String) -> Unit
): () -> Unit {
    return {
        scope.launch {
            val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            val date = format.format(Date())
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LunexSU_install_log_${date}.log"
            )
            file.writeText(logContent())
            showMessage("Log saved to ${file.absolutePath}")
        }
    }
}

private const val JAILBREAK_WARNING_COUNTDOWN = 10

@Composable
fun JailbreakFlashWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var countdown by remember { mutableIntStateOf(JAILBREAK_WARNING_COUNTDOWN) }

    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000.milliseconds)
            countdown--
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(android.R.string.dialog_alert_title)) },
        text = {
            Text(
                stringResource(R.string.jailbreak_flash_warning),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = countdown == 0
            ) {
                Text(
                    if (countdown > 0)
                        stringResource(R.string.jailbreak_flash_warning_countdown, countdown)
                    else
                        stringResource(R.string.install_next)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
