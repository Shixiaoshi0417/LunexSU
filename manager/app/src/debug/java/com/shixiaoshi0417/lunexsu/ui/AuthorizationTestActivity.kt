package com.shixiaoshi0417.lunexsu.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.data.model.AppInfo
import com.shixiaoshi0417.lunexsu.ui.screen.appprofile.*
import com.shixiaoshi0417.lunexsu.ui.screen.superuser.GroupedApps
import com.shixiaoshi0417.lunexsu.ui.theme.LunexSUTheme
import top.yukonga.miuix.kmp.basic.Scaffold

/** Debug-only UI fixture: actual App Profile pages, injected storage, no Root ioctls. */
class AuthorizationTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = if (intent.getStringExtra("ui") == "miuix") UiMode.Miuix else UiMode.Material
        val reject = intent.getBooleanExtra("reject", false)
        val preferences = getSharedPreferences("authorization_ui_fixture", MODE_PRIVATE)
        val permanent = Natives.Profile("bin.mt.plus", 10387, allowSu = true,
            authorizationState = 1, authorizationGeneration = 7, authorizationSupported = true)
        val initial = if (intent.getBooleanExtra("restore", false)) permanent.copy(
            allowSu = preferences.getBoolean("allowed", false),
            authorizationMode = preferences.getInt("mode", 0),
            authorizationDurationSeconds = preferences.getLong("seconds", 300),
            authorizationGeneration = preferences.getLong("generation", 7),
        ) else permanent
        val packageInfo = PackageInfo().apply {
            packageName = initial.name
            versionName = "test fixture"
            applicationInfo = ApplicationInfo(this@AuthorizationTestActivity.applicationInfo).apply { uid = 10387 }
        }
        val app = AppInfo("Authorization UI fixture", packageInfo, initial)
        setContent {
            var profile by remember { mutableStateOf(initial) }
            var saving by remember { mutableStateOf(false) }
            var stored by remember { mutableStateOf(initial) }
            val repository = remember {
                AuthorizationRepository({ stored }, { candidate ->
                    delay(100)
                    if (reject) -5 else {
                        stored = candidate.copy(authorizationOperation = 0,
                            authorizationGeneration = stored.authorizationGeneration + 1,
                            authorizationState = if (candidate.allowSu) 1 else 0,
                            authorizationRemainingSeconds = if (candidate.authorizationMode == 2 && candidate.allowSu)
                                candidate.authorizationDurationSeconds else 0)
                        0
                    }
                })
            }
            val scope = rememberCoroutineScope()
            val snackbar = remember { SnackbarHostState() }
            val actions = AppProfileActions(::finish, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}, { candidate ->
                if (!saving) {
                    saving = true
                    scope.launch {
                        try {
                            val result = repository.save(candidate)
                            profile = result.profile
                            preferences.edit().putInt("mode", profile.authorizationMode)
                                .putLong("seconds", profile.authorizationDurationSeconds)
                                .putBoolean("allowed", profile.allowSu).putInt("error", result.error)
                                .putLong("generation", profile.authorizationGeneration).commit()
                            val message = if (result.error == 0) "Saved and reloaded" else "Save failed: ${result.error}"
                            if (mode == UiMode.Miuix) Toast.makeText(this@AuthorizationTestActivity, message, Toast.LENGTH_SHORT).show()
                            else scope.launch { snackbar.showSnackbar(message) }
                        } finally { saving = false }
                    }
                }
            })
            val group = GroupedApps(10387, listOf(app), app, profile.allowSu, false, true)
            val state = AppProfileUiState(10387, initial.name, profile, group, "", saving)
            CompositionLocalProvider(LocalUiMode provides mode) {
                LunexSUTheme(uiMode = mode) {
                    when (mode) {
                        UiMode.Material -> AppProfileScreenMaterial(state, actions, snackbar)
                        UiMode.Miuix -> Scaffold { AppProfileScreenMiuix(state, actions) }
                    }
                }
            }
        }
    }
}
