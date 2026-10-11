package com.shixiaoshi0417.lunexsu.ui.component.material

import com.shixiaoshi0417.lunexsu.ui.theme.pack.WallpaperMaterial
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.staticCompositionLocalOf
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFolkWallpaperTokens
import com.shixiaoshi0417.lunexsu.ui.theme.pack.WallpaperSurfaceRole
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState

val LocalListItemShapes = compositionLocalOf<ListItemShapes?> { null }
private val SegmentedOuterRadius = 16.dp
private val SegmentedInnerRadius = 4.dp
private const val SegmentedSpringStiffness = 800f
private const val SegmentedSpringDamping = 0.9f

@DslMarker
annotation class SegmentedColumnDsl

/**
 * True inside [WallpaperGroupBackground]. Over a wallpaper a list group needs a single fill: the
 * [ListItemDefaults.SegmentedGap] between rows would otherwise leak the wallpaper through every seam,
 * and translucent rows over a translucent group would darken twice. (Adapted from FinaSU, GPL-3.0.)
 */
private val LocalWallpaperGroupActive = staticCompositionLocalOf { false }

@Composable
private fun WallpaperGroupBackground(content: @Composable () -> Unit) {
    val tokens = LocalFolkWallpaperTokens.current
    val material = wallpaperMaterialFor(tokens, LocalWallpaperMaterialRole.current, WallpaperSurfaceRole.Group)
    if (tokens == null) {
        content()
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (material != null) {
                    Modifier.background(material.fill, RoundedCornerShape(SegmentedOuterRadius))
                } else {
                    Modifier
                }
            )
    ) {
        CompositionLocalProvider(
            LocalWallpaperGroupActive provides true,
            LocalWallpaperMaterialRole provides WallpaperSurfaceRole.Group,
        ) {
            content()
        }
    }
}

/** A row's own fill over a wallpaper: none inside a material (the group already paints it), else the group material. */
internal fun segmentedRowFill(group: WallpaperMaterial, insideMaterial: Boolean): Color =
    if (insideMaterial) Color.Transparent else group.fill

@Composable
private fun defaultSegmentedColors(): ListItemColors {
    // Over a wallpaper the group background carries the material; rows inside it are transparent.
    val material = LocalFolkWallpaperTokens.current?.group
    if (material != null) {
        val fill = segmentedRowFill(material, LocalWallpaperGroupActive.current || LocalWallpaperMaterialRole.current != null)
        return ListItemDefaults.segmentedColors(
            containerColor = fill,
            contentColor = material.contentColor,
            disabledContainerColor = fill,
            supportingContentColor = material.supportingColor,
        )
    }
    return ListItemDefaults.segmentedColors(
        containerColor = colorScheme.surfaceBright,
        disabledContainerColor = colorScheme.surfaceBright,
        supportingContentColor = colorScheme.onSurfaceVariant
    )
}

@Composable
private fun defaultSingleSegmentedShape(index: Int, count: Int): ListItemShapes {
    val base = ListItemDefaults.segmentedShapes(index, count)
    if (LocalFolkWallpaperTokens.current != null) {
        // Over a wallpaper standalone rows tile into one continuous group: only the ends stay rounded.
        return base.copy(
            shape = RoundedCornerShape(
                topStart = if (index == 0) SegmentedOuterRadius else 0.dp,
                topEnd = if (index == 0) SegmentedOuterRadius else 0.dp,
                bottomStart = if (index == count - 1) SegmentedOuterRadius else 0.dp,
                bottomEnd = if (index == count - 1) SegmentedOuterRadius else 0.dp,
            )
        )
    }
    return if (count == 1) {
        base.copy(shape = MaterialTheme.shapes.large)
    } else {
        base
    }
}

@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    title: String = "",
    visibleLen: Int = 0,
    content: List<@Composable () -> Unit>,
) {
    if (content.isEmpty()) return

    Column(modifier = modifier) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }
        WallpaperGroupBackground {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                content.forEachIndexed { index, itemContent ->
                    CompositionLocalProvider(
                        LocalListItemShapes provides defaultSingleSegmentedShape(
                            index = index,
                            count = if (visibleLen > 0) visibleLen else content.size
                        ),
                    ) {
                        itemContent()
                    }
                }
            }
        }
    }
}

@SegmentedColumnDsl
class SegmentedColumnScope {
    internal data class Entry(
        val key: Any?,
        val visible: Boolean,
        val content: @Composable () -> Unit,
    )

    internal val entries = mutableListOf<Entry>()

