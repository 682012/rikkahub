package me.rerere.rikkahub.ui.startup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.startup.StartupMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 一段固定长度的动画，播完就通知调用方把自己摘掉，不会常驻，也没有循环。 */
private const val INTRO_DURATION_MS = 520

private const val ENTER_END = 0.22f
private const val FADE_START = 0.62f
private const val SCRIM_ALPHA = 0.10f

/**
 * 冷启动时的短前景开场。
 *
 * 刻意做成"非阻塞"的一段：
 * - 只画一层 Canvas，不接手势（没有 pointerInput），底下的页面照常可以点、可以滑；
 * - 底部内容在第一帧就已经组合好了，这里不会去等任何加载；
 * - 一次有限动画，播完 [onFinished] 把这块整个移除，不留动画、不留后台任务。
 * 系统把动画关掉时调用方根本不会挂上它（见 StartupIntroPolicy）。
 */
@Composable
fun StartupIntroOverlay(
    motion: StartupMotion,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val finish = rememberUpdatedState(onFinished)
    val scrimColor = MaterialTheme.colorScheme.surface
    val accentColor = MaterialTheme.colorScheme.primary
    val glowColor = MaterialTheme.colorScheme.tertiary

    LaunchedEffect(motion) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = INTRO_DURATION_MS, easing = LinearEasing),
        )
        finish.value()
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        // 在绘制阶段读进度：只重画，不重组
        val p = progress.value
        val enter = (p / ENTER_END).coerceIn(0f, 1f)
        val exitRaw = ((p - FADE_START) / (1f - FADE_START)).coerceIn(0f, 1f)
        val exit = exitRaw * exitRaw
        val glyphAlpha = enter * (1f - exit)

        // 很淡的过渡层，始终保留底下内容的可读性，不掩盖加载问题
        val scrimAlpha = SCRIM_ALPHA * (1f - exit)
        if (scrimAlpha > 0.004f) {
            drawRect(color = scrimColor.copy(alpha = scrimAlpha))
        }
        if (glyphAlpha <= 0.01f) return@Canvas

        when (motion) {
            StartupMotion.RING -> drawOrbitRing(p, glyphAlpha, accentColor, glowColor)
            StartupMotion.DOTS -> drawGatheringDots(p, glyphAlpha, accentColor, glowColor)
            StartupMotion.OFF -> Unit
        }
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

private fun easeOutCubic(progress: Float): Float {
    val inverted = 1f - progress.coerceIn(0f, 1f)
    return 1f - inverted * inverted * inverted
}
