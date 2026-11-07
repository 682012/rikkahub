package me.rerere.rikkahub.ui.startup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import me.rerere.rikkahub.startup.StartupCustomIntro
import me.rerere.rikkahub.startup.StartupFit
import me.rerere.rikkahub.startup.StartupIntroMaterialStore
import me.rerere.rikkahub.startup.StartupIntroRules
import me.rerere.rikkahub.startup.StartupIntroScene
import me.rerere.rikkahub.startup.StartupMediaKind
import me.rerere.rikkahub.startup.StartupMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 内容进入的收尾（前 12% 铺开）与退场起点（后 50% 开始变淡）。 */
private const val ENTER_END = 0.12f
private const val FADE_START = 0.50f

/** 自定义素材比内建动效更容易"抢眼"，所以退场更早开始，保证不会在画面上停住。 */
private const val MEDIA_FADE_START = 0.45f

/**
 * 冷启动时的短前景开场。
 *
 * 刻意做成"非阻塞"的一段：
 * - 只画一层，不接手势（没有 pointerInput），底下的页面照常可以点、可以滑；
 * - 底部内容在第一帧就已经组合好了，这里不等任何加载；素材是否可用在后台判断；
 * - 一次有限动画，时长到了就 [onFinished] 把这块整个移除。时长是硬上限：
 *   素材解码失败、视频解不出来、文件被系统清掉，都只是回落到内建风格或直接淡完，
 *   绝不会把开场留在屏幕上。
 * 系统把动画关掉时调用方根本不会挂上它（见 StartupIntroPolicy）。
 */
@Composable
fun StartupIntroOverlay(
    scene: StartupIntroScene,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = scene.durationMs.coerceIn(1, StartupIntroRules.MAX_DURATION_MS)
    val progress = remember(scene) { Animatable(0f) }
    val finish = rememberUpdatedState(onFinished)

    LaunchedEffect(scene) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
        )
        finish.value()
    }

    Box(modifier = modifier.fillMaxSize()) {
        val custom = scene.custom
        // 素材可用性先当作"内建风格"，真的读到文件再切过去：第一帧永远有东西可画。
        var mediaFile by remember(scene) { mutableStateOf<java.io.File?>(null) }
        var mediaFailed by remember(scene) { mutableStateOf(false) }
        val context = LocalContext.current
        LaunchedEffect(custom) {
            if (custom == null) return@LaunchedEffect
            val resolved = StartupIntroMaterialStore.resolveExistingAsync(context, custom)
            if (resolved == null) mediaFailed = true else mediaFile = resolved
        }

        val showBuiltIn = scene.style != StartupMotion.OFF &&
            (custom == null || mediaFile == null || mediaFailed)
        if (showBuiltIn) {
            BuiltInIntroLayer(style = scene.style, progress = progress)
        }

        val file = mediaFile
        if (custom != null && file != null && !mediaFailed) {
            CustomIntroLayer(
                custom = custom,
                file = file,
                progress = progress,
                onUnavailable = { mediaFailed = true },
            )
        }
    }
}

/** 内建风格：一层 Canvas，在绘制阶段读进度，只重画不重组。 */
@Composable
private fun BuiltInIntroLayer(style: StartupMotion, progress: Animatable<Float, *>) {
    val accentColor = MaterialTheme.colorScheme.primary
    val glowColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val p = progress.value
        val enter = (p / ENTER_END).coerceIn(0f, 1f)
        val exitRaw = ((p - FADE_START) / (1f - FADE_START)).coerceIn(0f, 1f)
        val exit = exitRaw * exitRaw
        val glyphAlpha = enter * (1f - exit)

        // 不叠遮罩：聊天页从第一帧起就是原亮度，动效只是一笔轻描
        if (glyphAlpha <= 0.01f) return@Canvas

        when (style) {
            StartupMotion.RIKKA -> drawRikkaIntro(p, glyphAlpha, accentColor, glowColor, fadeStart = 1f)
            StartupMotion.RING -> drawSignatureOrbit(p, glyphAlpha, accentColor, glowColor)
            StartupMotion.DOTS -> drawSignatureGather(p, glyphAlpha, accentColor, glowColor)
            StartupMotion.AURORA -> drawSignatureBloom(p, glyphAlpha, accentColor, glowColor)
            StartupMotion.OFF, StartupMotion.CUSTOM -> Unit
        }
    }
}

/**
 * 自定义素材：图片/动图走 Coil（动图和别处一样复用已注册的 AnimatedImageDecoder），
 * 小视频走项目已有的 media3 ExoPlayer。
 *
 * 两者都只做淡入淡出 + 轻微放大，不加新引擎、不加循环驻留。
 */
@Composable
private fun CustomIntroLayer(
    custom: StartupCustomIntro,
    file: java.io.File,
    progress: Animatable<Float, *>,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val metrics = LocalContext.current.resources.displayMetrics
    val screenLongSide = maxOf(metrics.widthPixels, metrics.heightPixels)
    val decodeBound = StartupIntroRules.decodeBoundPx(screenLongSide, custom.scalePercent)

    val request = remember(custom, file, decodeBound) {
        ImageRequest.Builder(context)
            .data(file)
            .size(decodeBound, decodeBound)
            .crossfade(false)
            .build()
    }
    val contentScale = when (custom.fit) {
        StartupFit.CONTAIN -> ContentScale.Fit
        StartupFit.COVER -> ContentScale.Crop
    }
    val scale = custom.scalePercent / 100f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // 绘制阶段读进度：整段淡入淡出只有 alpha/scale 两笔
                val p = progress.value
                val enter = (p / ENTER_END).coerceIn(0f, 1f)
                val exitRaw = ((p - MEDIA_FADE_START) / (1f - MEDIA_FADE_START)).coerceIn(0f, 1f)
                val exit = exitRaw * exitRaw
                alpha = enter * (1f - exit)
                val zoom = 0.97f + 0.03f * easeOutCubic(p)
                scaleX = scale * zoom
                scaleY = scale * zoom
            },
    ) {
        when (custom.kind) {
            StartupMediaKind.IMAGE -> AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { onUnavailable() },
            )

            StartupMediaKind.VIDEO -> StartupIntroVideo(file = file, onUnavailable = onUnavailable)
        }
    }
}

