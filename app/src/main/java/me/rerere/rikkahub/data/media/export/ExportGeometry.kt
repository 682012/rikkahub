package me.rerere.rikkahub.data.media.export

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * 导出用到的纯几何与时间轴计算：不依赖 Android，可以在 JVM 单元测试里直接验证。
 * 渲染层（android.graphics.Canvas）只按这里算出来的矩形画，保证"不拉伸"这条规则只有一个实现。
 */

/** 导出图片的目标格式。 */
enum class ExportImageFormat(val mimeType: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
}

/** 画面如何适配目标尺寸；两种情况都保持原比例，绝不拉伸。 */
enum class ExportFit {
    /** 等比缩放到目标框内，短边贴边，四周留白。 */
    FIT,

    /** 等比放大到铺满目标框，超出部分居中裁掉。 */
    CROP,
}

/** 目标尺寸，限制在常见分享平台能处理的范围。 */
data class ExportSize(val width: Int, val height: Int) {
    init {
        require(width in MIN_SIDE..MAX_SIDE) { "width out of range" }
        require(height in MIN_SIDE..MAX_SIDE) { "height out of range" }
        require(width.toLong() * height <= MAX_PIXELS) { "too many pixels" }
    }

    val pixels: Long get() = width.toLong() * height

    companion object {
        const val MIN_SIDE = 16
        const val MAX_SIDE = 4096
        const val MAX_PIXELS = 4096L * 4096L

        /** 按原图比例把长边缩到 [longSide]。 */
        fun scaledTo(sourceWidth: Int, sourceHeight: Int, longSide: Int): ExportSize {
            require(sourceWidth > 0 && sourceHeight > 0) { "source size must be positive" }
            val longest = max(sourceWidth, sourceHeight).toDouble()
            val scale = longSide / longest
            val w = (sourceWidth * scale).roundToInt().coerceIn(MIN_SIDE, MAX_SIDE)
            val h = (sourceHeight * scale).roundToInt().coerceIn(MIN_SIDE, MAX_SIDE)
            return ExportSize(w, h)
        }
    }
}

/**
 * 把源图画进目标画布时，源图上取用的区域（src*）与目标画布上画出的区域（dst*）。
 *
 * FIT 时源矩形是整张图、目标矩形可能小于画布（留白）；CROP 时源矩形是居中或按焦点取的一块、目标矩形正好
 * 是画布。两种情况下两个矩形宽高比一致，因此绘制时按矩形铺满即可，不存在拉伸。
 */
data class FitRect(
    val srcLeft: Float,
    val srcTop: Float,
    val srcWidth: Float,
    val srcHeight: Float,
    val dstLeft: Float,
    val dstTop: Float,
    val dstWidth: Float,
    val dstHeight: Float,
)

object ExportGeometry {
    /**
     * 计算把源图画进目标的矩形。[focusX]/[focusY] 是裁切焦点（0..1，默认居中），只在 [ExportFit.CROP] 时生效。
     */
    fun compute(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        fit: ExportFit,
        focusX: Float = 0.5f,
        focusY: Float = 0.5f,
    ): FitRect {
        require(sourceWidth > 0 && sourceHeight > 0) { "source size must be positive" }
        require(targetWidth > 0 && targetHeight > 0) { "target size must be positive" }
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val targetAspect = targetWidth.toFloat() / targetHeight
        return when (fit) {
            ExportFit.FIT -> {
                val scale = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
                val drawWidth = sourceWidth * scale
                val drawHeight = sourceHeight * scale
                FitRect(
                    srcLeft = 0f,
                    srcTop = 0f,
                    srcWidth = sourceWidth.toFloat(),
                    srcHeight = sourceHeight.toFloat(),
                    dstLeft = (targetWidth - drawWidth) / 2f,
                    dstTop = (targetHeight - drawHeight) / 2f,
                    dstWidth = drawWidth,
                    dstHeight = drawHeight,
                )
            }

            ExportFit.CROP -> {
                val cropWidth: Float
                val cropHeight: Float
                if (sourceAspect >= targetAspect) {
                    // 源图更宽：高度取满，按目标比例裁掉左右
                    cropHeight = sourceHeight.toFloat()
                    cropWidth = cropHeight * targetAspect
                } else {
                    cropWidth = sourceWidth.toFloat()
                    cropHeight = cropWidth / targetAspect
                }
                FitRect(
                    srcLeft = (sourceWidth - cropWidth) * focusX.coerceIn(0f, 1f),
                    srcTop = (sourceHeight - cropHeight) * focusY.coerceIn(0f, 1f),
                    srcWidth = cropWidth,
                    srcHeight = cropHeight,
                    dstLeft = 0f,
                    dstTop = 0f,
                    dstWidth = targetWidth.toFloat(),
                    dstHeight = targetHeight.toFloat(),
                )
            }
        }
    }

