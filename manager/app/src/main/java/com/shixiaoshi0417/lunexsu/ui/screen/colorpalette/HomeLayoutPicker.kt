package com.shixiaoshi0417.lunexsu.ui.screen.colorpalette

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.model.HomeLayoutStyle
import com.shixiaoshi0417.lunexsu.ui.theme.FolkShape

/** The localised name of a home layout token. */
@Composable
internal fun homeLayoutLabel(style: String): String = stringResource(
    when (style) {
        HomeLayoutStyle.GRID -> R.string.settings_home_layout_grid
        HomeLayoutStyle.FOCUS -> R.string.settings_home_layout_focus
        HomeLayoutStyle.DASHBOARD -> R.string.settings_home_layout_dashboard
        HomeLayoutStyle.STATS -> R.string.settings_home_layout_stats
        else -> R.string.settings_home_layout_circle
    }
)

/**
 * A row of miniature home screens, one per layout, drawn in the current theme colors so the
 * picker previews both the arrangement and the palette. Selecting one applies it immediately.
 */
@Composable
internal fun HomeLayoutPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(HomeLayoutStyle.supported) { style ->
            val isSelected = style == selected
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "home_layout_border",
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onSelect(style)
                    },
                    modifier = Modifier
                        .size(width = 88.dp, height = 156.dp)
                        .semantics {
                            role = Role.RadioButton
                            this.selected = isSelected
                        },
                    shape = FolkShape.Corner16,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
                ) {
                    Column(
                        modifier = Modifier.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LayoutSchematic(style)
                    }
                }
                Text(
                    text = homeLayoutLabel(style),
                    modifier = Modifier.padding(top = 6.dp).width(88.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

private val BlockShape = RoundedCornerShape(4.dp)

@Composable
private fun Block(modifier: Modifier, color: Color = MaterialTheme.colorScheme.surfaceBright) {
    Box(modifier = modifier.background(color, BlockShape))
}

@Composable
private fun RowScope.HalfBlock(height: Int, color: Color = MaterialTheme.colorScheme.surfaceBright) {
    Block(Modifier.weight(1f).height(height.dp), color)
}

/** A rough drawing of each layout's board, matching the order its real cards appear in. */
@Composable
private fun ColumnScope.LayoutSchematic(style: String) {
    val accent = MaterialTheme.colorScheme.secondaryContainer
    val primary = MaterialTheme.colorScheme.primary
    val full = Modifier.fillMaxWidth()
    when (style) {
        HomeLayoutStyle.GRID -> {
            Row(full.height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Block(Modifier.weight(1f).fillMaxHeight(), accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Block(full.weight(1f))
                    Block(full.weight(1f))
                }
            }
            Block(full.weight(1f))
        }

        HomeLayoutStyle.FOCUS -> {
            repeat(4) { index ->
                Box(full.weight(1f).background(MaterialTheme.colorScheme.surfaceBright, BlockShape)) {
                    Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(primary, CircleShape))
                        Block(Modifier.padding(start = 3.dp).width(28.dp).height(3.dp), MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (index == 2) {
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            repeat(3) { Box(Modifier.size(9.dp).background(primary.copy(alpha = 0.5f), CircleShape)) }
                        }
                    }
                }
            }
        }

        HomeLayoutStyle.DASHBOARD -> {
            Box(full.height(46.dp).background(MaterialTheme.colorScheme.surfaceBright, BlockShape)) {
                Box(Modifier.padding(5.dp).size(10.dp).background(primary, CircleShape))
                Row(
                    Modifier.align(Alignment.BottomStart).padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    repeat(3) { Block(Modifier.width(18.dp).height(4.dp), MaterialTheme.colorScheme.outlineVariant) }
                }
            }
            Row(full, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HalfBlock(18)
                HalfBlock(18)
            }
            Block(full.weight(1f))
        }

        HomeLayoutStyle.STATS -> {
            Block(full.height(18.dp), accent)
            Box(full.height(40.dp).background(MaterialTheme.colorScheme.surfaceBright, BlockShape)) {
                Block(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(3.dp),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
                )
            }
            Row(
                full.height(30.dp).background(MaterialTheme.colorScheme.surfaceBright, BlockShape).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp).background(primary.copy(alpha = 0.7f), CircleShape))
            }
            Block(full.weight(1f))
        }

        else -> {
            Block(full.height(22.dp), accent)
            Row(full, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HalfBlock(18)
                HalfBlock(18)
            }
            Block(full.weight(1f))
        }
    }
}
