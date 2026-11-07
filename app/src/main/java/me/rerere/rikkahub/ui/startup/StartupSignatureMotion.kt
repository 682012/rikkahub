package me.rerere.rikkahub.ui.startup

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.startup.StartupMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 冷启动前景的"签名动效"：三种内建风格各自一套原创画法，仍然是 RING / DOTS / AURORA 三种语义。
 *
 * 约束（和 [StartupIntroOverlay] 的调用方式对齐）：
 * - 只画一笔前景图形，不铺遮罩、不画背景、不接手势，聊天页从第一帧起照常是原亮度；
 * - 所有尺寸按 dp 定死（外接半径 40dp，低于约定的 44dp 上限），平板/折叠屏不会跟着变大；
 * - 不引新依赖、不用图片资源、不做无限动画：调用方推 [progress]，这里只把某一帧画出来；
 * - 完全由调用方的 [alpha] 决定整体浓淡，最后淡出也交给调用方，这里不自己"消失"；
 * - 绘制调用有界（最多 17 次 draw*），progress = 0 的几何形态已经成立，
 *   默认 1500ms（用户可调）内能看清"从散到聚"的过程，末尾停在一个干净的静态核。
 *
 * 这套图形是原创的"开放种子/星核"语言，借鉴极简 AI 品牌的节奏（少笔画、柔光、收束到中心），
 * 不代表、也不描述任何厂商的手机启动行为。
 */

/** 图形外接半径：40dp，留出余量给描边和柔光，仍在 44dp 的上限内。 */
private val SIGNATURE_RADIUS = 40.dp

/**
 * 只有极窄画面（分屏、手表）才启用的保护系数。
 * 正常手机上 40dp 永远小于这个上限，所以绝不会因为屏幕小反而把图形缩小成看不清。
 */
private const val NARROW_VIEWPORT_LIMIT = 0.42f

/**
 * 原始设计时标定节奏用的参考值（初版 280ms）；**不是**运行时时钟。
 *
 * m3 起内建风格的时长由用户可调（[me.rerere.rikkahub.startup.StartupIntroRules.BUILTIN_DURATION_MS]，
 * 默认 1500ms），进度仍然只有 0..1，绘制层按 progress 归一化，所以这个常量不参与计时、
 * 也不该被任何代码用来换算毫秒。
 */
internal const val SIGNATURE_PHASE_MS = 280

/** alpha 低于这个值就当没画，省掉一次没意义的绘制。 */
private const val MIN_VISIBLE_ALPHA = 0.01f

private const val LOBE_COUNT = 3
private const val GATHER_DOT_COUNT = 5

private const val GUIDE_STROKE_DP = 0.8f
private const val PETAL_STROKE_DP = 1.7f
private const val TAIL_STROKE_DP = 1.1f
private const val CORE_DOT_DP = 1.8f

/**
 * 三个内建风格的统一入口：调用方只要把 [StartupMotion] 和进度/浓淡丢进来。
 *
 * OFF 与 CUSTOM 什么都不画（CUSTOM 的素材由调用方自己叠那一层），
 * 所以这个函数在任何取值下都是安全的空操作或一次轻量绘制。
 */
internal fun DrawScope.drawSignatureMotion(
    motion: StartupMotion,
    progress: Float,
    alpha: Float,
    accent: Color,
    glow: Color,
) {
    when (motion) {
        StartupMotion.RIKKA -> drawRikkaIntro(progress, alpha, accent, glow)
        StartupMotion.RING -> drawSignatureOrbit(progress = progress, alpha = alpha, accent = accent, glow = glow)
        StartupMotion.DOTS -> drawSignatureGather(progress = progress, alpha = alpha, accent = accent, glow = glow)
        StartupMotion.AURORA -> drawSignatureBloom(progress = progress, alpha = alpha, accent = accent, glow = glow)
        StartupMotion.OFF, StartupMotion.CUSTOM -> Unit
    }
}