/**
 * 视频：静音单次播放，窗口一消失就释放播放器。
 *
 * 只在这里创建 ExoPlayer，而且素材是用户显式导入的；没导入视频的人完全不会走到这段代码。
 */
@Composable
private fun StartupIntroVideo(file: java.io.File, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val latestUnavailable by rememberUpdatedState(onUnavailable)
    var player by remember(file) { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }

    androidx.compose.runtime.DisposableEffect(file) {
        val created = runCatching {
            androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
                volume = 0f
                repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
                playWhenReady = true
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        latestUnavailable()
                    }
                })
                prepare()
            }
        }.getOrNull()
        if (created == null) {
            latestUnavailable()
        } else {
            player = created
        }
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }

    val current = player
    if (current != null) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx -> androidx.media3.ui.PlayerView(ctx).apply { useController = false } },
            modifier = Modifier.fillMaxSize(),
            update = { view -> view.player = current },
        )
    }
}

/**
 * 轨道环：一圈极淡的整圆 + 随进度铺开的 300° 弧 + 沿弧走的光点。
 * 呼应界面里"轨道/纸笺"那种轨道感的说法，但不依赖任何图标资源。
 */
private fun DrawScope.drawOrbitRing(progress: Float, alpha: Float, accent: Color, glow: Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension * 0.13f
    val eased = easeOutCubic(progress)

    drawCircle(
        color = accent.copy(alpha = alpha * 0.28f),
        radius = radius,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )

    val sweep = 300f * eased
    drawArc(
        color = accent.copy(alpha = alpha),
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
    )

    val angle = (-90f + sweep) * (PI / 180.0)
    val dot = Offset(
        x = center.x + radius * cos(angle).toFloat(),
        y = center.y + radius * sin(angle).toFloat(),
    )
    drawCircle(color = glow.copy(alpha = alpha), radius = 3.5.dp.toPx(), center = dot)
    drawCircle(color = glow.copy(alpha = alpha * 0.45f), radius = 1.5.dp.toPx(), center = center)
}

/** 光点聚合：几颗光点从外圈往中心收，收到一半时中心也亮一点。 */
private fun DrawScope.drawGatheringDots(progress: Float, alpha: Float, accent: Color, glow: Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val outer = size.minDimension * 0.17f
    val inner = size.minDimension * 0.07f
    val eased = easeOutCubic(progress)
    val radius = outer + (inner - outer) * eased
    val count = 5

    repeat(count) { index ->
        val angle = (-90f + index * 360f / count) * (PI / 180.0)
        val dot = Offset(
            x = center.x + radius * cos(angle).toFloat(),
            y = center.y + radius * sin(angle).toFloat(),
        )
        drawCircle(
            color = lerp(accent, glow, eased).copy(alpha = alpha * (0.45f + 0.55f * eased)),
            radius = (2f + 2.5f * eased).dp.toPx(),
            center = dot,
        )
    }
    drawCircle(
        color = glow.copy(alpha = alpha * 0.35f * eased),
        radius = (1.5f + 2f * eased).dp.toPx(),
        center = center,
    )
}

/**
 * 柔光散开：中心一团很淡的径向渐变向外铺开，外圈两道错开的细弧跟着淡出。
 * 比轨道环更"软"，但仍然只有几个绘制调用，没有额外依赖。
 */
private fun DrawScope.drawAuroraBloom(progress: Float, alpha: Float, accent: Color, glow: Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val eased = easeOutCubic(progress)
    val bloomRadius = size.minDimension * (0.16f + 0.20f * eased)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                glow.copy(alpha = alpha * 0.30f),
                accent.copy(alpha = alpha * 0.14f),
                Color.Transparent,
            ),
            center = center,
            radius = bloomRadius,
        ),
        radius = bloomRadius,
        center = center,
    )

    val ringRadius = size.minDimension * (0.10f + 0.13f * eased)
    val ringAlpha = alpha * (1f - eased) * 0.7f
    drawArc(
        color = accent.copy(alpha = ringAlpha),
        startAngle = -60f,
        sweepAngle = 210f,
        useCenter = false,
        topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
        size = Size(ringRadius * 2f, ringRadius * 2f),
        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
    )
    drawArc(
        color = glow.copy(alpha = ringAlpha * 0.8f),
        startAngle = 150f,
        sweepAngle = 150f,
        useCenter = false,
        topLeft = Offset(center.x - ringRadius * 1.25f, center.y - ringRadius * 1.25f),
        size = Size(ringRadius * 2.5f, ringRadius * 2.5f),
        style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
    )
}

private fun easeOutCubic(progress: Float): Float {
    val inverted = 1f - progress.coerceIn(0f, 1f)
    return 1f - inverted * inverted * inverted
}
