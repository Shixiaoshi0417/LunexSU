package com.shixiaoshi0417.lunexsu.ui.screen.appprofile

import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.navigation3.LocalNavigator
import com.shixiaoshi0417.lunexsu.ui.navigation3.Route
import com.shixiaoshi0417.lunexsu.ui.util.forceStopApp
import com.shixiaoshi0417.lunexsu.ui.util.getSepolicy
import com.shixiaoshi0417.lunexsu.ui.util.launchApp
import com.shixiaoshi0417.lunexsu.ui.util.restartApp
import com.shixiaoshi0417.lunexsu.ui.util.setSepolicy
import com.shixiaoshi0417.lunexsu.ui.viewmodel.SuperUserViewModel
import com.shixiaoshi0417.lunexsu.ui.viewmodel.getTemplateInfoById

@Composable
fun AppProfileScreen(uid: Int) {
    val uiMode = LocalUiMode.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val viewModel: SuperUserViewModel = viewModel()
    val appGroupState = remember(uid) {
        derivedStateOf {
            viewModel.uiState.value.groupedApps.find { it.uid == uid } ?: SuperUserViewModel.getGroupedApp(uid)
        }
    }
    val appGroup = appGroupState.value
    val primaryAppInfo = appGroup?.primary
    if (primaryAppInfo == null) {
        LaunchedEffect(Unit) {
            navigator.pop()
        }
        return
    }

    val packageName = primaryAppInfo.profileKey
    val sharedUserId = remember(uid) {
        primaryAppInfo.packageInfo.sharedUserId
            ?: appGroup.apps.firstOrNull { it.packageInfo.sharedUserId != null }?.packageInfo?.sharedUserId
            ?: ""
    }

    val initialProfile = remember(uid, packageName, primaryAppInfo.special) {
        Natives.getAppProfile(packageName, uid).let {
            if (primaryAppInfo.special) it.copy(allowSu = false) else it
        }.also {
            if (it.allowSu && !primaryAppInfo.special) {
                it.rules = getSepolicy(packageName)
            }
        }
    }
    var profile by rememberSaveable(uid, packageName) {
        mutableStateOf(initialProfile)
    }
    var saving by remember(uid, packageName) { mutableStateOf(false) }
    val repository = remember(uid, packageName) {
        AuthorizationRepository(
            read = { withContext(Dispatchers.IO) { Natives.getAppProfile(packageName, uid) } },
            write = { withContext(Dispatchers.IO) { Natives.setAppProfileResult(it) } },
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(uid, packageName, lifecycleOwner) {
        if (!shouldPollAuthorization(primaryAppInfo.special, initialProfile)) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(1000)
                if (!saving) {
                    val fresh = repository.refresh()
                    if (!saving) profile = fresh.copy(rules = profile.rules)
                }
            }
        }
    }

    val failToUpdateAppProfile = stringResource(R.string.failed_to_update_app_profile).format(primaryAppInfo.label)
    val failToUpdateSepolicy = stringResource(R.string.failed_to_update_sepolicy).format(primaryAppInfo.label)
    val suNotAllowed = stringResource(R.string.su_not_allowed).format(primaryAppInfo.label)
    val savedMessage = stringResource(R.string.authorization_saved)

    fun showMessage(message: String) {
        scope.launch {
            if (uiMode == UiMode.Material) {
                snackbarHost.showSnackbar(message)
            } else {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val state = AppProfileUiState(
        uid = uid,
        packageName = packageName,
        profile = profile,
        appGroup = appGroup,
        sharedUserId = sharedUserId,
        saving = saving,
    )

    val actions = AppProfileActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onLaunchApp = ::launchApp,
        onForceStopApp = ::forceStopApp,
        onRestartApp = ::restartApp,
        onViewTemplate = { templateId ->
            getTemplateInfoById(templateId)?.let { info ->
                navigator.push(Route.TemplateEditor(info, true))
            }
        },
        onManageTemplate = {
            navigator.push(Route.AppProfileTemplate)
        },
        onProfileChange = { updatedProfile ->
            if (!saving) {
                saving = true
                scope.launch {
                    try {
                        var profileToSave = if (primaryAppInfo.special) {
                            updatedProfile.copy(allowSu = false)
                        } else {
                            updatedProfile
                        }
                        // Re-enabling a revoked temporary grant is an explicit rearm.
                        // Ordinary edits to an expired grant preserve its deadline.
                        if (!profile.allowSu && profileToSave.allowSu) {
                            profileToSave = profileToSave.copy(authorizationOperation = 1)
                        }
                        if (profileToSave.authorizationOperation == 1) {
                            profileToSave = profileToSave.copy(name = packageName)
                        }
                        if (profileToSave.allowSu) {
                            if (uid < 2000 && uid != 1000) {
                                showMessage(suNotAllowed)
                                return@launch
                            }
                            if (!profileToSave.rootUseDefault
                                && profileToSave.rules.isNotEmpty()
                                && !primaryAppInfo.special
                                && !setSepolicy(profileToSave.name, profileToSave.rules)
                            ) {
                                showMessage(failToUpdateSepolicy)
                                return@launch
                            }
                        }
                        val result = repository.save(profileToSave)
                        val authorizationChanged = profileToSave.authorizationOperation == 1 ||
                            (profile.allowSu && !profileToSave.allowSu)
                        profile = result.profile.copy(rules = if (result.error == 0) profileToSave.rules else profile.rules)
                        if (result.error != 0) {
                            showMessage("$failToUpdateAppProfile (${android.system.Os.strerror(-result.error)}, ${result.error})")
                        } else {
                            if (authorizationChanged) showMessage(savedMessage)
                            viewModel.loadAppList()
                        }
                    } finally {
                        saving = false
                    }
                }
            }
        },
    )

    when (uiMode) {
        UiMode.Miuix -> AppProfileScreenMiuix(
            state = state,
            actions = actions,
        )

        UiMode.Material -> AppProfileScreenMaterial(
            state = state,
            actions = actions,
            snackBarHost = snackbarHost,
        )
    }
}
