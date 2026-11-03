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
import coil3.toBitmap
import coil3.request.ImageRequest
import coil3.request.allowHardware
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import me.rerere.rikkahub.data.datastore.WallpaperScale

/** Coil decodes to the measured viewport; no full-resolution bitmap or extra browser engine is held. */
@Composable
fun WallpaperBackdrop(
    source: String,
    appearance: AppearanceSettings,
    modifier: Modifier = Modifier,
    onLoaded: (Boolean) -> Unit = {},
    onTone: (Color?) -> Unit = {},
) {
    val options = appearance.normalized()
    val context = LocalContext.current
    val request = remember(context, source) { ImageRequest.Builder(context).data(source).allowHardware(false).build() }
    val scope = rememberCoroutineScope()
    var sampled by remember(source) { mutableStateOf(false) }
    DisposableEffect(source) { onTone(null); onDispose {} }
    var loaded by remember(source) { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        val blur = if (Build.VERSION.SDK_INT >= 31 && options.wallpaperBlur > 0f)
            Modifier.blur(options.wallpaperBlur.dp) else Modifier
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = if (options.wallpaperScale == WallpaperScale.FIT) ContentScale.Fit else ContentScale.Crop,
            alignment = BiasAlignment(options.wallpaperAlignmentX * 2f - 1f, options.wallpaperAlignmentY * 2f - 1f),
            modifier = Modifier.fillMaxSize().then(blur).alpha(options.wallpaperOpacity),
            onSuccess = { result ->
                loaded = true; onLoaded(true)
                if (!sampled) {
                    sampled = true
                    scope.launch {
                        val tone = withContext(Dispatchers.Default) {
                            runCatching {
                                val bitmap = result.result.image.toBitmap(width = 24, height = 24, config = android.graphics.Bitmap.Config.ARGB_8888)
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                wallpaperTone(pixels)
                            }.onFailure { android.util.Log.w("WallpaperTone", "sample failed", it) }.getOrNull()
                        }
                        android.util.Log.d("WallpaperTone", "sample completed")
                        onTone(tone)
                    }
                }
            },
            onError = { loaded = false; sampled = false; onLoaded(false); onTone(null) },
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
