package com.shixiaoshi0417.lunexsu.ui.theme.pack

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

private object PackMusic {
    var requested by mutableStateOf(false)
}

/** One music player for the activity, never one player per precomposed pager page. */
@Composable
internal fun ThemePackMusic() {
    val pack = ThemePackState.active.takeIf { LocalFinaThemeUi.current }
    val media = pack?.plan?.media
    val file = media?.music?.let { File(pack.directory, it) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(file, lifecycle) {
        // Always start paused, even when the pack requests autoplay. Playback needs a user tap.
        PackMusic.requested = false
        val player = file?.let { MediaPlayer() }
        var ready = false
        var disposed = false
        fun update() {
            if (disposed || !ready || player == null) return
            runCatching {
                if (PackMusic.requested && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player.start()
                else if (player.isPlaying) player.pause()
            }
        }
        if (player != null && media != null) {
            player.setOnErrorListener { _, _, _ ->
                if (!disposed) { ready = false; PackMusic.requested = false }
                true
            }
            player.setOnCompletionListener { if (!disposed) PackMusic.requested = false }
            player.setOnPreparedListener { if (!disposed) { ready = true; update() } }
            runCatching {
                player.setDataSource(requireNotNull(file).path)
                player.isLooping = media.looping
                player.setVolume(media.musicVolume, media.musicVolume)
                player.prepareAsync()
            }.onFailure { PackMusic.requested = false }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        // UI requests are delivered through a separate effect below.
        MusicPlayback.update = { update() }
        onDispose {
            disposed = true
            MusicPlayback.update = null
            lifecycle.removeObserver(observer)
            player?.release()
        }
    }
    LaunchedEffect(PackMusic.requested) { MusicPlayback.update?.invoke() }
}

private object MusicPlayback { var update: (() -> Unit)? = null }

@Composable
internal fun PackMusicButton() {
    if (!LocalFinaThemeUi.current) return
    val name = ThemePackState.active?.plan?.media?.music ?: return
    Box(Modifier.size(48.dp).toggleable(PackMusic.requested, role = Role.Switch,
        onValueChange = { PackMusic.requested = it }), contentAlignment = Alignment.Center) {
        Icon(if (PackMusic.requested) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = name, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun PackVideoBackground(file: File, volume: Float, modifier: Modifier, isCurrentPage: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Off-screen pages do not allocate a decoder or continue playing audio.
    if (isCurrentPage && resumed) {
        key(file) {
            AndroidView(factory = { PackVideoView(it, file, volume) }, modifier = modifier,
                onRelease = { it.release() }, update = {})
        }
    }
}

private class PackVideoView(context: Context, private val file: File, private val volume: Float) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init { surfaceTextureListener = this }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        output = Surface(texture)
        val media = MediaPlayer()
        player = media
        media.setOnErrorListener { _, _, _ -> release(); true }
        media.setOnVideoSizeChangedListener { _, w, h -> videoWidth = w; videoHeight = h; crop() }
        media.setOnPreparedListener { if (player === it) { crop(); it.start() } }
        runCatching {
            media.setDataSource(file.path)
            media.setSurface(output)
            media.isLooping = true
            media.setVolume(volume, volume)
            media.prepareAsync()
        }.onFailure { release() }
    }

    private fun crop() {
        if (width == 0 || height == 0 || videoWidth == 0 || videoHeight == 0) return
        val scale = maxOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        setTransform(Matrix().apply {
            setScale(videoWidth * scale / width, videoHeight * scale / height, width / 2f, height / 2f)
        })
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = crop()
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { release(); return true }
    fun release() { player?.release(); player = null; output?.release(); output = null }
}
