package com.shixiaoshi0417.lunexsu.ui.screen.susfs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.ui.LocalUiMode
import com.shixiaoshi0417.lunexsu.ui.UiMode
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveDialog
import com.shixiaoshi0417.lunexsu.ui.component.material.ExpressiveScaffold
import com.shixiaoshi0417.lunexsu.ui.component.material.FolkTextButton
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedColumnScope
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedColumn as MaterialGroup
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedListItem as MaterialItem
import com.shixiaoshi0417.lunexsu.ui.component.material.SegmentedSwitchItem as MaterialSwitch
import com.shixiaoshi0417.lunexsu.ui.component.material.expressiveTopAppBarColors
import com.shixiaoshi0417.lunexsu.ui.theme.LocalEnableBlur
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFinaThemeUi
import com.shixiaoshi0417.lunexsu.ui.util.BlurredBar
import com.shixiaoshi0417.lunexsu.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.TopAppBar as MiuixTopAppBar
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val miuix: Boolean
    @Composable get() = LocalUiMode.current == UiMode.Miuix

@Composable
internal fun SusfsNativeColors(content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val native = MiuixTheme.textStyles
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = colors.primary, error = colors.error, onSurface = colors.onBackground,
        onSurfaceVariant = colors.onSurfaceVariantSummary, surface = colors.background,
        surfaceContainerLow = colors.surface, secondaryContainer = colors.secondary,
        onSecondaryContainer = colors.onBackground,
    ), typography = MaterialTheme.typography.copy(
        bodyLarge = native.main,
        bodyMedium = native.body2,
        bodySmall = native.footnote1,
        titleSmall = native.subtitle,
        titleLarge = native.title4.copy(fontWeight = FontWeight.Medium),
        labelLarge = native.button,
    )) {
        // Shared Material slots inherit native font metrics as well as native colors.
        // Replace the style rather than merging unspecified native line height/tracking.
        CompositionLocalProvider(LocalContentColor provides colors.onBackground,
            LocalTextStyle provides native.main, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SusfsScaffold(busy: Boolean, back: () -> Unit, refresh: () -> Unit,
    title: String = stringResource(R.string.susfs_title),
    content: @Composable (PaddingValues) -> Unit) {
    if (miuix) {
        val scrollBehavior = MiuixScrollBehavior()
        val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
        SusfsNativeColors {
            MiuixScaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), topBar = {
                BlurredBar(backdrop) {
                    MiuixTopAppBar(title = title, navigationIcon = {
                        MiuixIconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.susfs_back)) }
                    }, actions = { TextButton(enabled = !busy, onClick = refresh) { Text(stringResource(R.string.susfs_refresh)) } },
                        scrollBehavior = scrollBehavior,
                        color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface)
                }
            }, popupHost = {}) { padding ->
                Box(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) { content(padding) }
            }
        }
    } else {
        val fina = LocalFinaThemeUi.current
        val scrollBehavior = if (fina) TopAppBarDefaults.pinnedScrollBehavior()
            else TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        val navigation: @Composable () -> Unit = {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.susfs_back)) }
        }
        val actions: @Composable RowScope.() -> Unit = {
            TextButton(enabled = !busy, onClick = refresh) { Text(stringResource(R.string.susfs_refresh)) }
        }
        ExpressiveScaffold(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection), topBar = {
            if (fina) TopAppBar(title = { Text(title) }, navigationIcon = navigation, actions = actions,
                scrollBehavior = scrollBehavior, colors = expressiveTopAppBarColors())
            else LargeFlexibleTopAppBar(title = { Text(title) }, navigationIcon = navigation, actions = actions,
                scrollBehavior = scrollBehavior, colors = expressiveTopAppBarColors())
        }, content = content)
    }
}

