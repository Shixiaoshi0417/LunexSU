package com.shixiaoshi0417.lunexsu.ui.screen.susfs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import top.yukonga.miuix.kmp.basic.Card
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
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = colors.primary, error = colors.error, onSurface = colors.onBackground,
        onSurfaceVariant = colors.onSurfaceVariantSummary, surface = colors.background,
        surfaceContainerLow = colors.surface, secondaryContainer = colors.secondary,
        onSecondaryContainer = colors.onBackground,
    )) {
        // MIUIX and Material use separate content-color locals. Material Text/Icon
        // must inherit the native foreground, not Material's default black.
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SusfsScaffold(busy: Boolean, back: () -> Unit, refresh: () -> Unit,
    title: String = stringResource(R.string.susfs_title),
    content: @Composable (PaddingValues) -> Unit) {
    if (miuix) {
        // Shared editor text and tab indicators follow the native MIUIX palette too.
        SusfsNativeColors {
            MiuixScaffold(topBar = {
                MiuixTopAppBar(title = title, navigationIcon = {
                    MiuixIconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.susfs_back)) }
                }, actions = { TextButton(enabled = !busy, onClick = refresh) { Text(stringResource(R.string.susfs_refresh)) } })
            }, popupHost = {}, content = content)
        }
    } else {
        ExpressiveScaffold(topBar = {
            LargeFlexibleTopAppBar(title = { Text(title) }, navigationIcon = {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.susfs_back)) }
            }, actions = { TextButton(enabled = !busy, onClick = refresh) { Text(stringResource(R.string.susfs_refresh)) } })
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
            .alpha(if (enabled) 1f else 0.38f).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leadingContent?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge, headlineContent)
                supportingContent?.let { ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), it) }
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
    if (miuix) SusfsNativeColors { WindowDialog(show = true, title = "", onDismissRequest = onDismissRequest) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
            // Bound the editor's own scroll container above the action row.
            Column(Modifier.weight(1f, fill = false)) { text() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismissButton?.invoke()
                Spacer(Modifier.width(12.dp))
                confirmButton()
            }
        }
    } } else ExpressiveDialog(onDismissRequest = onDismissRequest, title = title, text = text,
        confirmButton = confirmButton, dismissButton = dismissButton)
}

@Composable
internal fun OutlinedTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    singleLine: Boolean = false, minLines: Int = 1, label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null, isError: Boolean = false) {
    if (miuix) Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.invoke()
        MiuixTextField(value = value, onValueChange = onValueChange, singleLine = singleLine, modifier = Modifier.fillMaxWidth())
        if (isError) Text(stringResource(R.string.susfs_path_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else androidx.compose.material3.OutlinedTextField(value = value, onValueChange = onValueChange,
        modifier = modifier, singleLine = singleLine, minLines = minLines, label = label, placeholder = placeholder, isError = isError)
}

@Composable
internal fun TextButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (miuix) Row(Modifier.padding(2.dp).background(MiuixTheme.colorScheme.secondary, RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else 0.38f).heightIn(min = 48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
    else FolkTextButton(onClick = onClick, enabled = enabled, content = content)
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
