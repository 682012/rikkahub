/*
 * 六花 (Rikka) 启动签名动效；默认风格，接入 StartupIntroOverlay。
 *
 * 形态：六片花瓣绕中心成风车状（六重对称），中心一枚六边形核。
 * 与 RING/DOTS/AURORA 三款并列时可作为第四种 StartupMotion 选项。
 *
 * 时间轴：进度归一化，由用户配置的内建时长驱动。
 * 关键点：p≈0.55 时形态已完全成形，之后留出静止可读窗口再淡出，避免“刚成形就消失”。
 */
package me.rerere.rikkahub.ui.startup

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val PETALS = 6
private const val HUB = 0.085f // 核半径 / 花瓣外径

private fun easeOutCubic(t: Float): Float = 1f - (1f - t).coerceIn(0f, 1f).pow(3)
private fun window(p: Float, start: Float, span: Float): Float =
    if (span <= 0f) (if (p >= start) 1f else 0f) else ((p - start) / span).coerceIn(0f, 1f)
private fun overshoot(t: Float): Float {
    val c = 1.55f
    val x = t - 1f
    return 1f + (c + 1f) * x * x * x + c * x * x
}
private fun bump(t: Float): Float = if (t <= 0f || t >= 1f) 0f else sin(PI * t).pow(2).toFloat()

/** 单位坐标下的单片花瓣（+x 朝外），供 rotate/scale 变换后直接填充。 */
private fun petalPath(): Path = Path().apply {
    moveTo(0.17f, 0.035f)
    cubicTo(0.27f, 0.36f, 0.84f, 0.47f, 1.00f, -0.045f)
    cubicTo(0.80f, -0.19f, 0.50f, -0.21f, 0.17f, 0.035f)
    close()
}

private fun hubPath(r: Float): Path = Path().apply {
    repeat(PETALS) { k ->
        val a = (k * 60f + 30f) * PI.toFloat() / 180f
        val x = r * cos(a)
        val y = r * sin(a)
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * 启动入场。progress 0..1 对应整段时长；alpha 只乘透明度，不驱动几何。
 * fadeStart=0.70（280ms）或 0.72（420ms）。
 */
internal fun DrawScope.drawRikkaIntro(
    progress: Float,
    alpha: Float,
    accent: Color,
    glow: Color,
    fadeStart: Float = 0.70f,
) {
    if (alpha <= 0.01f) return
    val r = rikkaRadius()
    val center = Offset(size.width / 2f, size.height / 2f)
    val exit = window(progress, fadeStart, 1f - fadeStart)
    val fade = 1f - exit * exit
    val a = alpha * fade
    if (a <= 0.01f) return

    drawRikkaGlow(center, r, a * easeOutCubic(window(progress, 0.25f, 0.35f)), glow)

    val spin = (1f - easeOutCubic(window(progress, 0f, 0.62f))) * -36f
    val petal = petalPath()
    for (i in 0 until PETALS) {
        val t = window(progress, 0.02f + i * 0.035f, 0.36f)
        if (t <= 0f) continue
        val s = 0.42f + 0.58f * overshoot(t)
        val rot = spin - 90f + i * 360f / PETALS + (1f - easeOutCubic(t)) * -48f
        val pa = a * (t * 3.2f).coerceIn(0f, 1f)
        withTransform({
            rotate(rot, center)
            scale(r * s, r * s, center)
            translate(center.x, center.y)
        }) {
            drawPath(petal, rikkaBrush(accent, glow, pa))
        }
    }

    val hubScale = overshoot(window(progress, 0.30f, 0.26f))
    if (hubScale > 0.02f) {
        withTransform({ translate(center.x, center.y) }) {
            drawPath(hubPath(r * HUB * hubScale), accent.copy(alpha = a))
        }
    }
}

/** 动态 logo（循环）。t 0..1 无缝：波峰顺时针扫过六瓣，整体每周期转 60°。 */
internal fun DrawScope.drawRikkaLoop(t: Float, accent: Color, glow: Color) {
    val r = rikkaRadius()
    val center = Offset(size.width / 2f, size.height / 2f)
    val breath = bump(window(t, 0.62f, 0.38f))
    drawRikkaGlow(center, r, 0.55f + 0.45f * breath, glow)

    val spin = window(t, 0.10f, 0.62f).let { it * it * (3f - 2f * it) } * 60f
    val petal = petalPath()
    for (i in 0 until PETALS) {
        val w = bump(window(t, 0.04f + i * 0.07f, 0.30f))
        val rot = spin - 90f + i * 360f / PETALS + w * 9f
        withTransform({
            rotate(rot, center)
            scale(r * (1f + 0.10f * w), r * (1f + 0.10f * w), center)
            translate(center.x, center.y)
        }) {
            drawPath(petal, rikkaBrush(accent, glow, 1f))
        }
    }
    withTransform({ translate(center.x, center.y) }) {
        drawPath(hubPath(r * HUB * (1f + 0.28f * breath)), accent)
    }
}

/** 外径：默认 32dp（64dp 直径），窄屏按短边比例收，避免铺满。 */
private fun DrawScope.rikkaRadius(): Float {
    val configured = 32.dp.toPx()
    val limit = size.minDimension * 0.42f
    return minOf(configured, limit)
}

private fun DrawScope.drawRikkaGlow(center: Offset, r: Float, strength: Float, glow: Color) {
    if (strength <= 0.01f) return
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(glow.copy(alpha = glow.alpha * strength), Color.Transparent),
            center = center,
            radius = r * 1.35f,
        ),
        radius = r * 1.35f,
        center = center,
    )
}

/** 花瓣填充：靠中心一侧压暗到 accent/glow 的混合，外侧为纯 accent。 */
private fun rikkaBrush(accent: Color, glow: Color, alpha: Float): Brush =
    Brush.linearGradient(
        colors = listOf(
            lerp(accent, glow, 0.45f).copy(alpha = alpha),
            accent.copy(alpha = alpha),
        ),
        start = Offset.Zero,
        end = Offset(1f, 0f),
    )

/** 需要描边版本时用（细线主题）。当前填充版不用。 */
@Suppress("unused")
private fun DrawScope.drawRikkaOutline(progress: Float, alpha: Float, accent: Color) {
    val r = rikkaRadius()
    val center = Offset(size.width / 2f, size.height / 2f)
    val petal = petalPath()
    for (i in 0 until PETALS) {
        withTransform({
            rotate(-90f + i * 360f / PETALS, center)
            scale(r, r, center)
            translate(center.x, center.y)
        }) {
            drawPath(
                petal,
                accent.copy(alpha = alpha),
                style = Stroke(width = 1.8.dp.toPx() / r, cap = StrokeCap.Round),
            )
        }
    }
}