/**
 * RING：三瓣流线轨道互相咬合，收成一个"开放种子"。
 *
 * 三个瓣各自是一段压在内侧的圆弧：一开始远离中心、弧段也短（只看得出三条呼吸的轨道），
 * 随着进度它们同时向内收拢、弧段变长、并反向拧正，最后三片弧在 4.8dp 外互相扣住，
 * 围出一个三叶的开放轮廓，中间一点亮芯。呼应"轨道"的语义，但不是整圆也不是转圈。
 */
internal fun DrawScope.drawSignatureOrbit(
    progress: Float,
    alpha: Float,
    accent: Color,
    glow: Color,
) {
    if (alpha <= MIN_VISIBLE_ALPHA) return

    val p: Float = progress.coerceIn(0f, 1f)
    val center: Offset = Offset(size.width / 2f, size.height / 2f)
    val base: Float = signatureBaseRadius()
    val converge: Float = signatureEaseOutCubic(p)

    // 极淡的引导轨道：一条整圆，随汇聚从 0.96 收到 0.60，给"绕着种子转"一个参照。
    val guideRadius: Float = base * signatureLerp(0.96f, 0.60f, converge)
    drawCircle(
        color = accent.copy(alpha = alpha * 0.13f),
        radius = guideRadius,
        center = center,
        style = Stroke(width = GUIDE_STROKE_DP.dp.toPx()),
    )

    val petalDistance: Float = base * signatureLerp(0.70f, 0.46f, converge)
    val petalRadius: Float = base * signatureLerp(0.30f, 0.34f, converge)
    val petalSweep: Float = signatureLerp(104f, 214f, converge)
    val twist: Float = signatureLerp(22f, 0f, converge)
    val tailAlpha: Float = alpha * 0.26f * (1f - converge * 0.55f)

    for (lobe in 0 until LOBE_COUNT) {
        // 瓣心方向：-90° 起步，120° 一片，和启动图标的"上方起笔"一致。
        val outward: Float = -90f + lobe * (360f / LOBE_COUNT)
        val lobeCenter: Offset = signaturePolar(center, outward, petalDistance)
        val topLeft: Offset = Offset(lobeCenter.x - petalRadius, lobeCenter.y - petalRadius)
        val arcSize: Size = Size(petalRadius * 2f, petalRadius * 2f)
        // 弧心朝内（outward + 180°），所以三片弧是"掌心相对"而不是向外开花。
        val arcStart: Float = outward + 180f - petalSweep / 2f + twist

        drawArc(
            color = accent.copy(alpha = alpha * 0.92f),
            startAngle = arcStart,
            sweepAngle = petalSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = PETAL_STROKE_DP.dp.toPx(), cap = StrokeCap.Round),
        )
        // 同一段弧尾上压一道更淡更细的拖影：流线感，不新增粒子。
        drawArc(
            color = lerp(accent, glow, 0.5f).copy(alpha = tailAlpha),
            startAngle = arcStart - 26f,
            sweepAngle = 26f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = TAIL_STROKE_DP.dp.toPx(), cap = StrokeCap.Round),
        )
    }

    // 中心核：一层很窄的柔光 + 一点亮芯，收尾时才交代"种子成型"。
    val coreAlpha: Float = alpha * signatureLerp(0.22f, 0.55f, converge)
    val coreRadius: Float = base * signatureLerp(0.20f, 0.30f, converge)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                glow.copy(alpha = coreAlpha),
                accent.copy(alpha = coreAlpha * 0.35f),
                Color.Transparent,
            ),
            center = center,
            radius = coreRadius,
        ),
        radius = coreRadius,
        center = center,
    )
    drawCircle(
        color = glow.copy(alpha = alpha * 0.92f),
        radius = CORE_DOT_DP.dp.toPx(),
        center = center,
    )
}

/**
 * DOTS：五颗光点沿径向收拢，最后正好落在小五边形花核的五个角上。
 *
 * 语义仍是"光点聚合"，但落点不是一个糊掉的光斑，而是一个几何花核：
 * 细五边形 + 五根短辐条 + 中心亮芯。点的拖影随收拢变短，收尾时画面完全静止。
 */
