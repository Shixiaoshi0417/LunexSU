package com.shixiaoshi0417.lunexsu.ui.screen.sui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles
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
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            startAction = {
                                Icon(Icons.Rounded.Api, null, Modifier.padding(end = 6.dp), tint = colorScheme.onBackground)
                            },
                        ) {
                            Text(
                                stringResource(R.string.sui_payload_version, ui.status.version.ifEmpty { "Sui" }),
                                style = textStyles.headline1,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurface,
                            )
                            Column(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(suiStatusText(ui), style = textStyles.body2, color = colorScheme.onSurfaceVariantSummary)
                                ui.error?.let { Text(it, style = textStyles.body2, color = colorScheme.error) }
                                listOfNotNull(
                                    ui.status.reason.takeIf { it.isNotBlank() },
                                    stringResource(R.string.sui_request_dialog),
                                    stringResource(R.string.sui_demo).takeIf { BuildConfig.NO_ROOT_DEMO },
                                ).forEach { Text(it, style = textStyles.body2, color = colorScheme.onSurfaceVariantSummary) }
                            }
                        }
                    }
                }
                item(key = "search") { search(Modifier.padding(top = 12.dp)) }
                if (apps.isNotEmpty()) item(key = "header") {
                    SmallTitle(text = stringResource(R.string.sui_apps), modifier = Modifier.padding(top = 6.dp))
                }
                items(apps, key = { it.uid }) { app ->
                    Card(modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth()) {
                        BasicComponent(
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            enabled = editable,
                            onClick = { onEdit(app) },
                            endActions = {
                                val layoutDirection = LocalLayoutDirection.current
                                Icon(
                                    imageVector = MiuixIcons.Basic.ArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(width = 10.dp, height = 16.dp).graphicsLayer {
                                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                    },
                                    tint = if (editable) colorScheme.onSurfaceVariantActions else colorScheme.disabledOnSecondaryVariant,
                                )
                            },
                        ) {
                            Text(
                                app.label,
                                style = textStyles.headline1,
                                fontWeight = FontWeight.Medium,
                                color = if (editable) colorScheme.onSurface else colorScheme.disabledOnSecondaryVariant,
                                maxLines = if (editable) 1 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${stringResource(app.permission.label())} · UID ${app.uid} · ${app.packages}",
                                fontSize = 12.sp,
                                color = if (editable) colorScheme.onSurfaceVariantSummary else colorScheme.disabledOnSecondaryVariant,
                                maxLines = if (editable) 1 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                item(key = "bottom") {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.captionBar)))
                }
            }
        }
    }
}
