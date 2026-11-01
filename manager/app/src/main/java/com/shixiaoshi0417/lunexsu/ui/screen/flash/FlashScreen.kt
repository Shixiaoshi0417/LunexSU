package com.shixiaoshi0417.lunexsu.ui.screen.flash

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.repository.isSoftRebootPreferred
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.navigation3.LocalNavigator
import com.shixiaoshi0417.lunexsu.ui.util.reboot
import java.util.UUID

@Composable
fun FlashScreen(flashIt: FlashIt, onBackEnabledChange: (Boolean) -> Unit) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Activity-owned ViewModels need a distinct key for every navigation visit.
    val visit = rememberSaveable { UUID.randomUUID().toString() }
    val viewModel: FlashViewModel = viewModel(key = "flash:$visit")
    val operationState by viewModel.operation.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose {
            // Activity-owned visit holders remain until Activity destruction; release large logs on exit.
            if (activity?.isChangingConfigurations != true) viewModel.operation.releaseFinishedLog()
        }
    }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val needJailbreakWarning = flashIt is FlashIt.FlashBoot && Natives.isLateLoadMode
    // Soft reboot keeps the jailbreak and still applies modules
    val softReboot = flashIt is FlashIt.FlashModules && isSoftRebootPreferred()
    var flashingEnabled by rememberSaveable { mutableStateOf(!needJailbreakWarning) }
    val uiMode = LocalUiMode.current
    val snackbarHost = remember { SnackbarHostState() }
    val backEnabled = operationState.status != FlashingStatus.FLASHING

    SideEffect { onBackEnabledChange(backEnabled) }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = !backEnabled,
        onBackCompleted = {},
    )

    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(viewModel, flashingEnabled) {
        if (flashingEnabled) {
            val restoredAttempt = attempted
            attempted = true
            viewModel.start(flashIt, restoredAttempt)
        }
    }

    val state = FlashUiState(
        text = operationState.text,
        showRebootAction = operationState.showReboot,
        flashingStatus = operationState.status,
        showJailbreakWarning = needJailbreakWarning && !flashingEnabled,
        rebootLabelRes = if (softReboot) R.string.reboot_soft else R.string.reboot,
    )
    val actions = FlashScreenActions(
        onBack = dropUnlessResumed { if (backEnabled) navigator.pop() },
        onSaveLog = saveLog(viewModel.operation::logSnapshot, scope) { showMessage(it) },
        onReboot = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    reboot(if (softReboot) "soft_reboot" else "")
                }
            }
        },
        onConfirmJailbreakWarning = { flashingEnabled = true },
        onDismissJailbreakWarning = dropUnlessResumed { navigator.pop() },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> FlashScreenMiuix(state, actions)
        UiMode.Material -> FlashScreenMaterial(state, actions, snackbarHost)
    }
}
