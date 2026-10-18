package com.shixiaoshi0417.lunexsu

import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import kotlin.math.abs

/** Optional framework-drawable checks; compiling them is not device validation. */
internal object LauncherIconDeviceTests {
    fun run(instrumentation: Instrumentation): Int {
        var cases = 0
        val base = instrumentation.targetContext
        for (density in listOf(160, 240, 320, 480, 640)) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = density
            })
            for (size in listOf(16, 24, 32, 48, 72, 108)) {
                val icon = context.getDrawable(R.mipmap.ic_launcher)!!.mutate() as AdaptiveIconDrawable
                icon.setBounds(0, 0, size, size)
                checkRatio(icon.foreground, density, size)
                cases++
                if (Build.VERSION.SDK_INT >= 33) {
                    checkRatio(checkNotNull(icon.monochrome), density, size)
                    cases++
                }
            }
        }
        return cases
    }

    private fun checkRatio(layer: Drawable, density: Int, size: Int) {
        val inset = layer as InsetDrawable
        val child = checkNotNull(inset.drawable)
        val bounds = layer.bounds.width()
        check(bounds > 0 && child.bounds.width() > 0) { "$density/$size: empty icon artwork" }
        check(abs(child.bounds.width() - bounds * (2f / 3f)) <= 2.01f) {
            "$density/$size: artwork ${child.bounds.width()} does not scale with layer $bounds"
        }
    }
}