    fun item(
        key: Any? = null,
        visible: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        entries.add(Entry(key ?: entries.size, visible, content))
    }
}

@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    title: String = "",
    content: SegmentedColumnScope.() -> Unit,
) {
    val entries = SegmentedColumnScope().apply(content).entries
    if (entries.isEmpty()) return

    Column(modifier = modifier) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }

        val floatSpring = spring<Float>(SegmentedSpringDamping, SegmentedSpringStiffness)
        val dpSpring = spring<Dp>(SegmentedSpringDamping, SegmentedSpringStiffness)

        val progresses = entries.mapIndexed { index, entry ->
            key(entry.key ?: index) {
                animateFloatAsState(
                    targetValue = if (entry.visible) 1f else 0f,
                    animationSpec = floatSpring,
                    label = "SegmentedProgress"
                )
            }
        }

        val firstVisible = entries.indexOfFirst { it.visible }
        val lastVisible = entries.indexOfLast { it.visible }

        WallpaperGroupBackground {
            Layout(
                content = {
                    entries.forEachIndexed { index, entry ->
                        key(entry.key ?: index) {
                            val isFirst = if (firstVisible == -1) index == 0 else index == firstVisible
                            val isLast = if (lastVisible == -1) index == entries.lastIndex else index == lastVisible

                            val topRadius by animateDpAsState(
                                if (isFirst) SegmentedOuterRadius else SegmentedInnerRadius,
                                dpSpring, label = "SegmentedTopRadius"
                            )
                            val bottomRadius by animateDpAsState(
                                if (isLast) SegmentedOuterRadius else SegmentedInnerRadius,
                                dpSpring, label = "SegmentedBottomRadius"
                            )
                            val gap by animateDpAsState(
                                if (isFirst) 0.dp else ListItemDefaults.SegmentedGap,
                                dpSpring, label = "SegmentedGap"
                            )

                            val shape = RoundedCornerShape(
                                topStart = topRadius, topEnd = topRadius,
                                bottomStart = bottomRadius, bottomEnd = bottomRadius
                            )

                            Box(
                                modifier = Modifier
                                    .zIndex(if (entry.visible) (entries.size - index).toFloat() else -index.toFloat())
                                    .graphicsLayer {
                                        val progress = progresses[index].value.coerceAtLeast(0f)
                                        clip = true
                                        this.shape = object : Shape {
                                            override fun createOutline(
                                                size: Size,
                                                layoutDirection: LayoutDirection,
                                                density: Density,
                                            ): Outline = Outline.Rectangle(Rect(0f, 0f, size.width, size.height * progress))
                                        }
                                        alpha = (progress * 1.5f).coerceIn(0f, 1f)
                                    }
                            ) {
                                CompositionLocalProvider(
                                    LocalListItemShapes provides ListItemDefaults.segmentedShapes(0, 1).copy(shape = shape)
                                ) {
                                    Column(modifier = Modifier.padding(top = gap)) {
                                        entry.content()
                                    }
                                }
                            }
                        }
                    }
                }
            ) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints) }
                val positions = IntArray(placeables.size)
                var y = 0f
                placeables.forEachIndexed { index, placeable ->
                    positions[index] = y.roundToInt()
                    y += placeable.height * progresses[index].value.coerceAtLeast(0f)
                }
                layout(constraints.maxWidth, y.roundToInt().coerceAtLeast(0)) {
                    placeables.forEachIndexed { index, placeable ->
                        placeable.placeRelative(0, positions[index])
                    }
                }
            }
        }
    }
}

@Composable
fun SegmentedItem(
    index: Int,
    count: Int,
    content: @Composable () -> Unit,
) {
    val wallpaper = LocalFolkWallpaperTokens.current != null
    CompositionLocalProvider(
        LocalListItemShapes provides defaultSingleSegmentedShape(index, count),
    ) {
        if (!wallpaper) {
            content()
        } else {
            // The parent list spaces standalone rows by SegmentedGap; report a height reduced by
            // that gap and let the row overflow into it, so the squared rows join into one group.
            val gapPx = with(LocalDensity.current) { ListItemDefaults.SegmentedGap.roundToPx() }
            Box(
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, (placeable.height - gapPx).coerceAtLeast(0)) {
                        placeable.placeRelative(0, 0)
                    }
                }
            ) {
                content()
            }
        }
    }
}

