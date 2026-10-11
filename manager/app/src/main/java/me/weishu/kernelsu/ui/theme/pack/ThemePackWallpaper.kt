// Wallpaper behavior adapted from FolkSU, GPL-3.0; Android drawable decoding is shared by both UIs.
package me.weishu.kernelsu.ui.theme.pack

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.theme.isInDarkTheme
import java.io.File

@Composable
internal fun ThemePackWallpaper(page: Int = 0, isCurrentPage: Boolean = true) {
    if (!LocalThemePackBackground.current) return
    val pack = ThemePackState.active ?: return
    val config = pack.plan.background
    val name = config.image(page)
    val file = name?.let { File(pack.directory, it) }
    val drawable by produceState<Drawable?>(null, file) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 2048) decoder.setTargetSize(
                        (info.size.width.toLong() * 2048 / longest).toInt().coerceAtLeast(1),
                        (info.size.height.toLong() * 2048 / longest).toInt().coerceAtLeast(1),
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        }
    }
    var frame by remember(drawable) { mutableIntStateOf(0) }
    DisposableEffect(drawable) {
        val handler = Handler(Looper.getMainLooper())
        drawable?.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { frame++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {
                handler.postAtTime(what, time)
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { handler.removeCallbacks(what) }
        }
        onDispose {
            drawable?.callback = null
            handler.removeCallbacksAndMessages(null)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(drawable, lifecycle, isCurrentPage) {
        fun update() {
            (drawable as? AnimatedImageDrawable)?.let {
                if (isCurrentPage && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) it.start() else it.stop()
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); (drawable as? AnimatedImageDrawable)?.stop() }
    }
    val dark = isInDarkTheme()
    val dim = if (config.dualDim) { if (dark) config.nightDim else config.dayDim } else config.dim
    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF17171A) else Color(0xFFF5F5FA))) {
        drawable?.let { image ->
            // Draw through Compose's canvas so pager/route clipping also applies to native drawables.
            Canvas(Modifier.fillMaxSize().clipToBounds().blur(config.blur.dp)) {
                // Observe animated-drawable invalidations during draw, without recomposing the UI.
                frame.let {
                    drawIntoCanvas { canvas ->
                        val width = image.intrinsicWidth.coerceAtLeast(1)
                        val height = image.intrinsicHeight.coerceAtLeast(1)
                        val scale = maxOf(size.width / width, size.height / height)
                        canvas.save()
                        canvas.translate((size.width - width * scale) / 2, (size.height - height * scale) / 2)
                        canvas.scale(scale, scale)
                        image.setBounds(0, 0, width, height)
                        image.draw(canvas.nativeCanvas)
                        canvas.restore()
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }
    }
}