    /** FIT 模式下两侧留白的宽度：返回（水平每边, 垂直每边）。 */
    fun letterboxInsets(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Pair<Float, Float> {
        val rect = compute(sourceWidth, sourceHeight, targetWidth, targetHeight, ExportFit.FIT)
        return ((targetWidth - rect.dstWidth) / 2f) to ((targetHeight - rect.dstHeight) / 2f)
    }

    /** 目标尺寸与原图比例是否一致（相对误差在 [tolerance] 以内），用于界面提示"比例不同将留白/裁切"。 */
    fun sameAspect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        tolerance: Float = 0.01f,
    ): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return false
        val a = sourceWidth.toFloat() / sourceHeight
        val b = targetWidth.toFloat() / targetHeight
        return abs(a - b) <= tolerance * max(a, b)
    }
}

/** 本地图像动效的样式。全部只是对同一张图的推近/拉远/平移/淡入，不生成新内容。 */
enum class MotionStyle {
    ZOOM_IN,
    ZOOM_OUT,
    PAN_RIGHT,
    PAN_UP,
    FADE_IN,
    ZOOM_FADE,
}

/** 动效导出的容器格式。 */
enum class MotionOutput(val mimeType: String, val extension: String) {
    GIF("image/gif", "gif"),
    MP4("video/mp4", "mp4"),
}

/** 动效的时间参数：GIF 15fps（体积可控）、MP4 30fps。 */
object MotionRates {
    const val GIF_FPS = 15
    const val MP4_FPS = 30
    const val MIN_DURATION = 2
    const val MAX_DURATION = 5
    const val DEFAULT_DURATION = 3
}

/**
 * 一次动效导出的完整参数。[durationSeconds] 与 [fps] 决定帧数；GIF 每帧延时直接由它推出。
 */
data class MotionSpec(
    val style: MotionStyle,
    val size: ExportSize,
    val durationSeconds: Int = MotionRates.DEFAULT_DURATION,
    val output: MotionOutput = MotionOutput.GIF,
    /** 推近/拉远最多放大的倍数，1.10 表示最多放大 10%。 */
    val zoom: Float = 1.10f,
) {
    init {
        require(durationSeconds in MotionRates.MIN_DURATION..MotionRates.MAX_DURATION) { "duration out of range" }
        require(zoom in 1f..1.5f) { "zoom out of range" }
    }

    val fps: Int get() = if (output == MotionOutput.GIF) MotionRates.GIF_FPS else MotionRates.MP4_FPS

    val frameCount: Int get() = durationSeconds * fps

    /** 单帧持续时长（毫秒）。 */
    val frameMillis: Int get() = 1000 / fps

    /** 第 [index] 帧归一化时间（0..1）。 */
    fun frameTime(index: Int): Float = (index.toFloat() / frameCount).coerceIn(0f, 1f)
}

/**
 * 某一帧的画面变换。
 *
 * [scale] 是铺满画布之后额外放大的倍数（始终 ≥ 1，画面始终覆盖整张画布，不会露出背景）；
 * [offsetX]/[offsetY] 是相对居中的平移，单位是画布尺寸的比例（0 表示居中）；
 * [alpha] 是不透明度，淡入用。
 */
data class MotionFrame(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val alpha: Float,
)

object MotionTimeline {
    /** 计算时间 [progress]（0..1）上的变换；曲线用 smoothstep 缓入缓出，首尾速度为 0。 */
    fun frameAt(style: MotionStyle, progress: Float, zoom: Float): MotionFrame {
        val t = progress.coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        val span = (zoom - 1f).coerceAtLeast(0f)
        return when (style) {
            MotionStyle.ZOOM_IN ->
                MotionFrame(scale = 1f + span * eased, offsetX = 0f, offsetY = 0f, alpha = 1f)

            MotionStyle.ZOOM_OUT ->
                MotionFrame(scale = 1f + span * (1f - eased), offsetX = 0f, offsetY = 0f, alpha = 1f)

            // 平移时至少多放大 4%，移动时边缘不会露白
            MotionStyle.PAN_RIGHT -> MotionFrame(
                scale = 1f + max(span, 0.04f),
                offsetX = -0.04f + 0.08f * eased,
                offsetY = 0f,
                alpha = 1f,
            )

            MotionStyle.PAN_UP -> MotionFrame(
                scale = 1f + max(span, 0.04f),
                offsetX = 0f,
                offsetY = 0.04f - 0.08f * eased,
                alpha = 1f,
            )

            MotionStyle.FADE_IN -> MotionFrame(
                scale = 1f,
                offsetX = 0f,
                offsetY = 0f,
                alpha = (t / 0.35f).coerceIn(0f, 1f),
            )

            MotionStyle.ZOOM_FADE -> MotionFrame(
                scale = 1f + span * eased,
                offsetX = 0f,
                offsetY = 0f,
                alpha = (t / 0.3f).coerceIn(0f, 1f),
            )
        }
    }
}

/** 导出进度回调；在 IO 线程调用。 */
fun interface ExportProgressListener {
    fun onProgress(fraction: Float, stage: ExportStage)
}

enum class ExportStage {
    PREPARING,
    RENDERING,
    ENCODING,
    FINISHED,
}