@Composable
fun SegmentedItemContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shapes = LocalListItemShapes.current ?: ListItemDefaults.segmentedShapes(0, 1)
    if (LocalFolkWallpaperTokens.current != null) {
        FolkWallpaperSurface(
            role = WallpaperSurfaceRole.Group,
            shape = shapes.shape,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colorScheme.surfaceBright,
        shape = shapes.shape,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/**
 * M3's [SegmentedListItem] hard-codes its ripple and has no `indication` parameter, so a press reads
 * through [folkPressScale] instead. The same [MutableInteractionSource] drives the scale and the
 * row's own clickable, and a transparent ripple configuration stands in for `indication = null`.
 * (Adapted from FinaSU, GPL-3.0.)
 */
@Composable
private fun SegmentedRow(
    modifier: Modifier,
    clickable: Boolean,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    content: @Composable (Modifier, MutableInteractionSource) -> Unit,
) {
    val pressedSource = interactionSource ?: remember { MutableInteractionSource() }
    val rowModifier = if (clickable) modifier.folkPressScale(pressedSource, enabled) else modifier
    val rippleConfiguration = if (clickable) {
        RippleConfiguration(color = Color.Transparent)
    } else {
        LocalRippleConfiguration.current
    }
    CompositionLocalProvider(LocalRippleConfiguration provides rippleConfiguration) {
        content(rowModifier, pressedSource)
    }
}

@Composable
fun SegmentedListItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: ListItemColors = defaultSegmentedColors(),
    interactionSource: MutableInteractionSource? = null,
    headlineContent: @Composable () -> Unit,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    SegmentedRow(
        modifier = modifier,
        clickable = onClick != null || onLongClick != null,
        enabled = enabled,
        interactionSource = interactionSource,
    ) { rowModifier, source ->
        SegmentedListItem(
            onClick = onClick ?: {},
            onLongClick = onLongClick,
            enabled = enabled,
            colors = colors,
            interactionSource = source,
            shapes = LocalListItemShapes.current ?: ListItemDefaults.segmentedShapes(0, 1),
            modifier = rowModifier,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            overlineContent = overlineContent,
            supportingContent = supportingContent,
            verticalAlignment = Alignment.CenterVertically,
            content = headlineContent
        )
    }
}

@Composable
fun SegmentedListItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ListItemColors = defaultSegmentedColors(),
    interactionSource: MutableInteractionSource? = null,
    headlineContent: @Composable () -> Unit,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    SegmentedRow(
        modifier = modifier,
        clickable = true,
        enabled = enabled,
        interactionSource = interactionSource,
    ) { rowModifier, source ->
        SegmentedListItem(
            checked = checked,
            onCheckedChange = onCheckedChange,
            shapes = LocalListItemShapes.current ?: ListItemDefaults.segmentedShapes(0, 1),
            modifier = rowModifier,
            enabled = enabled,
            colors = colors,
            interactionSource = source,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            overlineContent = overlineContent,
            supportingContent = supportingContent,
            verticalAlignment = Alignment.CenterVertically,
            onLongClick = onLongClick,
            content = headlineContent
        )
    }
}

@Composable
fun SegmentedListItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ListItemColors = defaultSegmentedColors(),
    interactionSource: MutableInteractionSource? = null,
    headlineContent: @Composable () -> Unit,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    SegmentedRow(
        modifier = modifier,
        clickable = true,
        enabled = enabled,
        interactionSource = interactionSource,
    ) { rowModifier, source ->
        SegmentedListItem(
            selected = selected,
            onClick = onClick,
            shapes = LocalListItemShapes.current ?: ListItemDefaults.segmentedShapes(0, 1),
            modifier = rowModifier,
            enabled = enabled,
            colors = colors,
            interactionSource = source,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            overlineContent = overlineContent,
            supportingContent = supportingContent,
            verticalAlignment = Alignment.CenterVertically,
            onLongClick = onLongClick,
            content = headlineContent
        )
    }
}

@Composable
fun SegmentedSwitchItem(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    colors: ListItemColors = defaultSegmentedColors(),
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    SegmentedListItem(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onCheckedChange(!checked)
        },
        enabled = enabled,
        interactionSource = interactionSource,
        colors = colors,
        headlineContent = { Text(title) },
        leadingContent = icon?.let { { Icon(it, title) } },
        trailingContent = {
            ExpressiveSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
                interactionSource = interactionSource,
            )
        },
        supportingContent = summary?.let { { Text(it) } }
    )
}

