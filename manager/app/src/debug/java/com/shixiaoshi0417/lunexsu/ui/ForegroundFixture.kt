package com.shixiaoshi0417.lunexsu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.ui.screen.home.SuperkeyError
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.AlertDialog
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.SegmentedColumn
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.SegmentedListItem
import com.shixiaoshi0417.lunexsu.ui.screen.susfs.SusfsNativeColors
import com.shixiaoshi0417.lunexsu.ui.theme.pack.LocalFinaThemeUi
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** No ViewModels, authentication, settings writes or native calls. */
internal class ForegroundFixture {
    @Volatile var result = ""
        private set
    @Volatile var pixel = 0
        private set
    private val completed = mutableSetOf<String>()

    private fun verify(name: String, actual: Color, expected: Color, required: Int) {
        if (result.startsWith("FAIL")) return
        if (actual != expected) {
            result = "FAIL: $name expected $expected, got $actual"
        } else {
            completed += name
            if (completed.size >= required) result = "PASS"
        }
    }

    @Composable
    fun Content(scenario: String) {
        val native = "native" in scenario
        val dark = "dark" in scenario || "amoled" in scenario
        val material = if (dark) darkColorScheme() else lightColorScheme()
        CompositionLocalProvider(LocalUiMode provides if (native) UiMode.Miuix else UiMode.Material,
            LocalFinaThemeUi provides ("fina" in scenario)) {
            MaterialTheme(colorScheme = material) {
                MiuixTheme(controller = ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light, isDark = dark)) {
                    val colors = MiuixTheme.colorScheme
                    MiuixTheme(colors = colors.copy(
                        background = if ("amoled" in scenario) Color.Black else colors.background,
                        surface = if ("amoled" in scenario) Color.Black else colors.surface,
                    )) {
                        val background = if (native) MiuixTheme.colorScheme.background else material.background
                        Column(Modifier.fillMaxSize().background(background).padding(24.dp)) {
                            if ("superkey" in scenario) {
                                pixel = (if (native) colors.error else material.error).toArgb()
                                SuperkeyError()
                                SideEffect { result = "PASS" }
                            } else {
                                // Deliberately wrong inherited color: the native bridge must replace it
                                // and must not leak its override back into the enclosing composition.
                                CompositionLocalProvider(LocalContentColor provides Color.Magenta) {
                                    val required = if ("dialog" in scenario) 3 else 4
                                    val body: @Composable () -> Unit = {
                                        Text("Native foreground", onTextLayout = {
                                            verify("body", it.layoutInput.style.color, colors.onBackground, required)
                                        })
                                        val actualError = MaterialTheme.colorScheme.error
                                        SideEffect { verify("error", actualError, colors.error, required) }
                                    }
                                    if ("dialog" in scenario) {
                                        AlertDialog(onDismissRequest = {}, title = { Text("Import preview") },
                                            text = body, confirmButton = {})
                                    } else {
                                        SusfsNativeColors {
                                            SegmentedColumn(title = "Section") {
                                                item { SegmentedListItem(enabled = false,
                                                    headlineContent = body,
                                                    supportingContent = { Text("Supporting", onTextLayout = {
                                                        verify("supporting", it.layoutInput.style.color, colors.onSurfaceVariantSummary, required)
                                                    }) },
                                                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }) }
                                            }
                                        }
                                    }
                                    val outside = LocalContentColor.current
                                    SideEffect { verify("outside", outside, Color.Magenta, required) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
