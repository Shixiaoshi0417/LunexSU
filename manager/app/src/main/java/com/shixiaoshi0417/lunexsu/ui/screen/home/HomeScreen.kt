package com.shixiaoshi0417.lunexsu.ui.screen.home

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.HomeMetrics
import com.shixiaoshi0417.lunexsu.data.SavedSuperkey
import com.shixiaoshi0417.lunexsu.data.SuperkeyAuthenticationResult
import com.shixiaoshi0417.lunexsu.ui.util.KsuCli
import kotlinx.coroutines.CancellationException
import com.shixiaoshi0417.lunexsu.data.model.HomeLayoutStyle
import com.shixiaoshi0417.lunexsu.magica.MagicaService
import com.shixiaoshi0417.lunexsu.ui.LocalMainPagerState
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.component.bottombar.BottomBarDestination
import com.shixiaoshi0417.lunexsu.ui.component.dialog.rememberLoadingDialog
import com.shixiaoshi0417.lunexsu.ui.navigation3.Navigator
import com.shixiaoshi0417.lunexsu.ui.navigation3.Route
import com.shixiaoshi0417.lunexsu.ui.viewmodel.HomeViewModel
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFinaThemeUi
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun HomePager(
    navigator: Navigator,
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
    superuserCount: Int = 0,
    moduleEnabledCount: Int = 0,
) {
    val viewModel = viewModel<HomeViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val uiMode = LocalUiMode.current
    val mainPagerState = LocalMainPagerState.current
    val metrics = if (
        uiMode == UiMode.Material && isCurrentPage &&
        HomeLayoutStyle.usesMetrics(LocalHomeLayoutOptions.current.style)
    ) {
        viewModel.metrics.collectAsStateWithLifecycle().value
    } else {
        HomeMetrics()
    }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val loadingDialog = rememberLoadingDialog()
    val scope = rememberCoroutineScope()
    val latestIsCurrentPage by rememberUpdatedState(isCurrentPage)
    val initialResumeHandled = rememberSaveable { mutableStateOf(false) }
    var showSuperkey by remember { mutableStateOf(false) }
    var autoUnlocking by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try {
            when (SavedSuperkey.tryAutomatic()) {
                is SuperkeyAuthenticationResult.Accepted -> {
                    withContext(Dispatchers.IO) { KsuCli.refreshAfterAuthentication() }
                    viewModel.refresh()
                    (context as? android.app.Activity)?.recreate()
                }
                SuperkeyAuthenticationResult.Invalidated -> {
                    Toast.makeText(context, R.string.superkey_saved_invalid, Toast.LENGTH_LONG).show()
                    showSuperkey = true
                }
                else -> Unit
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            // Keystore/unavailable Core errors never block the manual entry.
            Toast.makeText(context, R.string.superkey_auto_failed, Toast.LENGTH_LONG).show()
            showSuperkey = true
        } finally { autoUnlocking = false }
    }
    if (showSuperkey && !autoUnlocking) {
        SuperkeyDialog(
            onDismiss = { showSuperkey = false },
            onAuthenticated = {
                showSuperkey = false
                viewModel.refresh()
                (context as? android.app.Activity)?.recreate()
            },
        )
    }

    var hasActivated by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage && !hasActivated) {
            hasActivated = true
            viewModel.refresh()
        }
    }

    LifecycleResumeEffect(Unit) {
        if (initialResumeHandled.value && latestIsCurrentPage) {
            viewModel.refresh()
        }
        initialResumeHandled.value = true
        onPauseOrDispose { }
    }

    val actions = HomeActions(
        onInstallClick = { navigator.push(Route.Install) },
        onOpenUrl = uriHandler::openUri,
        onJailbreakClick = {
            loadingDialog.showLoading()
            context.startService(Intent(context, MagicaService::class.java))
            // Manager will be force-stopped and restarted by late-load on success.
            // If that doesn't happen within timeout, jailbreak likely failed.
            scope.launch(Dispatchers.IO) {
                delay(30_000.milliseconds)
                withContext(Dispatchers.Main) {
                    loadingDialog.hide()
                    Toast.makeText(context, R.string.jailbreak_timeout, Toast.LENGTH_LONG).show()
                }
            }
        },
        onOpenSuperUser = { mainPagerState.animateToPage(BottomBarDestination.SuperUser.ordinal) },
        onOpenModule = { mainPagerState.animateToPage(BottomBarDestination.Module.ordinal) },
        onSuperkeyClick = { showSuperkey = true },
    )

    if (LocalFinaThemeUi.current) {
        FinaThemeHome(uiState, actions, bottomInnerPadding, superuserCount, moduleEnabledCount, metrics)
    } else when (uiMode) {
        UiMode.Miuix -> HomePagerMiuix(
            state = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
        )

        UiMode.Material -> HomePagerMaterial(
            state = uiState,
            actions = actions,
            bottomInnerPadding = bottomInnerPadding,
            superuserCount = superuserCount,
            moduleEnabledCount = moduleEnabledCount,
            metrics = metrics,
        )
    }
}
