package com.shixiaoshi0417.lunexsu

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optional real composition/text-pixel regressions; compilation alone is not a pass. */
internal object ForegroundDeviceTests {
    fun run(instrumentation: Instrumentation): Int {
        val scenarios = listOf("native_light", "native_dark", "native_amoled").flatMap { mode ->
            listOf("susfs_$mode", "dialog_$mode", "superkey_$mode")
        } + listOf("material_light", "material_dark", "fina_light", "fina_dark").map { "superkey_$it" }
        for (scenario in scenarios) {
            val activity = instrumentation.startActivitySync(Intent().setClassName(instrumentation.targetContext,
                "com.shixiaoshi0417.lunexsu.ui.ThemeRenderingTestActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("case", "foreground_$scenario"))
            try {
                val deadline = SystemClock.uptimeMillis() + 10_000
                var result = ""
                while (result.isEmpty() && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync { result = activity.javaClass.getMethod("foregroundResult").invoke(activity) as String }
                    if (result.isEmpty()) SystemClock.sleep(50)
                }
                check(result == "PASS") { "$scenario: $result" }
                if (scenario.startsWith("superkey")) {
                    instrumentation.waitForIdleSync()
                    var expected = 0
                    instrumentation.runOnMainSync { expected = activity.javaClass.getMethod("foregroundPixel").invoke(activity) as Int }
                    val bitmap = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        val renderDeadline = SystemClock.uptimeMillis() + 10_000
                        var matched = false
                        while (!matched && SystemClock.uptimeMillis() < renderDeadline) {
                            val latch = CountDownLatch(1)
                            var status = -1
                            instrumentation.runOnMainSync {
                                PixelCopy.request(activity.window, bitmap, { status = it; latch.countDown() }, Handler(Looper.getMainLooper()))
                            }
                            check(latch.await(3, TimeUnit.SECONDS)) { "$scenario: PixelCopy timeout" }
                            if (status == PixelCopy.SUCCESS) {
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                matched = pixels.count { it == expected } >= 3
                            }
                            if (!matched) {
                                instrumentation.waitForIdleSync()
                                SystemClock.sleep(50)
                            }
                        }
                        check(matched) { "$scenario: error text did not render in its theme error color" }
                    } finally { bitmap.recycle() }
                }
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        }
        return scenarios.size
    }
}
