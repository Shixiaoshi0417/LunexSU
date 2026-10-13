package com.shixiaoshi0417.lunexsu

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Optional real-render regressions. Compiling this suite is not equivalent to running it. */
internal object ThemeRenderingDeviceTests {
    fun run(instrumentation: Instrumentation): Bundle {
        val cases = listOf(
            Triple("card", 0f, Color.BLUE),
            Triple("card", 0.5f, Color.rgb(0, 128, 127)),
            Triple("card", 1f, Color.GREEN),
            Triple("route", 1f, Color.BLUE),
            Triple("route_wallpaper", 1f, Color.BLUE),
            Triple("native", 1f, Color.RED),
        )
        for ((scenario, opacity, expected) in cases) {
            val activity = instrumentation.startActivitySync(Intent().setClassName(instrumentation.targetContext,
                "com.shixiaoshi0417.lunexsu.ui.ThemeRenderingTestActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("case", scenario).putExtra("opacity", opacity))
            try {
                val deadline = SystemClock.uptimeMillis() + 10_000
                var actual = 0
                var matched = false
                while (SystemClock.uptimeMillis() < deadline && !matched) {
                    instrumentation.waitForIdleSync()
                    val bitmap = Bitmap.createBitmap(activity.window.decorView.width,
                        activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                    val latch = CountDownLatch(1)
                    var result = -1
                    instrumentation.runOnMainSync {
                        PixelCopy.request(activity.window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
                    }
                    check(latch.await(3, TimeUnit.SECONDS)) { "PixelCopy timeout" }
                    if (result == PixelCopy.SUCCESS) {
                        actual = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        matched = abs(Color.red(actual) - Color.red(expected)) <= 5 &&
                            abs(Color.green(actual) - Color.green(expected)) <= 5 &&
                            abs(Color.blue(actual) - Color.blue(expected)) <= 5
                    }
                    bitmap.recycle()
                    if (!matched) SystemClock.sleep(50)
                }
                check(matched) { "$scenario/$opacity expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}" }
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        }
        return Bundle().apply { putString("theme_rendering", "PASS: ${cases.size} hidden-card alpha, route isolation and native UI pixel cases") }
    }
}
