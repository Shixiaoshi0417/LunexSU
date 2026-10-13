// Wallpaper behavior adapted from FinaSU, GPL-3.0; Android drawable decoding is shared by both UIs.
package com.shixiaoshi0417.lunexsu.ui.theme.pack

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.shixiaoshi0417.lunexsu.ui.theme.isInDarkTheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Average luminance of each wallpaper file, measured once from a 64 px software decode. */
private object ThemePackLuminance {
    private val cache = ConcurrentHashMap<String, Float>()

    private fun key(file: File) = "${file.path}:${file.lastModified()}:${file.length()}"

    fun cached(file: File?): Float? = file?.let { cache[key(it)] }

    suspend fun measure(file: File): Float = cache[key(file)] ?: withContext(Dispatchers.IO) {
        val pixels = try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                if (longest > 64) decoder.setTargetSize(
                    (info.size.width.toLong() * 64 / longest).toInt().coerceAtLeast(1),
                    (info.size.height.toLong() * 64 / longest).toInt().coerceAtLeast(1),
                )
            }
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            IntArray(0)
        }
        averageLuminance(pixels).also { cache[key(file)] = it }
    }
}

/** Luminance (0..1) of the wallpaper on the visible page, or -1 while unknown. */
@Composable
internal fun rememberThemePackLuminance(): Float {
    val pack = ThemePackState.active
    val file = pack?.plan?.background?.image(ThemePackState.visiblePage)?.let { File(pack.directory, it) }
    return rememberPackImageLuminance(file)
}

@Composable
internal fun rememberPackImageLuminance(file: File?): Float {
    val luminance by produceState(ThemePackLuminance.cached(file) ?: -1f, file) {
        value = file?.let { ThemePackLuminance.measure(it) } ?: -1f
    }
    return luminance
}

@Composable
internal fun ThemePackWallpaper(page: Int = 0, isCurrentPage: Boolean = true) {
    if (!LocalThemePackBackground.current) return
    val pack = ThemePackState.active ?: return
    val config = pack.plan.background
    val name = config.image(page)
    val file = name?.let { File(pack.directory, it) }
    val routeVisible = LocalThemeRouteVisible.current
    val playing = isCurrentPage && routeVisible
    LaunchedEffect(page, playing) {
        if (playing) ThemePackState.visiblePage = page
    }
    val dark = isInDarkTheme()
    val dim = LocalWallpaperDim.current ?: effectiveWallpaperDim(config, dark)
    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF17171A) else Color(0xFFF5F5FA))) {
        file?.let {
            PackFileImage(it, Modifier.fillMaxSize().blur(config.blur.dp), isCurrentPage = playing)
        }
        pack.plan.media.video?.let {
            PackVideoBackground(File(pack.directory, it), pack.plan.media.videoVolume, Modifier.fillMaxSize(), playing)
        }
        if (file != null || pack.plan.media.video != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }
    }
}

/** One bounded native decoder for wallpapers, surfaces, titles and navigation images (including GIF). */
@Composable
internal fun PackFileImage(file: File, modifier: Modifier = Modifier, fit: Boolean = false,
    isCurrentPage: Boolean = true) {
    val drawable by produceState<Drawable?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 2048) decoder.setTargetSize(
                        (info.size.width.toLong() * 2048 / longest).toInt().coerceAtLeast(1),
                        (info.size.height.toLong() * 2048 / longest).toInt().coerceAtLeast(1),
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("ThemePackImage", "Cannot decode ${file.name}", error)
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
    val playing = isCurrentPage && LocalThemeRouteVisible.current
    DisposableEffect(drawable, lifecycle, playing) {
        fun update() {
            (drawable as? AnimatedImageDrawable)?.let {
                if (playing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) it.start() else it.stop()
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); (drawable as? AnimatedImageDrawable)?.stop() }
    }
    // Keep the image's layout node even while decoding. Otherwise an all-hidden status tile
    // can give its match-parent image a zero-sized target during intrinsic measurement.
    Canvas(modifier.clipToBounds()) {
        drawable?.let { image ->
            // Draw through Compose's canvas so pager/route clipping applies to native drawables.
            // Observe animated-drawable invalidations during draw, without recomposing the UI.
            frame.let {
                drawIntoCanvas { canvas ->
                    val width = image.intrinsicWidth.coerceAtLeast(1)
                    val height = image.intrinsicHeight.coerceAtLeast(1)
                    val scale = if (fit) minOf(size.width / width, size.height / height)
                        else maxOf(size.width / width, size.height / height)
                    canvas.save()
                    canvas.translate((size.width - width * scale) / 2, (size.height - height * scale) / 2)
                    canvas.scale(scale, scale)
                    image.setBounds(0, 0, width, height)
                    image.draw(canvas.nativeCanvas)
                    canvas.restore()
                }
            }
        }
    }
}
