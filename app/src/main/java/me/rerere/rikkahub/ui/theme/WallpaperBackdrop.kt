package me.rerere.rikkahub.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import me.rerere.rikkahub.data.datastore.WallpaperScale

/** Coil decodes to the measured viewport; no full-resolution bitmap or extra browser engine is held. */
@Composable
fun WallpaperBackdrop(
    source: String,
    appearance: AppearanceSettings,
    modifier: Modifier = Modifier,
    onLoaded: (Boolean) -> Unit = {},
) {
    val options = appearance.normalized()
    var loaded by remember(source) { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        val blur = if (Build.VERSION.SDK_INT >= 31 && options.wallpaperBlur > 0f)
            Modifier.blur(options.wallpaperBlur.dp) else Modifier
        AsyncImage(
            model = source,
            contentDescription = null,
            contentScale = if (options.wallpaperScale == WallpaperScale.FIT) ContentScale.Fit else ContentScale.Crop,
            alignment = BiasAlignment(options.wallpaperAlignmentX * 2f - 1f, options.wallpaperAlignmentY * 2f - 1f),
            modifier = Modifier.fillMaxSize().then(blur).alpha(options.wallpaperOpacity),
            onSuccess = { loaded = true; onLoaded(true) },
            onError = { loaded = false; onLoaded(false) },
        )
        if (loaded) WallpaperProtection(options)
    }
}

/** Theme-colored endcaps keep both system bars readable even over white, black or busy images. */
@Composable
fun WallpaperProtection(appearance: AppearanceSettings, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.background
    val scrim = appearance.normalized().wallpaperScrim
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to color.copy(alpha = 0.96f),
        0.10f to color.copy(alpha = maxOf(scrim, 0.35f)),
        0.32f to color.copy(alpha = scrim),
        0.78f to color.copy(alpha = scrim),
        1f to color.copy(alpha = 0.96f),
    )))
}
