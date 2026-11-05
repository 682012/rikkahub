package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 纯 Kotlin：不引用 Android，也不引用资源 ID（文案由界面侧映射），方便直接跑 JVM 单测。

/** 宽高比换算成像素尺寸时用的长边。 */
private const val DEFAULT_LONG_SIDE = 1536

/**
 * 产出格式。只影响「向接口要什么」，不保证拿到什么：中转可能无视请求，实测请求 png 拿回过 JPEG。
 * 真实格式一律以落盘文件的字节为准。
 */
@Serializable
enum class MediaImageFormat(val key: String, val mimeType: String, val extension: String) {
    PNG("png", "image/png", "png"),
    JPEG("jpeg", "image/jpeg", "jpg"),
    WEBP("webp", "image/webp", "webp"),
    GIF("gif", "image/gif", "gif");

    companion object {
        fun fromKey(value: String?): MediaImageFormat? =
            value?.trim()?.lowercase()?.let { key -> entries.find { it.key == key } }

        fun fromMimeType(mimeType: String?): MediaImageFormat? = when (mimeType?.lowercase()) {
            "image/png" -> PNG
            "image/jpeg", "image/jpg" -> JPEG
            "image/webp" -> WEBP
            "image/gif" -> GIF
            else -> null
        }
    }
}

/**
 * 生成质量。`auto` 交给接口决定；`xhigh` / `max` 只有部分模型接受（见 [MediaImageModels.supportsExtendedQuality]）。
 */
@Serializable
enum class MediaImageQuality(val key: String) {
    AUTO("auto"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    companion object {
        fun fromKey(value: String?): MediaImageQuality? =
            value?.trim()?.lowercase()?.let { key -> entries.find { it.key == key } }
    }
}

/**
 * 复杂度档位。文档只把 `complexity` 列为 2.6 的能力，实测中转会静默忽略它，
 * 所以默认不选、在其它模型上不展示，选了也只在 2.6 上真正下发。
 */
@Serializable
enum class MediaImageComplexity(val key: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromKey(value: String?): MediaImageComplexity? =
            value?.trim()?.lowercase()?.let { key -> entries.find { it.key == key } }
    }
}

/** 导出方式：原样保存接口返回的字节，或者在本地按目标尺寸重新编码。 */
@Serializable
enum class MediaImageExportMode {
    /** 原样：保留接口返回的字节，不做任何转码。 */
    ORIGINAL,

    /** 本地重新编码到目标尺寸。 */
    REENCODE,
}

/** 本地重新编码时，原图比例和目标比例不一致的填法。 */
@Serializable
enum class MediaImageExportFit {
    /** 等比缩放到能装进目标框，空出来的地方填背景色。 */
    CONTAIN,

    /** 等比放大到铺满目标框，多出来的边缘裁掉。 */
    COVER,

    /** 直接拉伸到目标尺寸，不保持比例。 */
    STRETCH,
}

/**
 * 图像请求里只有部分模型接受的选项。它们不进公共的 [MediaCreationParams] 字段，
 * 而是在提交时通过 `extraParameters` 交给适配器，并在记录里存下实际下发的值。
 */
@Serializable
data class MediaImageOptions(
    val format: MediaImageFormat? = null,
    val quality: MediaImageQuality? = null,
    val complexity: MediaImageComplexity? = null,
)

/**
 * 按实际接口文档整理的模型差异。匹配的是模型名里的版本号片段，中转域名、模型别名加前后缀都不影响。
 *
 * 文档依据（2026-11 查证）：
 * - GPT Image 系接受 `size` / `quality` / `output_format` / `n`
 * - `xhigh` / `max` 是 2.5 引入的档位，2.6 继续支持
 * - `gpt-image-2` 起接受任意 `WIDTHxHEIGHT`：宽高都能被 16 整除、比例在 1:3 到 3:1 之间、不超过 3840x2160
 * - `complexity` 文档上只属于 2.6，且部分中转静默忽略
 * - `response_format`、`input_fidelity` 不被 `gpt-image-2` 接受（400），这里不替用户下发
 *
 * 注意：这些都是**请求**侧的约束。实际产出的格式和尺寸由文件字节决定，不由这里的判断决定。
 */
