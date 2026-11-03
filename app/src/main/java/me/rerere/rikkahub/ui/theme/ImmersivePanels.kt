package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import me.rerere.rikkahub.data.datastore.AppearanceSettings
import kotlin.math.pow

/**
 * 沉浸聊天的面板预算（r10）。
 *
 * r9 的做法是“每块面板各自加到读得清为止”：壁纸越花，消息外框、正文气泡、顶部栏、输入区就越接近
 * 一整块不透明卡片，壁纸最后只在卡片缝里露一点。沉浸模式换个顺序：
 *
 * 1. 壁纸整层压暗 [ImmersiveContrastPlan.scrim]（全局、均匀、没有边界，看起来还是壁纸）；
 * 2. 正文只铺一层很薄的文字底 [ImmersiveContrastPlan.surface]；
 * 3. 两者加起来仍要满足正文对比度 [ImmersivePanels.MIN_CONTRAST]，谁都不许单独把壁纸盖死。
 *
 * 这里全是纯函数：不读 Compose 状态、不碰 Android，方便直接跑 JVM 单元测试。
 */

/** 采样到的壁纸明暗：主色调 + 暗/亮两端（10%/90% 分位）的相对亮度。 */
data class WallpaperSample(
    val tone: Color?,
    val darkLuminance: Float,
    val lightLuminance: Float,
) {
    /** 两端都要算：最坏的那端才算数，不能只看平均值。 */
    fun luminances(): List<Float> = listOf(darkLuminance, lightLuminance)
}

/**
 * 一份沉浸预算：[scrim] 是整层壁纸压暗，[surface] 是正文薄底。
 *
 * [backdrops] 是参与对比度检查的壁纸明暗两端（已经换算成灰度 Color），
 * [scrimColor] 是压暗用的颜色（主题背景色，和浮层保持同一个色系）。
 */
data class ImmersiveContrastPlan(
    val scrim: Float,
    val surface: Float,
    val scrimColor: Color,
    val backdrops: List<Color>,
) {
    /**
     * 顶部栏 / 输入区这类“薄玻璃”比正文底再厚一点点：它们上面要压图标和小字，
     * 但依旧远薄于 r9 的整块卡片。
     */
    val glass: Float get() = (surface + GLASS_EXTRA).coerceAtMost(MAX_GLASS)

    companion object {
        const val GLASS_EXTRA = 0.14f
        const val MAX_GLASS = 0.72f
    }
}

object ImmersivePanels {
    /** 正文文字底最薄一档：再薄就只是壁纸上一层雾，字会飘。 */
    const val MIN_TEXT_BASE = 0.10f

    /** 正文文字底最厚一档：到这儿还算“薄底”，再厚就是 r9 的卡片了。 */
    const val MAX_TEXT_BASE = 0.46f

    /**
     * 任何情况下正文底都不超过这个值——超过就不再是沉浸模式。
     *
     * 0.62 是“够读但不变成卡片”的折中：浅色主题压一张纯黑照片时，正文底需要到 0.59 左右
     * 才能把深色正文从黑底上分辨出来，而 0.55 差一点点；再高就跟 r9 的不透明气泡没区别了。
     */
    const val MAX_SURFACE = 0.62f

    /** 整层压暗的上限：极端壁纸（纯白/纯黑）也要能读到 4.5:1。 */
    const val MAX_SCRIM = 0.85f

    /** 正文对比度底线（WCAG AA 常规字号）。 */
    const val MIN_CONTRAST = 4.5f

    internal const val SURFACE_STEPS = 9
    internal const val SCRIM_STEPS = 34
}

/**
 * 复用设置里已有的“面板强度”滑块：r9 的 0.6..1 映射成沉浸模式的 0.10..0.46。
 *
 * 不新增滑块就是这个意思——同一份设置，在沉浸模式下从“面板多不透明”变成“文字底多厚”。
 */
fun immersiveTextBase(requested: Float): Float {
    val value = sanitize(requested, AppearanceSettings.MIN_SURFACE_OPACITY)
        .coerceIn(AppearanceSettings.MIN_SURFACE_OPACITY, 1f)
    val span = (1f - AppearanceSettings.MIN_SURFACE_OPACITY).coerceAtLeast(1e-4f)
    val progress = (value - AppearanceSettings.MIN_SURFACE_OPACITY) / span
    return ImmersivePanels.MIN_TEXT_BASE +
        progress * (ImmersivePanels.MAX_TEXT_BASE - ImmersivePanels.MIN_TEXT_BASE)
}