internal fun DrawScope.drawSignatureGather(
    progress: Float,
    alpha: Float,
    accent: Color,
    glow: Color,
) {
    if (alpha <= MIN_VISIBLE_ALPHA) return

    val p: Float = progress.coerceIn(0f, 1f)
    val center: Offset = Offset(size.width / 2f, size.height / 2f)
    val base: Float = signatureBaseRadius()

    val gather: Float = signatureEaseOutCubic(signatureWindow(p, 0f, 0.72f))
    val coreOpen: Float = signatureEaseOutCubic(signatureWindow(p, 0.40f, 0.95f))

    // 外圈 0.92 收到 0.24；0.22 是花核半径，点正好停在五边形角外一点点。
    val dotRadius: Float = base * signatureLerp(0.92f, 0.24f, gather)
    val spin: Float = signatureLerp(-24f, 0f, gather)
    val tailLength: Float = base * 0.16f * (1f - gather)
    val dotSize: Float = signatureLerp(2.6f, 1.8f, gather).dp.toPx()

    for (index in 0 until GATHER_DOT_COUNT) {
        val angle: Float = -90f + spin + index * (360f / GATHER_DOT_COUNT)
        val dot: Offset = signaturePolar(center, angle, dotRadius)

        if (tailLength > 0.5f) {
            val tailEnd: Offset = signaturePolar(center, angle, dotRadius + tailLength)
            drawLine(
                color = glow.copy(alpha = alpha * 0.22f * (1f - gather)),
                start = dot,
                end = tailEnd,
                strokeWidth = TAIL_STROKE_DP.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        drawCircle(
            color = lerp(accent, glow, gather).copy(alpha = alpha * (0.55f + 0.45f * gather)),
            radius = dotSize,
            center = dot,
        )
    }

    if (coreOpen <= 0f) return

    // 花核：五边形轮廓 + 五根短辐条。半径 2.2dp → 8.8dp，始终在这个小小的核心里。
    val coreRadius: Float = base * 0.22f * coreOpen
    val coreSpin: Float = signatureLerp(-18f, 0f, coreOpen)
    val pentagon: Path = signaturePolygonPath(
        center = center,
        radius = coreRadius,
        sides = GATHER_DOT_COUNT,
        startAngleDegrees = -90f + coreSpin,
    )
    drawPath(
        path = pentagon,
        color = accent.copy(alpha = alpha * 0.55f * coreOpen),
        style = Stroke(
            width = GUIDE_STROKE_DP.dp.toPx(),
            join = StrokeJoin.Round,
        ),
    )

    for (index in 0 until GATHER_DOT_COUNT) {
        val angle: Float = -90f + coreSpin + index * (360f / GATHER_DOT_COUNT)
        val spokeStart: Offset = signaturePolar(center, angle, coreRadius * 0.42f)
        val spokeEnd: Offset = signaturePolar(center, angle, coreRadius)
        drawLine(
            color = lerp(accent, glow, 0.4f).copy(alpha = alpha * 0.34f * coreOpen),
            start = spokeStart,
            end = spokeEnd,
            strokeWidth = GUIDE_STROKE_DP.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }

    drawCircle(
        color = glow.copy(alpha = alpha * 0.9f * coreOpen),
        radius = signatureLerp(1.2f, 2.2f, coreOpen).dp.toPx(),
        center = center,
    )
}

/**
 * AURORA：柔和四芒星光。
 *
 * 一团很淡的径向柔光从中心铺开，里面立起一枚竖长横短的四芒星（不是十字），
 * 叠一个更小的内星当星核；外圈两道错开的细弧在收尾时几乎散尽，
 * 最后画面停在一枚干净的星上，不会闪、不会转。
 */
internal fun DrawScope.drawSignatureBloom(
    progress: Float,
    alpha: Float,
    accent: Color,
    glow: Color,
) {
    if (alpha <= MIN_VISIBLE_ALPHA) return

    val p: Float = progress.coerceIn(0f, 1f)
    val center: Offset = Offset(size.width / 2f, size.height / 2f)
    val base: Float = signatureBaseRadius()

    val open: Float = signatureEaseOutCubic(signatureWindow(p, 0f, 0.78f))
    val settle: Float = signatureEaseOutCubic(signatureWindow(p, 0.35f, 1f))

    // 唯一一个大面积绘制：柔光圆，外缘全透明，只到 40dp，压不住底下的聊天页。
    val haloRadius: Float = base * signatureLerp(0.55f, 1.0f, open)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                glow.copy(alpha = alpha * 0.20f * signatureLerp(0.5f, 1f, open)),
                accent.copy(alpha = alpha * 0.10f * signatureLerp(0.4f, 1f, open)),
                Color.Transparent,
            ),
            center = center,
            radius = haloRadius,
        ),
        radius = haloRadius,
        center = center,
    )

    // 四芒星：竖芒更长（12.8dp → 24.8dp），横芒 0.66 倍；腰身收得很细，所以是"针尖"而不是"花窗"。
    val verticalTip: Float = base * signatureLerp(0.32f, 0.62f, open)
    val horizontalTip: Float = verticalTip * 0.66f
    val waist: Float = verticalTip * 0.16f
    val rotation: Float = signatureLerp(-12f, 0f, settle)

    val star: Path = signatureSparklePath(
        center = center,
        tipVertical = verticalTip,
        tipHorizontal = horizontalTip,
        controlRadius = waist,
        rotationDegrees = rotation,
    )
    drawPath(
        path = star,
        color = accent.copy(alpha = alpha * 0.88f),
        style = Stroke(
            width = PETAL_STROKE_DP.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    // 内星：同一套几何缩到 0.45，颜色偏 glow，只在收尾时亮起来，撑起"星核"。
    val innerVertical: Float = verticalTip * 0.45f
    val innerStar: Path = signatureSparklePath(
        center = center,
        tipVertical = innerVertical,
        tipHorizontal = innerVertical * 0.66f,
        controlRadius = innerVertical * 0.18f,
        rotationDegrees = rotation,
    )
    drawPath(
        path = innerStar,
        color = glow.copy(alpha = alpha * 0.52f * signatureLerp(0.35f, 1f, settle)),
        style = Stroke(
            width = TAIL_STROKE_DP.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )

    // 两道错开的细弧：柔光的流动感，收尾时淡到接近 0。
    val arcFade: Float = alpha * 0.16f * (1f - open * 0.7f)
    val arcRadius: Float = base * signatureLerp(0.88f, 0.80f, open)
    drawArc(
        color = accent.copy(alpha = arcFade),
        startAngle = rotation - 140f,
        sweepAngle = 96f,
        useCenter = false,
        topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
        size = Size(arcRadius * 2f, arcRadius * 2f),
        style = Stroke(width = GUIDE_STROKE_DP.dp.toPx(), cap = StrokeCap.Round),
    )
    drawArc(
        color = glow.copy(alpha = arcFade * 0.8f),
        startAngle = rotation + 34f,
        sweepAngle = 74f,
        useCenter = false,
        topLeft = Offset(center.x - arcRadius * 0.94f, center.y - arcRadius * 0.94f),
        size = Size(arcRadius * 1.88f, arcRadius * 1.88f),
        style = Stroke(width = GUIDE_STROKE_DP.dp.toPx(), cap = StrokeCap.Round),
    )

    drawCircle(
        color = glow.copy(alpha = alpha * 0.92f * signatureLerp(0.55f, 1f, settle)),
        radius = signatureLerp(1.3f, 2.0f, settle).dp.toPx(),
        center = center,
    )
}

// ---------------------------------------------------------------------------
// 纯几何 / 纯数值：不依赖 DrawScope 以外的东西，容易单测，也方便被别的画法复用。
// ---------------------------------------------------------------------------

/**
 * 图形基准半径（像素）。
 *
 * 正常情况下就是 40dp；只有画面窄到装不下 40dp（分屏、手表）时才按短边比例收窄，
 * 所以平板不会因为屏幕大而把图形放大。
 */
internal fun DrawScope.signatureBaseRadius(): Float {
    val dpRadius: Float = SIGNATURE_RADIUS.toPx()
    val narrowGuard: Float = size.minDimension * NARROW_VIEWPORT_LIMIT
    return if (dpRadius <= narrowGuard) dpRadius else narrowGuard
}

/**
 * 极坐标 → 屏幕坐标（角度按度传入，内部转弧度，Y 轴向下，0° 指向右，-90° 指向上）。
 */
internal fun signaturePolar(center: Offset, angleDegrees: Float, radius: Float): Offset {
    val radians: Double = angleDegrees.toDouble() * PI / 180.0
    return Offset(
        x = center.x + radius * cos(radians).toFloat(),
        y = center.y + radius * sin(radians).toFloat(),
    )
}

/** 缓出三次：开场几乎立刻起势，末尾平顺收住。 */
internal fun signatureEaseOutCubic(progress: Float): Float {
    val inverted: Float = 1f - progress.coerceIn(0f, 1f)
    return 1f - inverted * inverted * inverted
}

/** 把 [start]..[end] 这一段进度切成 0..1，用来给同一段动画里的不同元素错峰。 */
internal fun signatureWindow(progress: Float, start: Float, end: Float): Float {
    if (end <= start) return if (progress >= end) 1f else 0f
    return ((progress - start) / (end - start)).coerceIn(0f, 1f)
}

/** 数值线性插值，[fraction] 自动收进 0..1。 */
internal fun signatureLerp(from: Float, to: Float, fraction: Float): Float {
    val t: Float = fraction.coerceIn(0f, 1f)
    return from + (to - from) * t
}

/** 正多边形路径：从 [startAngleDegrees] 起笔，顺时针连 [sides] 个顶点后闭合。 */
internal fun signaturePolygonPath(
    center: Offset,
    radius: Float,
    sides: Int,
    startAngleDegrees: Float,
): Path {
    val path: Path = Path()
    val count: Int = sides.coerceAtLeast(3)
    val step: Float = 360f / count
    for (index in 0 until count) {
        val point: Offset = signaturePolar(center, startAngleDegrees + index * step, radius)
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    return path
}

/**
 * 四芒星路径：上下两芒长 [tipVertical]，左右两芒长 [tipHorizontal]，
 * 芒之间的腰身由 [controlRadius] 处的控制点收细（越小越尖），整体绕中心转 [rotationDegrees]。
 *
 * 用二次/三次贝塞尔而不用折线，是为了让四个芒是"柔的针尖"而不是四个三角。
 */
internal fun signatureSparklePath(
    center: Offset,
    tipVertical: Float,
    tipHorizontal: Float,
    controlRadius: Float,
    rotationDegrees: Float,
): Path {
    val path: Path = Path()
    val neck: Float = controlRadius.coerceAtLeast(0f)

    val top: Offset = signaturePolar(center, rotationDegrees - 90f, tipVertical)
    val right: Offset = signaturePolar(center, rotationDegrees, tipHorizontal)
    val bottom: Offset = signaturePolar(center, rotationDegrees + 90f, tipVertical)
    val left: Offset = signaturePolar(center, rotationDegrees + 180f, tipHorizontal)

    path.moveTo(top.x, top.y)
    appendSparkleSegment(path, center, rotationDegrees - 90f, right, neck)
    appendSparkleSegment(path, center, rotationDegrees, bottom, neck)
    appendSparkleSegment(path, center, rotationDegrees + 90f, left, neck)
    appendSparkleSegment(path, center, rotationDegrees + 180f, top, neck)
    path.close()
    return path
}

/** 四芒星的一段边：从当前点弯到 [to]，两个控制点都拉向中心，形成细腰。 */
private fun appendSparkleSegment(
    path: Path,
    center: Offset,
    angleFrom: Float,
    to: Offset,
    controlRadius: Float,
) {
    val first: Offset = signaturePolar(center, angleFrom + 32f, controlRadius)
    val second: Offset = signaturePolar(center, angleFrom + 58f, controlRadius)
    path.cubicTo(first.x, first.y, second.x, second.y, to.x, to.y)
}