@Composable
internal fun SegmentedColumn(modifier: Modifier = Modifier, title: String = "", content: SegmentedColumnScope.() -> Unit) {
    if (!miuix) {
        MaterialGroup(modifier = modifier, title = title, content = content)
    } else {
        val scope = SegmentedColumnScope().apply(content)
        Column(modifier) {
            if (title.isNotEmpty()) Text(title, Modifier.padding(start = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth()) {
                Column {
                    scope.entries.filter { it.visible }.forEachIndexed { index, entry ->
                        key(entry.key ?: index) { entry.content() }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SegmentedListItem(headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null, leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null, enabled: Boolean = true,
    selected: Boolean = false, onClick: (() -> Unit)? = null) {
    if (!miuix) {
        if (onClick == null) MaterialItem(headlineContent = headlineContent, supportingContent = supportingContent,
            leadingContent = leadingContent, trailingContent = trailingContent)
        else MaterialItem(headlineContent = headlineContent, supportingContent = supportingContent,
            leadingContent = leadingContent, trailingContent = trailingContent, enabled = enabled,
            selected = selected, onClick = onClick)
    } else {
        Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.38f).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leadingContent?.invoke()
            Column(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalTextStyle provides MiuixTheme.textStyles.main.copy(fontWeight = FontWeight.Medium), content = headlineContent)
                supportingContent?.let {
                    CompositionLocalProvider(LocalTextStyle provides MiuixTheme.textStyles.body2,
                        LocalContentColor provides MiuixTheme.colorScheme.onSurfaceVariantSummary, content = it)
                }
            }
            trailingContent?.invoke()
        }
    }
}

@Composable
internal fun SegmentedSwitchItem(title: String, summary: String? = null, checked: Boolean,
    enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    if (miuix) SwitchPreference(title = title, summary = summary, checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    else MaterialSwitch(title = title, summary = summary, checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
}

@Composable
internal fun AlertDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null) {
    if (miuix) SusfsNativeColors { WindowDialog(show = true, title = null, onDismissRequest = onDismissRequest) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompositionLocalProvider(LocalTextStyle provides MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.Medium), content = title)
            // Bound the editor's own scroll container above the action row.
            CompositionLocalProvider(LocalTextStyle provides MiuixTheme.textStyles.body2) {
                Column(Modifier.weight(1f, fill = false)) { text() }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    } } else ExpressiveDialog(onDismissRequest = onDismissRequest, title = title, text = text,
        confirmButton = confirmButton, dismissButton = dismissButton)
}

@Composable
internal fun OutlinedTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    singleLine: Boolean = false, minLines: Int = 1, label: String = "",
    placeholder: (@Composable () -> Unit)? = null, isError: Boolean = false) {
    if (miuix) MiuixTextField(value = value, onValueChange = onValueChange, label = label,
        singleLine = singleLine, minLines = minLines, modifier = modifier,
        colors = top.yukonga.miuix.kmp.basic.TextFieldDefaults.textFieldColors(
            borderColor = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
            labelColor = if (isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSecondaryContainer))
    else androidx.compose.material3.OutlinedTextField(value = value, onValueChange = onValueChange,
        modifier = modifier, singleLine = singleLine, minLines = minLines, label = { Text(label) }, placeholder = placeholder, isError = isError)
}

@Composable
internal fun TextButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (miuix) CompositionLocalProvider(LocalTextStyle provides MiuixTheme.textStyles.button) {
        Row(Modifier.background(MiuixTheme.colorScheme.secondary, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f).heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
    } else FolkTextButton(onClick = onClick, enabled = enabled, content = content)
}

@Composable
internal fun SusfsRemoveButton(enabled: Boolean, onClick: () -> Unit) {
    val icon: @Composable () -> Unit = {
        Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.susfs_remove))
    }
    if (miuix) MiuixIconButton(modifier = Modifier.size(48.dp), enabled = enabled, onClick = onClick, content = icon)
    else IconButton(modifier = Modifier.size(48.dp), enabled = enabled, onClick = onClick, content = icon)
}

@Composable
internal fun RadioButton(selected: Boolean, onClick: (() -> Unit)?, enabled: Boolean = true) {
    if (miuix) {
        val color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground
        Canvas(Modifier.size(24.dp).semantics { this.selected = selected }.alpha(if (enabled) 1f else 0.38f)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick) else Modifier)) {
            drawCircle(color, radius = 9.dp.toPx(), style = Stroke(2.dp.toPx()))
            if (selected) drawCircle(color, radius = 5.dp.toPx())
        }
    } else androidx.compose.material3.RadioButton(selected = selected, onClick = onClick, enabled = enabled)
}