/**
 * 按壁纸明暗两端算出一份沉浸预算。分两遍，顺序很关键：
 *
 * 1. **先不动壁纸**：只把文字底从用户选的那一档加厚，最多到 [ImmersivePanels.MAX_SURFACE]。
 *    浅色主题 + 深色照片、深色主题 + 暗色照片这些常见组合都停在第一遍——
 *    壁纸保持原样，用户看到的还是自己选的那张图。
 * 2. 文字底到上限仍不够（典型：深色主题 + 亮壁纸，浅字压在亮照片上），才整层压暗；
 *    压暗是均匀铺满整屏的，没有卡片边界，而且这时文字底退回用户选的那一档，不再额外加厚。
 *
 * 压暗永远不会“为了读数”把壁纸往反方向推：颜色的选择交给调用方（主题背景色），
 * 这里只保证不欠对比度，也不无谓地改变壁纸观感。
 *
 * [panelBases] 是这一刻会被铺成面板的基色（气泡、标题栏…）。预算要照顾到最不利的那一块，
 * 否则整屏够了、用户气泡却读不清。
 *
 * [beneath] 传空表示“没采到壁纸明暗”，此时按最坏情况（纯黑 + 纯白）保护，宁厚不虚。
 */
fun immersiveContrastPlan(
    background: Color,
    foreground: Color,
    beneath: List<Color>,
    requestedSurface: Float,
    requestedScrim: Float,
    panelBases: List<Color> = emptyList(),
    minContrast: Float = ImmersivePanels.MIN_CONTRAST,
): ImmersiveContrastPlan {
    val backdrops = beneath.ifEmpty { listOf(grayFromLuminance(0f), grayFromLuminance(1f)) }
    val bases = listOf(background) + panelBases
    val surfaceStart = sanitize(requestedSurface, ImmersivePanels.MIN_TEXT_BASE)
        .coerceIn(ImmersivePanels.MIN_TEXT_BASE, ImmersivePanels.MAX_SURFACE)
    val scrimFloor = sanitize(requestedScrim, 0f).coerceIn(0f, ImmersivePanels.MAX_SCRIM)

    fun readableAt(scrim: Float, surface: Float): Boolean = bases.all { base ->
        backdrops.all { beneathColor ->
            val backdrop = background.copy(alpha = scrim).compositeOver(beneathColor)
            val painted = base.copy(alpha = surface).compositeOver(backdrop)
            surfaceContrast(painted, foreground) >= minContrast
        }
    }

    // 第一遍：壁纸不动，只加厚文字底。
    for (step in 0..ImmersivePanels.SURFACE_STEPS) {
        val surface = lerpBy(surfaceStart, ImmersivePanels.MAX_SURFACE, step, ImmersivePanels.SURFACE_STEPS)
        if (readableAt(scrimFloor, surface)) return ImmersiveContrastPlan(scrimFloor, surface, background, backdrops)
    }
    // 第二遍：文字底已到上限，才整层压暗；这时文字底保持用户选的那一档。
    for (step in 1..ImmersivePanels.SCRIM_STEPS) {
        val scrim = lerpBy(scrimFloor, ImmersivePanels.MAX_SCRIM, step, ImmersivePanels.SCRIM_STEPS)
        if (readableAt(scrim, surfaceStart)) return ImmersiveContrastPlan(scrim, surfaceStart, background, backdrops)
    }
    return ImmersiveContrastPlan(
        scrim = ImmersivePanels.MAX_SCRIM,
        surface = ImmersivePanels.MAX_SURFACE,
        scrimColor = background,
        backdrops = backdrops,
    )
}

/**
 * 某一块具体面板（用户气泡、助手气泡、标题栏…）在这份预算下要多厚。
 *
 * 每块面板的基色亮度不一样，所以不能直接套 plan.surface；这里按同一层压暗重新算一次，
 * 保证“薄”与“读得清”同时成立。
 */
fun immersiveSurfaceAlpha(
    base: Color,
    foreground: Color,
    plan: ImmersiveContrastPlan,
    requested: Float,
    maxSurface: Float = ImmersivePanels.MAX_SURFACE,
    minContrast: Float = ImmersivePanels.MIN_CONTRAST,
): Float {
    val ceiling = if (maxSurface.isFinite()) maxSurface.coerceIn(0f, 1f) else ImmersivePanels.MAX_SURFACE
    val start = sanitize(requested, ImmersivePanels.MIN_TEXT_BASE).coerceIn(0f, ceiling)
    for (step in 0..ImmersivePanels.SURFACE_STEPS) {
        val surface = lerpBy(start, ceiling, step, ImmersivePanels.SURFACE_STEPS)
        if (immersivePanelReadable(base, foreground, plan, surface, minContrast)) return surface
    }
    return ceiling
}

