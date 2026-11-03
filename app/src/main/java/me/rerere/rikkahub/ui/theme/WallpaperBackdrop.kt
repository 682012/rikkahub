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
    onSample: (WallpaperSample?) -> Unit = {},
) {
    val options = appearance.normalized()
    val context = LocalContext.current
    val request = remember(context, source) { ImageRequest.Builder(context).data(source).allowHardware(false).build() }
    val scope = rememberCoroutineScope()
    var sampled by remember(source) { mutableStateOf(false) }
    DisposableEffect(source) { onTone(null); onSample(null); onDispose {} }
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
                        // 同一张小图同时给出主色调与明暗两端：沉浸模式的对比预算要用后者。
                        val sample = withContext(Dispatchers.Default) {
                            runCatching {
                                val bitmap = result.result.image.toBitmap(width = 24, height = 24, config = android.graphics.Bitmap.Config.ARGB_8888)
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                wallpaperSample(pixels)
                            }.onFailure { android.util.Log.w("WallpaperTone", "sample failed", it) }.getOrNull()
                        }
                        android.util.Log.d("WallpaperTone", "sample completed")
                        onSample(sample)
                        onTone(sample?.tone)
                    }
                }
            },
            onError = { loaded = false; sampled = false; onLoaded(false); onTone(null); onSample(null) },
        )
        if (loaded) WallpaperProtection(options)
    }
}

/**
 * 壁纸遮罩。
 *
 * 默认（r9 观感）上下两端压到 0.96，保证状态栏/导航栏在任何图上都读得清。
 * 沉浸模式换成“整片均匀压一格 + 上下一条很窄的加强带”：顶部栏、输入区能透出壁纸，
 * 又不会在屏幕上下各留一大块黑边。
 */
@Composable
fun WallpaperProtection(appearance: AppearanceSettings, modifier: Modifier = Modifier) {
    val normalized = appearance.normalized()
    val plan = LocalImmersiveContrast.current
    val immersive = LocalImmersiveChat.current && plan != null
    // 沉浸模式下压暗色必须和预算里那份一致，否则实际画面会比算出来的更亮/更暗。
    val color = if (immersive && plan != null) plan.scrimColor else MaterialTheme.colorScheme.background
    // 整层压暗不许比对比预算更亮，否则薄文字底会读不清。
    val scrim = if (immersive) {
        maxOf(normalized.wallpaperScrim, plan?.scrim ?: 0f)
    } else {
        normalized.wallpaperScrim
    }
    val stops = remember(color, scrim, immersive) { wallpaperProtectionStops(scrim, immersive, color) }
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(*stops.toTypedArray())))
}
