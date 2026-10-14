// Adapted from FinaSU, formerly FolkSU (https://github.com/LyraVoid/FinaSU), GPL-3.0.
package com.shixiaoshi0417.lunexsu.ui.screen.colorpalette

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurCircular
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BorderOuter
import androidx.compose.material.icons.rounded.Brightness7
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.component.bottombar.FloatingBarConfig
import com.shixiaoshi0417.lunexsu.ui.component.bottombar.NavMode
import com.shixiaoshi0417.lunexsu.ui.component.bottombar.NavModeConfig
import com.shixiaoshi0417.lunexsu.ui.component.bottombar.rememberNavMode
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedColumn
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedDropdownItem
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedSliderItem
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedSwitchItem
import kotlin.math.roundToInt

/** Navigation mode and floating bar appearance, as in FinaSU's navigation settings. */
@Composable
internal fun NavModeSectionMaterial() {
    val navMode = rememberNavMode()
    val floatingRevision by FloatingBarConfig.revision.collectAsStateWithLifecycle()
    val floatingStyle = remember(floatingRevision) { FloatingBarConfig.style }
    val floatingCompact = remember(floatingRevision) { FloatingBarConfig.compact }
    val floatingGlass = remember(floatingRevision) { FloatingBarConfig.glass }
    val floatingAutoHide = remember(floatingRevision) { FloatingBarConfig.autoHide }
    val floatingSwipeHide = remember(floatingRevision) { FloatingBarConfig.swipeHide }
    val glassBlurStrength = remember(floatingRevision) { FloatingBarConfig.glassBlurStrength }
    val glassTransparency = remember(floatingRevision) { FloatingBarConfig.glassTransparency }
    val glassHighlightStrength = remember(floatingRevision) { FloatingBarConfig.glassHighlightStrength }
    val glassSpecular = remember(floatingRevision) { FloatingBarConfig.glassSpecular }
    val glassInnerGlow = remember(floatingRevision) { FloatingBarConfig.glassInnerGlow }
    val glassBorder = remember(floatingRevision) { FloatingBarConfig.glassBorder }
    val toPercent: (Float) -> String = { "${(it * 100).roundToInt()}%" }
    val navModes = NavMode.entries
    val navModeLabels = listOf(
        stringResource(R.string.settings_nav_mode_auto),
        stringResource(R.string.settings_nav_mode_bottom),
        stringResource(R.string.settings_nav_mode_rail),
        stringResource(R.string.settings_nav_mode_floating),
    )
    val styles = FloatingBarConfig.Style.entries
    val styleLabels = listOf(
        stringResource(R.string.settings_floating_bar_style_standard),
        stringResource(R.string.settings_floating_bar_style_drawer),
    )

    SegmentedColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        content = buildList<@Composable () -> Unit> {
            add {
                SegmentedDropdownItem(
                    icon = Icons.Rounded.SpaceDashboard,
                    title = stringResource(R.string.settings_nav_mode),
                    summary = stringResource(R.string.settings_nav_mode_summary),
                    items = navModeLabels,
                    selectedIndex = navModes.indexOf(navMode).coerceAtLeast(0),
                    onItemSelected = { NavModeConfig.mode = navModes[it] },
                )
            }
            if (navMode == NavMode.Floating) {
                add {
                    SegmentedDropdownItem(
                        icon = Icons.Rounded.Style,
                        title = stringResource(R.string.settings_floating_bar_style),
                        items = styleLabels,
                        selectedIndex = styles.indexOf(floatingStyle).coerceAtLeast(0),
                        onItemSelected = { FloatingBarConfig.style = styles[it] },
                    )
                }
                add {
                    SegmentedSwitchItem(
                        icon = Icons.Rounded.BlurOn,
                        title = stringResource(R.string.settings_floating_bar_glass),
                        summary = stringResource(R.string.settings_floating_bar_glass_summary),
                        checked = floatingGlass,
                        onCheckedChange = { FloatingBarConfig.glass = it },
                    )
                }
                if (floatingGlass) {
                    add {
                        SegmentedSliderItem(
                            icon = Icons.Rounded.BlurCircular,
                            title = stringResource(R.string.settings_navbar_glass_blur_strength),
                            value = glassBlurStrength,
                            valueRange = 0f..1f,
                            valueText = toPercent,
                            onValueChangeFinished = { FloatingBarConfig.glassBlurStrength = it },
                        )
                    }
                    add {
                        SegmentedSliderItem(
                            icon = Icons.Rounded.Opacity,
                            title = stringResource(R.string.settings_navbar_glass_transparency),
                            value = glassTransparency,
                            valueRange = 0f..1f,
                            valueText = toPercent,
                            onValueChangeFinished = { FloatingBarConfig.glassTransparency = it },
                        )
                    }
                    add {
                        SegmentedSliderItem(
                            icon = Icons.Rounded.Brightness7,
                            title = stringResource(R.string.settings_navbar_glass_highlight_strength),
                            value = glassHighlightStrength,
                            valueRange = 0f..1f,
                            valueText = toPercent,
                            onValueChangeFinished = { FloatingBarConfig.glassHighlightStrength = it },
                        )
                    }
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Rounded.FlashOn,
                            title = stringResource(R.string.settings_navbar_glass_specular),
                            summary = stringResource(R.string.settings_navbar_glass_specular_summary),
                            checked = glassSpecular,
                            onCheckedChange = { FloatingBarConfig.glassSpecular = it },
                        )
                    }
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Rounded.WbTwilight,
                            title = stringResource(R.string.settings_navbar_glass_inner_glow),
                            summary = stringResource(R.string.settings_navbar_glass_inner_glow_summary),
                            checked = glassInnerGlow,
                            onCheckedChange = { FloatingBarConfig.glassInnerGlow = it },
                        )
                    }
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Rounded.BorderOuter,
                            title = stringResource(R.string.settings_navbar_glass_border),
                            summary = stringResource(R.string.settings_navbar_glass_border_summary),
                            checked = glassBorder,
                            onCheckedChange = { FloatingBarConfig.glassBorder = it },
                        )
                    }
                } else {
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Rounded.RoundedCorner,
                            title = stringResource(R.string.settings_floating_bar_compact),
                            checked = floatingCompact,
                            onCheckedChange = { FloatingBarConfig.compact = it },
                        )
                    }
                }
                add {
                    SegmentedSwitchItem(
                        icon = Icons.Rounded.VisibilityOff,
                        title = stringResource(R.string.settings_floating_auto_hide),
                        summary = stringResource(R.string.settings_floating_auto_hide_summary),
                        checked = floatingAutoHide,
                        onCheckedChange = { FloatingBarConfig.autoHide = it },
                    )
                }
                add {
                    SegmentedSwitchItem(
                        icon = Icons.Rounded.SwipeUp,
                        title = stringResource(R.string.settings_floating_swipe_hide),
                        summary = stringResource(R.string.settings_floating_swipe_hide_summary),
                        checked = floatingSwipeHide,
                        onCheckedChange = { FloatingBarConfig.swipeHide = it },
                    )
                }
            }
        }
    )
}
