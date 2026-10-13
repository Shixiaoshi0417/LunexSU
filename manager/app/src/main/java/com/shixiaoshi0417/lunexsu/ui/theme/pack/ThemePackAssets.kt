package com.shixiaoshi0417.lunexsu.ui.theme.pack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shixiaoshi0417.lunexsu.ui.theme.isInDarkTheme
import com.shixiaoshi0417.lunexsu.R
import java.io.File

@Composable
internal fun packSurface(id: String?): PackSurface? = if (LocalFinaThemeUi.current)
    id?.let { ThemePackState.active?.plan?.visuals?.surfaces?.get(it) } else null

@Composable
internal fun packSurfaceColor(id: String?, fallback: Color): Color {
    val surface = packSurface(id) ?: return fallback
    val pack = ThemePackState.active ?: return fallback
    val file = surface.image?.takeIf { surface.enabled }?.let { File(pack.directory, it) }
    val luminance = rememberPackImageLuminance(file)
    if (file == null || luminance < 0 || surface.opacity(isInDarkTheme()) < 0.5f) return fallback
    return if (luminance * (1f - surface.dim(isInDarkTheme())) > 0.5f) Color(0xFF161616) else Color.White
}

@Composable
internal fun PackSurfaceBackground(id: String, modifier: Modifier = Modifier) {
    if (!LocalFinaThemeUi.current) return
    val pack = ThemePackState.active ?: return
    val surface = pack.plan.visuals.surfaces[id]?.takeIf { it.enabled } ?: return
    val name = surface.image ?: return
    val file = File(pack.directory, name)
    val luminance = rememberPackImageLuminance(file)
    val dim = surface.dim(isInDarkTheme())
    val scrim = if (luminance >= 0 && luminance * (1f - dim) > 0.5f) Color.White else Color.Black
    Box(modifier) {
        PackFileImage(file, Modifier.fillMaxSize().alpha(surface.opacity(isInDarkTheme())),
            isCurrentPage = ThemePackState.visiblePage == 0)
        Box(Modifier.fillMaxSize().background(scrim.copy(alpha = dim)))
    }
}

@Composable
internal fun PackHomeTitle(fallback: @Composable () -> Unit) {
    if (!LocalFinaThemeUi.current) { fallback(); return }
    val pack = ThemePackState.active
    val title = pack?.plan?.visuals?.title
    val name = title?.image
    if (pack == null || title == null || name == null) { fallback(); return }
    val description = stringResource(R.string.app_name)
    Box(Modifier.width(180.dp).height(40.dp).offset(x = (title.offsetX * 100f).dp)
        .semantics { contentDescription = description }
        .alpha(if (isInDarkTheme()) title.nightOpacity else title.dayOpacity)) {
        PackFileImage(File(pack.directory, name), Modifier.fillMaxSize(), fit = true,
            isCurrentPage = ThemePackState.visiblePage == 0)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = title.dim)))
    }
}

@Composable
internal fun PackNavIcon(index: Int, description: String, modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit) {
    if (!LocalFinaThemeUi.current) { fallback(); return }
    val pack = ThemePackState.active
    val name = pack?.plan?.visuals?.navIcons?.get(index)
    if (pack == null || name == null) { fallback(); return }
    PackFileImage(File(pack.directory, name), modifier.size(24.dp).semantics { contentDescription = description }, fit = true)
}
