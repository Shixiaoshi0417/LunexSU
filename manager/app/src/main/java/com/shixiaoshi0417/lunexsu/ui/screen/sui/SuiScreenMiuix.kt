package com.shixiaoshi0417.lunexsu.ui.screen.sui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.BuildConfig
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.sui.SuiApp
import com.shixiaoshi0417.lunexsu.data.sui.SuiUiState
import com.shixiaoshi0417.lunexsu.ui.theme.LocalEnableBlur
import com.shixiaoshi0417.lunexsu.ui.util.BlurredBar
import com.shixiaoshi0417.lunexsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** Native MIUIX layout, matching Settings: blurred collapsing bar and native cards. */
@Composable
internal fun SuiScreenMiuix(
    ui: SuiUiState,
    apps: List<SuiApp>,
    editable: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: (SuiApp) -> Unit,
    search: @Composable (Modifier) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.sui_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                    actions = {
                        IconButton(enabled = !ui.busy, onClick = onRefresh) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.susfs_refresh), tint = colorScheme.onBackground)
                        }
                    },
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .imePadding()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = inner,
                overscrollEffect = null,
            ) {
                item(key = "status") {
                    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                        BasicComponent(
                            title = stringResource(R.string.sui_payload_version, ui.status.version.ifEmpty { "Sui" }),
                            summary = suiStatusText(ui),
                            startAction = {
                                Icon(Icons.Rounded.Api, null, Modifier.padding(end = 6.dp), tint = colorScheme.onBackground)
                            },
                        )
                        ui.error?.let {
                            Text(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), color = colorScheme.error)
                        }
                        listOfNotNull(
                            ui.status.reason.takeIf { it.isNotBlank() },
                            stringResource(R.string.sui_host_required).takeIf { !ui.hostAttached },
                            stringResource(R.string.sui_demo).takeIf { BuildConfig.NO_ROOT_DEMO },
                        ).forEach { BasicComponent(summary = it) }
                    }
                }
                item(key = "search") { search(Modifier.padding(top = 12.dp)) }
                if (apps.isNotEmpty()) item(key = "header") {
                    SmallTitle(text = stringResource(R.string.sui_apps), modifier = Modifier.padding(top = 6.dp))
                }
                items(apps, key = { it.uid }) { app ->
                    Card(modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth()) {
                        ArrowPreference(
                            title = app.label,
                            summary = "${stringResource(app.permission.label())} · UID ${app.uid}\n${app.packages}",
                            enabled = editable,
                            onClick = { onEdit(app) },
                        )
                    }
                }
                item(key = "bottom") {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.captionBar)))
                }
            }
        }
    }
}
