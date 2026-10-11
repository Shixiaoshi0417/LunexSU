package com.shixiaoshi0417.lunexsu.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.shixiaoshi0417.lunexsu.ui.component.choosekmidialog.ChooseKmiDialog
import com.shixiaoshi0417.lunexsu.ui.component.choosekmidialog.KmiDialogState
import com.shixiaoshi0417.lunexsu.ui.theme.LunexSUTheme
import top.yukonga.miuix.kmp.basic.Scaffold

/** Debug-only fixture. Exercises both real KMI dialogs without flashing or Root. */
class KmiTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = if (intent.getStringExtra("ui") == "miuix") UiMode.Miuix else UiMode.Material
        val state = when (intent.getStringExtra("case")) {
            "loading" -> KmiDialogState("android12-5.10")
            "failed" -> KmiDialogState("android12-5.10", loading = false, failed = true)
            "ready" -> KmiDialogState("android12-5.10", listOf("android12-5.10"), loading = false)
            "mismatch" -> KmiDialogState("android12-5.10", listOf("android13-5.10"), loading = false)
            else -> KmiDialogState("android12-5.10", loading = false)
        }
        setContent {
            CompositionLocalProvider(LocalUiMode provides ui) {
                LunexSUTheme(uiMode = ui) {
                    Scaffold {
                        Box(Modifier.fillMaxSize()) {
                            ChooseKmiDialog(true, ::finish, { selected ->
                                getSharedPreferences("kmi_ui_fixture", MODE_PRIVATE).edit()
                                    .putString("selected", selected).commit()
                            }, state)
                        }
                    }
                }
            }
        }
    }
}