/** A list row with a slider underneath, committing the value when the drag ends. (From FinaSU.) */
@Composable
fun SegmentedSliderItem(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: (Float) -> String,
    colors: ListItemColors = defaultSegmentedColors(),
    enabled: Boolean = true,
    onValueChangeFinished: (Float) -> Unit,
) {
    val sliderState = rememberSliderState(value = value, trackRange = valueRange)

    SegmentedListItem(
        enabled = enabled,
        colors = colors,
        headlineContent = { Text(title) },
        leadingContent = icon?.let { { Icon(it, title) } },
        trailingContent = { Text(valueText(sliderState.value)) },
        supportingContent = {
            Column {
                summary?.let { Text(it) }
                Slider(
                    state = sliderState,
                    onValueChangeFinished = { onValueChangeFinished(sliderState.value) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
fun SegmentedDropdownItem(
    icon: ImageVector? = null,
    title: String,
    summary: String? = null,
    items: List<String>,
    colors: ListItemColors = defaultSegmentedColors(),
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    var anchorOffset by remember { mutableStateOf(IntOffset.Zero) }

    val hasItems = items.isNotEmpty()
    val safeIndex = if (hasItems) {
        selectedIndex.coerceIn(0, items.lastIndex)
    } else {
        -1
    }

    Box(modifier = Modifier.trackPressPosition { anchorOffset = it.round() }) {
        SegmentedListItem(
            onClick = if (enabled) {
                {
                    onClick?.invoke()
                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    expanded = true
                }
            } else null,
            enabled = enabled,
            colors = colors,
            leadingContent = icon?.let { { Icon(it, title) } },
            headlineContent = { Text(text = title) },
            supportingContent = summary?.let { { Text(it) } },
            trailingContent = {
                Text(
                    text = if (hasItems && safeIndex >= 0) items[safeIndex] else "",
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(0.3f),
                    color = if (enabled) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }
        )
        OffsetAnchoredExpressiveMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            anchorOffset = anchorOffset,
        ) {
            items.forEachIndexed { index, text ->
                SelectableDropdownMenuItem(
                    text = { Text(text) },
                    selected = index == safeIndex,
                    onClick = {
                        if (index in items.indices) {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onItemSelected(index)
                        }
                        expanded = false
                    },
                    shapes = MenuDefaults.itemShape(index = index, count = items.size),
                    selectedLeadingIcon = {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun SegmentedRadioItem(
    title: String,
    summary: String? = null,
    colors: ListItemColors = defaultSegmentedColors(),
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    SegmentedListItem(
        selected = selected,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onClick()
        },
        enabled = enabled,
        colors = colors,
        headlineContent = { Text(title) },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled
            )
        },
        supportingContent = summary?.let { { Text(it) } }
    )
}

@Composable
fun SegmentedCheckboxItem(
    title: String,
    summary: String? = null,
    colors: ListItemColors = defaultSegmentedColors(),
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    SegmentedListItem(
        checked = checked,
        onCheckedChange = {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            onCheckedChange(it)
        },
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource,
        headlineContent = { Text(title) },
        leadingContent = {
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
                interactionSource = interactionSource,
                modifier = Modifier.size(24.dp)
            )
        },
        supportingContent = summary?.let { { Text(it) } }
    )
}

@Composable
fun SegmentedTextField(
    modifier: Modifier = Modifier,
    label: String = "",
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    colors: ListItemColors = defaultSegmentedColors(),
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    cursorBrush: Brush = SolidColor(colorScheme.primary),
    placeholder: @Composable (() -> Unit)? = { Text("-") },
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    isError: Boolean = false
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    SegmentedListItem(
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .focusRequester(focusRequester),
        colors = colors,
        onClick = { focusRequester.requestFocus() },
        leadingContent = leadingContent,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
        headlineContent = {
            Column {
                if (label.isNotEmpty()) {
                    Text(text = label, color = if (isError) colorScheme.error else colors.contentColor)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused) {
                                coroutineScope.launch {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        },
                    enabled = enabled,
                    readOnly = readOnly,
                    textStyle = textStyle.copy(
                        colors.supportingContentColor,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                    ),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    minLines = minLines,
                    visualTransformation = visualTransformation,
                    onTextLayout = onTextLayout,
                    interactionSource = interactionSource,
                    cursorBrush = cursorBrush,
                    decorationBox = { innerTextField ->
                        if (value.isEmpty() && placeholder != null) {
                            Box(contentAlignment = Alignment.CenterStart) {
                                CompositionLocalProvider(
                                    LocalContentColor provides colors.supportingContentColor
                                ) {
                                    ProvideTextStyle(value = MaterialTheme.typography.bodyMedium) {
                                        placeholder()
                                    }
                                }
                            }
                        }
                        innerTextField()
                    }
                )
            }
        }
    )
}