object MediaImageModels {
    private val EXTENDED_QUALITY = Regex("gpt-image-2(?:[.-][5-9]|\\d)")
    private val CUSTOM_SIZE = Regex("gpt-image-2(?![.-]?1\\b)")
    private val COMPLEXITY = Regex("gpt-image-2(?:[.-][6-9]|\\d)")

    const val MIN_SIDE = 256
    const val MAX_SIDE = 3840
    const val MAX_PIXELS = 3840 * 2160

    /** 比例只在提示词里体现，不单独下发字段。 */
    const val MIN_ASPECT = 1.0 / 3.0
    const val MAX_ASPECT = 3.0

    // 上游按 16 取整，这里按 32 的倍数算，两边都稳
    private const val SIZE_GRID = 16

    val STANDARD_SIZES = listOf("1024x1024", "1536x1024", "1024x1536")

    val SIZE_PRESETS = listOf(
        "1024x1024",
        "1536x1024",
        "1024x1536",
        "2048x1152",
        "1152x2048",
        "2048x2048",
        "3840x2160",
        "2160x3840",
    )

    private val PIXEL_SIZE = Regex("(\\d{2,5})\\s*[xX×*]\\s*(\\d{2,5})")
    private val RATIO = Regex("(\\d+(?:\\.\\d+)?)\\s*[:：/]\\s*(\\d+(?:\\.\\d+)?)")

    fun supportsExtendedQuality(modelId: String): Boolean = false // Extended quality not verified on these gateways.

    fun supportsCustomSize(modelId: String): Boolean = CUSTOM_SIZE.containsMatchIn(modelId)

    fun supportsComplexity(modelId: String): Boolean = COMPLEXITY.containsMatchIn(modelId)

    /** 只有认识 [quality] 的模型才下发，其余情况交给接口默认值。 */
    fun supportedQuality(modelId: String, quality: MediaImageQuality): Boolean =
        (quality != MediaImageQuality.XHIGH && quality != MediaImageQuality.MAX) ||
            supportsExtendedQuality(modelId)

    /** 解析 `1024x1024`，大小写和中间空白都容忍；不是像素尺寸时返回 null。 */
    fun parsePixelSize(size: String?): Pair<Int, Int>? {
        val match = PIXEL_SIZE.matchEntire(size?.trim().orEmpty()) ?: return null
        val width = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val height = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return width to height
    }

    /**
     * 尺寸能不能在 [modelId] 上用：必须是 `宽x高`。不支持任意尺寸的模型只接受接口的标准档位。
     */
    fun isUsableSize(modelId: String, size: String?): Boolean {
        val trimmed = size?.trim()
        val (width, height) = parsePixelSize(trimmed) ?: return false
        if (!supportsCustomSize(modelId)) return trimmed in STANDARD_SIZES
        if (width < MIN_SIDE || height < MIN_SIDE) return false
        if (width > MAX_SIDE || height > MAX_SIDE) return false
        if (width.toLong() * height > MAX_PIXELS) return false
        if (width % SIZE_GRID != 0 || height % SIZE_GRID != 0) return false
        val ratio = width.toDouble() / height
        return ratio >= MIN_ASPECT && ratio <= MAX_ASPECT
    }

    /** 只给比例时换算出一个可用的像素尺寸；1:1 直接用标准档位。 */
    fun sizeForAspectRatio(modelId: String, aspectRatio: String?, longSide: Int = DEFAULT_LONG_SIDE): String? {
        if (!supportsCustomSize(modelId)) return null
        val (w, h) = parseRatio(aspectRatio) ?: return null
        if (w == h) return STANDARD_SIZES.first()
        val width: Int
        val height: Int
        if (w > h) {
            width = longSide
            height = (longSide.toDouble() * h / w).roundToGrid()
        } else {
            height = longSide
            width = (longSide.toDouble() * w / h).roundToGrid()
        }
        val size = "${width}x$height"
        return size.takeIf { isUsableSize(modelId, it) }
    }

