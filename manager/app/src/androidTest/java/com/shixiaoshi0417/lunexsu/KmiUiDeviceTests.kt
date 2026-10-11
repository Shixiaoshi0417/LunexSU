package com.shixiaoshi0417.lunexsu

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

object KmiUiDeviceTests {
    fun run(instrumentation: Instrumentation): Bundle {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("kmi_ui_fixture", 0)
        var phase = "starting"
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            if (node == null || !node.refresh()) return emptyList()
            return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        }
        fun awaitText(text: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 7000
            while (SystemClock.uptimeMillis() < deadline) {
                nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull {
                    it.isVisibleToUser && it.text?.toString() == text
                }?.let { return it }
                SystemClock.sleep(100)
            }
            error("Missing KMI dialog text: $text ($phase); visible=${nodes(instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { it.text }}")
        }
        fun button(text: String): AccessibilityNodeInfo {
            var target = awaitText(text)
            while (!target.isClickable && target.parent != null) target = target.parent
            return target
        }
        var passed = 0
        for (ui in listOf("material", "miuix")) {
            for (scenario in listOf("empty", "loading", "failed", "ready", "mismatch")) {
                phase = "$ui/$scenario"
                prefs.edit().clear().commit()
                val activity = instrumentation.startActivitySync(Intent().setClassName(context,
                    "com.shixiaoshi0417.lunexsu.ui.KmiTestActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("ui", ui).putExtra("case", scenario))
                try {
                    val confirm = context.getString(R.string.confirm)
                    val initial = button(confirm)
                    if (scenario == "ready") {
                        check(initial.isEnabled && initial.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    } else {
                        check(!initial.isEnabled) { "$ui $scenario enabled without a matching bundled module" }
                        val message = when (scenario) {
                            "empty" -> R.string.install_no_bundled_lkm
                            "loading" -> R.string.install_loading_kmis
                            "failed" -> R.string.install_load_kmis_failed
                            else -> R.string.install_no_matching_lkm
                        }
                        awaitText(context.getString(message))
                        if (scenario == "mismatch") {
                            check(button("android13-5.10").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            SystemClock.sleep(250)
                            val updated = button(confirm)
                            check(updated.isEnabled && updated.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        } else {
                            check(button(context.getString(android.R.string.cancel)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            check(!prefs.contains("selected"))
                        }
                    }
                    if (scenario == "ready" || scenario == "mismatch") {
                        val expected = if (scenario == "ready") "android12-5.10" else "android13-5.10"
                        val deadline = SystemClock.uptimeMillis() + 3000
                        while (!prefs.contains("selected") && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                        check(prefs.getString("selected", null) == expected)
                    }
                    passed++
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
        }
        return Bundle().apply { putString("kmi_ui", "PASS: $passed Material/MIUIX loading, empty, error, available and manual selection cases; no flashing") }
    }
}
