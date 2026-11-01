package com.shixiaoshi0417.lunexsu

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.view.accessibility.AccessibilityNodeInfo

/** Exercises the real Material/MIUIX pages with debug-only injected storage. */
object AuthorizationUiDeviceTests {
    fun run(instrumentation: Instrumentation): Bundle {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("authorization_ui_fixture", 0)
        Os.stat(context.applicationInfo.dataDir)
        val peer = context.packageManager.getApplicationInfo(instrumentation.context.packageName, 0)
        val peerLookup = try {
            Os.stat(peer.dataDir)
            "visible"
        } catch (failure: ErrnoException) { "errno=${failure.errno}" }
        check(Natives.setAppProfileResult(null) == -22) { "JNI invalid input must be a negative error, not success" }
        if (!Natives.isManager) {
            val unreadable = Natives.getAppProfile("lunexsu.test.unavailable", 10387)
            check(unreadable.authorizationReadError != 0 && unreadable.authorizationMode == -1)
            check(!Natives.setAppProfile(unreadable.copy(allowSu = true, authorizationMode = 1)))
        }
        var passed = 0
        fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            if (node == null) return emptyList()
            // Compose can reuse node IDs when controls change after a save.
            // Refresh each node instead of traversing the accessibility cache.
            if (!node.refresh()) return emptyList()
            return listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
        }
        fun click(vararg labels: String) {
            val deadline = SystemClock.uptimeMillis() + 8000
            while (SystemClock.uptimeMillis() < deadline) {
                val all = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                for (node in all.filter { it.isVisibleToUser && it.text?.toString() in labels }) {
                    var target: AccessibilityNodeInfo? = node
                    while (target != null) {
                        if (target.isEnabled && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            SystemClock.sleep(250)
                            return
                        }
                        target = target.parent
                    }
                }
                all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(100)
            }
            error("Unable to click ${labels.toList()}; visible=${nodes(instrumentation.uiAutomation.rootInActiveWindow).mapNotNull { it.text }}")
        }
        fun awaitMode(mode: Int, allowed: Boolean, error: Int = 0) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                if (prefs.contains("mode") && prefs.getInt("mode", -1) == mode &&
                    prefs.getBoolean("allowed", !allowed) == allowed && prefs.getInt("error", -1) == error) return
                SystemClock.sleep(100)
            }
            error("Unexpected save/readback: ${prefs.all}")
        }
        fun awaitGeneration(previous: Long) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline) {
                if (prefs.getLong("generation", 0) > previous) return
                SystemClock.sleep(100)
            }
            error("Save did not reload a new generation: ${prefs.all}")
        }
        fun setMinutes(value: String) {
            val field = nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { it.isEditable }
                ?: error("Custom duration field missing")
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
            SystemClock.sleep(300)
        }
        fun finishMinutes() {
            val field = nodes(instrumentation.uiAutomation.rootInActiveWindow).first { it.isEditable }
            check(field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
            SystemClock.sleep(300)
        }
        for (ui in listOf("material", "miuix")) {
            for (reject in listOf(false, true)) {
                prefs.edit().clear().commit()
                val activity = instrumentation.startActivitySync(Intent().setClassName(context,
                    "com.shixiaoshi0417.lunexsu.ui.AuthorizationTestActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("ui", ui).putExtra("reject", reject))
                try {
                    SystemClock.sleep(700)
                    fun select(vararg names: String) {
                        if (ui == "miuix") click("Root authorization duration", "Root 授权有效期")
                        click(*names)
                    }
                    select("Once per app run", "本次运行")
                    awaitMode(if (reject) 0 else 1, true, if (reject) -5 else 0)
                    passed++
                    if (reject) continue
                    select("Timed", "限时")
                    awaitMode(2, true)
                    var generation = prefs.getLong("generation", 0)
                    click("15 min", "15 分钟")
                    awaitGeneration(generation)
                    check(prefs.getLong("seconds", 0) == 900L)
                    generation = prefs.getLong("generation", 0)
                    setMinutes("42")
                    finishMinutes()
                    awaitGeneration(generation)
                    check(prefs.getLong("seconds", 0) == 2520L)
                    passed++
                    generation = prefs.getLong("generation", 0)
                    setMinutes("0")
                    val all = nodes(instrumentation.uiAutomation.rootInActiveWindow)
                    check(all.first { it.isEditable }.text?.toString() == "0") { "Invalid duration was not entered" }
                    finishMinutes()
                    SystemClock.sleep(500)
                    check(prefs.getLong("generation", 0) == generation)
                    check(prefs.getLong("seconds", 0) == 2520L)
                    passed++
                    setMinutes("42")
                    finishMinutes()
                    // Selecting the existing policy must not extend a timed deadline.
                    check(prefs.getLong("generation", 0) == generation)
                    click("Superuser", "超级用户")
                    awaitMode(2, false)
                    val writes = prefs.getInt("writes", 0)
                    select("Permanent", "永久")
                    SystemClock.sleep(300)
                    check(prefs.getInt("writes", 0) == writes && !prefs.getBoolean("allowed", true))
                    click("Superuser", "超级用户")
                    awaitMode(0, true)
                    passed += 3
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                    SystemClock.sleep(200)
                }
            }
            // A new page instance reloads stored Once; a UI default must not
            // replace the stored mode. This remains debug injected storage.
            prefs.edit().putInt("mode", 1).putBoolean("allowed", true).putLong("seconds", 0).commit()
            val reopened = instrumentation.startActivitySync(Intent().setClassName(context,
                "com.shixiaoshi0417.lunexsu.ui.AuthorizationTestActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("ui", ui).putExtra("restore", true))
            try {
                SystemClock.sleep(700)
                check(prefs.getInt("mode", -1) == 1 && prefs.getInt("writes", -1) == 0)
                passed++
            } finally { instrumentation.runOnMainSync { reopened.finish() } }
            prefs.edit().clear().commit()
            val denied = instrumentation.startActivitySync(Intent().setClassName(context,
                "com.shixiaoshi0417.lunexsu.ui.AuthorizationTestActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("ui", ui).putExtra("denied", true))
            try {
                SystemClock.sleep(700)
                if (ui == "miuix") click("Root authorization duration", "Root 授权有效期")
                click("Timed", "限时")
                click("15 min", "15 分钟")
                check(prefs.getInt("writes", -1) == 0 && !prefs.getBoolean("allowed", true))
                click("Superuser", "超级用户")
                awaitMode(2, true)
                check(prefs.getLong("seconds", 0) == 900L)
                passed++
            } finally { instrumentation.runOnMainSync { denied.finish() } }
        }
        return Bundle().apply {
            putString("authorization_ui", "PASS: $passed UI save/readback cases, Material and MIUIX; injected storage, no Root grants")
            putString("data_view", "Manager own directory readable; peer uid=${peer.uid} path=${peer.dataDir} lookup=$peerLookup")
        }
    }
}