    private fun parseRatio(value: String?): Pair<Double, Double>? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        val match = RATIO.matchEntire(raw) ?: return null
        val w = match.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val h = match.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return w to h
    }

    private fun Double.roundToGrid(): Int {
        val value = Math.round(this / SIZE_GRID).toInt() * SIZE_GRID
        return value.coerceIn(MIN_SIDE, MAX_SIDE)
    }
}

/** 告诉用户「这一项目前实际会怎么下发」。文案在界面层映射，这里只给原因。 */
enum class MediaImageNote {
    /** 接口不认 `xhigh` / `max`，已退回 `high`。 */
    QUALITY_DOWNGRADED,

    /** 这个模型只接受标准尺寸，自定义的尺寸不会下发。 */
    SIZE_NOT_SENT,

    /** 只给了比例，尺寸由模型换算。 */
    SIZE_FROM_ASPECT_RATIO,

    /** 实测这个接口的「1024 方图」常常是 1254，实际尺寸以文件为准。 */
    ACTUAL_SIZE_MAY_DIFFER,

    /** `complexity` 只有 2.6 支持，当前模型不会下发。 */
    COMPLEXITY_NOT_SENT,
}

/**
 * 一次图像请求的有效参数：真正下发的尺寸、通过 `extraParameters` 下发的模型级字段，以及要告诉用户的提示。
 */
data class MediaImageDispatch(
    val resolution: String? = null,
    val extra: Map<String, JsonPrimitive> = emptyMap(),
    val notes: List<MediaImageNote> = emptyList(),
) {
    fun asJson() = buildJsonObject { extra.forEach { (key, value) -> put(key, value) } }
}

/**
 * 计算一次图像请求的有效参数。界面上的提示、实际请求和记录里保存的值都来自同一份结果，不会互相矛盾。
 *
 * 不透明格式固定带上 `output_compression`：JPEG / WebP 在 85 时体积约为 PNG 的一半，画面差异很小。
 */
fun MediaImageOptions.dispatchFor(modelId: String, params: MediaCreationParams): MediaImageDispatch {
    val notes = mutableListOf<MediaImageNote>()

    val pixelSize = MediaImageModels.parsePixelSize(params.resolution)
    val requested = pixelSize?.let { "${it.first}x${it.second}" }
        ?: params.aspectRatio?.let { MediaImageModels.sizeForAspectRatio(modelId, it) }
            ?.also { notes += MediaImageNote.SIZE_FROM_ASPECT_RATIO }
    val usable = requested?.takeIf { MediaImageModels.isUsableSize(modelId, it) }
    if (requested != null && usable == null) notes += MediaImageNote.SIZE_NOT_SENT
    if (usable != null && MediaImageModels.parsePixelSize(usable)?.let { it.first == it.second } == true) {
        notes += MediaImageNote.ACTUAL_SIZE_MAY_DIFFER
    }

    val extra = linkedMapOf<String, JsonPrimitive>()
    format?.let { extra["output_format"] = JsonPrimitive(it.key) }
    quality?.takeIf { it != MediaImageQuality.AUTO }?.let { requestedQuality ->
        val supported = MediaImageModels.supportedQuality(modelId, requestedQuality)
        if (!supported) notes += MediaImageNote.QUALITY_DOWNGRADED
        extra["quality"] = JsonPrimitive(if (supported) requestedQuality.key else MediaImageQuality.HIGH.key)
    }
    if (format == MediaImageFormat.JPEG || format == MediaImageFormat.WEBP) {
        extra["output_compression"] = JsonPrimitive(100)
    }
    if (complexity != null) {
        if (MediaImageModels.supportsComplexity(modelId)) {
            extra["complexity"] = JsonPrimitive(complexity.key)
        } else {
            notes += MediaImageNote.COMPLEXITY_NOT_SENT
        }
    }
    return MediaImageDispatch(resolution = usable, extra = extra, notes = notes)
}

/** 给新请求的默认质量：用户明确选过（含 `auto`）就按用户的来，没选过时用 `high`。 */
fun MediaImageOptions.effectiveQualityOrHigh(): MediaImageQuality = quality ?: MediaImageQuality.HIGH