/** 这块面板以 [surface] 的厚度压在压暗后的壁纸上，正文还读不读得清。 */
fun immersivePanelReadable(
    base: Color,
    foreground: Color,
    plan: ImmersiveContrastPlan,
    surface: Float,
    minContrast: Float = ImmersivePanels.MIN_CONTRAST,
): Boolean {
    val scrim = plan.scrim.coerceIn(0f, 1f)
    val alpha = surface.coerceIn(0f, 1f)
    return plan.backdrops.all { beneath ->
        val backdrop = plan.scrimColor.copy(alpha = scrim).compositeOver(beneath)
        val painted = base.copy(alpha = alpha).compositeOver(backdrop)
        surfaceContrast(painted, foreground) >= minContrast
    }
}

/**
 * 壁纸遮罩的渐变停靠点。
 *
 * [immersive] = false 时和 r9 完全一致（上下两端 0.96，避免状态栏/导航栏被白图吃掉）；
 * [immersive] = true 时上下只留一条很窄的加强带，其余整片用 [scrim]，
 * 这样顶部栏和输入区能透出壁纸，又不会在边缘出现“大黑块”。
 */
fun wallpaperProtectionStops(
    scrim: Float,
    immersive: Boolean,
    base: Color,
): List<Pair<Float, Color>> {
    val value = sanitize(scrim, 0.20f).coerceIn(0f, 1f)
    if (!immersive) {
        return listOf(
            0f to base.copy(alpha = 0.96f),
            0.10f to base.copy(alpha = maxOf(value, 0.35f)),
            0.32f to base.copy(alpha = value),
            0.78f to base.copy(alpha = value),
            1f to base.copy(alpha = 0.96f),
        )
    }
    val top = (value + 0.20f).coerceAtMost(0.92f)
    val bottom = (value + 0.22f).coerceAtMost(0.92f)
    return listOf(
        0f to base.copy(alpha = top),
        0.06f to base.copy(alpha = value),
        0.30f to base.copy(alpha = value),
        0.80f to base.copy(alpha = value),
        1f to base.copy(alpha = bottom),
    )
}

/**
 * 输入区底部渐变的强度。
 *
 * r9 有壁纸时按风格强度 ×0.6 铺一层背景色，在沉浸模式里那是一条压在壁纸上的深色带。
 * 沉浸模式只留一层薄雾（[IMMERSIVE_INPUT_SCRIM]），消息从后面滚过去依旧看得见。
 */
fun immersiveInputScrim(styleStrength: Float, hasWallpaper: Boolean, immersive: Boolean): Float {
    val value = sanitize(styleStrength, 1f).coerceIn(0f, 1f)
    return when {
        immersive -> value * IMMERSIVE_INPUT_SCRIM
        hasWallpaper -> value * 0.6f
        else -> value
    }
}

/** 沉浸模式下输入区底部只用薄薄一层雾。 */
const val IMMERSIVE_INPUT_SCRIM = 0.28f

/**
 * 从一张很小的壁纸采样里量出主色调与明暗两端。
 *
 * 用 10%/90% 分位而不是 min/max：一个高光点不该让整屏壁纸被压到最暗。
 * 全透明图（或采不到有效像素）返回 null，调用方按“没采到”处理。
 */
fun wallpaperSample(pixels: IntArray): WallpaperSample? {
    if (pixels.isEmpty()) return null
    val luminances = ArrayList<Float>(pixels.size)
    for (pixel in pixels) {
        val alpha = ((pixel ushr 24) and 255) / 255f
        if (alpha < 0.2f) continue
        val red = ((pixel ushr 16) and 255) / 255f
        val green = ((pixel ushr 8) and 255) / 255f
        val blue = (pixel and 255) / 255f
        luminances += Color(red, green, blue, 1f).luminance()
    }
    if (luminances.isEmpty()) return null
    luminances.sort()
    fun percentile(fraction: Float): Float {
        val index = ((luminances.size - 1) * fraction).toInt().coerceIn(0, luminances.lastIndex)
        return luminances[index]
    }
    return WallpaperSample(
        tone = wallpaperTone(pixels),
        darkLuminance = percentile(0.10f),
        lightLuminance = percentile(0.90f),
    )
}

/** 相对亮度反解成 sRGB 灰度：这样就能直接拿 Color 参与 compositeOver / 对比度计算。 */
internal fun grayFromLuminance(luminance: Float): Color {
    val y = sanitize(luminance, 0f).coerceIn(0f, 1f)
    val channel = if (y <= 0.0031308f) {
        y * 12.92f
    } else {
        (1.055f * y.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
    }
    val value = channel.coerceIn(0f, 1f)
    return Color(value, value, value, 1f)
}

/** 非有限值退回默认值，其余原样返回。 */
private fun sanitize(value: Float, default: Float): Float = if (value.isFinite()) value else default

private fun lerpBy(start: Float, end: Float, step: Int, steps: Int): Float =
    start + (end - start) * (step.toFloat() / steps)
