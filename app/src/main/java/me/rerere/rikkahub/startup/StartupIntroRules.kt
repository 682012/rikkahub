package me.rerere.rikkahub.startup

import java.io.File
import java.io.InputStream

/**
 * 本地启动素材的类型。
 *
 * 只认两族：静态/动图（png/jpg/webp/gif/...）和很小的视频（mp4/webm）。
 * 视频不引新引擎：走项目里已有的 media3 ExoPlayer。
 */
enum class StartupMediaKind {
    IMAGE,
    VIDEO,
    ;

    val wireName: String
        get() = when (this) {
            IMAGE -> "image"
            VIDEO -> "video"
        }

    companion object {
        fun fromWire(raw: String?): StartupMediaKind? = when (raw?.trim()?.lowercase()) {
            IMAGE.wireName -> IMAGE
            VIDEO.wireName -> VIDEO
            else -> null
        }
    }
}

/** 素材怎么放进画面：完整可见（contain）或铺满裁切（cover）。 */
enum class StartupFit {
    CONTAIN,
    COVER,
    ;

    val wireName: String
        get() = when (this) {
            CONTAIN -> "contain"
            COVER -> "cover"
        }

    companion object {
        val DEFAULT = CONTAIN

        fun fromWire(raw: String?): StartupFit = when (raw?.trim()?.lowercase()) {
            COVER.wireName -> COVER
            CONTAIN.wireName -> CONTAIN
            else -> DEFAULT
        }
    }
}

/**
 * 用户导入的一段开场素材。
 *
 * [path] 一定是应用私有目录里的绝对路径（见 [StartupIntroRules.DIRECTORY]），
 * 不保存 `content://`：相册 URI 的临时授权在下次冷启动时可能已经失效，
 * 而开场是进程里最早跑起来的东西之一，不能依赖一次授权还在。
 */
data class StartupCustomIntro(
    val path: String,
    val kind: StartupMediaKind,
    /** 单次播放时长（毫秒），已被 [StartupIntroRules.clampDuration] 收进范围。 */
    val durationMs: Int = StartupIntroRules.customDurationDefault(kind),
    /** 100 表示原尺寸；和适配方式一起决定画面大小。 */
    val scalePercent: Int = StartupIntroRules.DEFAULT_SCALE,
    val fit: StartupFit = StartupFit.DEFAULT,
)

/**
 * 冷启动画面最终要画什么。
 *
 * [style] 一定是可以直接绘制的内建风格（不是 OFF，也不是 CUSTOM）：
 * 自定义素材读不出来/解码失败时绘制层直接拿它兜底，
 * 所以任何失败路径都有东西可画或什么都不画，不会停在"有覆盖层却没内容"的空白上。
 */
data class StartupIntroScene(
    val style: StartupMotion,
    val custom: StartupCustomIntro?,
    val durationMs: Int,
) {
    val isEmpty: Boolean
        get() = style == StartupMotion.OFF && custom == null
}

/**
 * 素材的准入规则：全部纯函数，不碰 Android API，JVM 单测直接跑。
 *
 * 规则只做两件事：把不认识的东西挡在门外，把认得的数字收进安全范围。
 * 真正写盘/解码的副作用在 `StartupIntroMaterialStore` 里。
 */
object StartupIntroRules {
    /** 私有目录名，相对 `context.filesDir`。 */
    const val DIRECTORY = "startup_intro"

    /** 一张图/一张动图的上限：动图（GIF/WebP）比静态图大，给到 12MB。 */
    const val MAX_IMAGE_BYTES = 12L * 1024 * 1024

    /** 视频上限刻意更小：开场只播一两秒，没必要容忍更大的文件。 */
    const val MAX_VIDEO_BYTES = 8L * 1024 * 1024

    /** 解码像素上限：只按这个长边解码，几 MB 的图不会解成几十 MB 的位图。 */
    const val MAX_DECODE_PX = 1536

    /** 内建风格的时长，保持 a75335a 的 280ms。 */
    const val BUILTIN_DURATION_MS = 280

    /** 自定义素材默认稍长一点，但仍在"不挡内容"的量级。 */
    const val CUSTOM_DURATION_DEFAULT_MS = 600

    const val MIN_DURATION_MS = 200
    const val MAX_DURATION_MS = 1500

    const val DEFAULT_SCALE = 100
    const val MIN_SCALE = 60
    const val MAX_SCALE = 140

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif")
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv")

    /** 从文件名猜类型；猜不出返回 null（= 不接受）。 */
    fun kindOfFileName(fileName: String?): StartupMediaKind? {
        val name = fileName?.trim().orEmpty()
        if (name.isEmpty()) return null
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext.isEmpty()) return null
        return when (ext) {
            in IMAGE_EXTENSIONS -> StartupMediaKind.IMAGE
            in VIDEO_EXTENSIONS -> StartupMediaKind.VIDEO
            else -> null
        }
    }

    /**
     * MIME 和文件名一起判断：系统选择器有时只给 `application/octet-stream`，
     * 有时只给名字，两边都认一遍，都不认识才算不接受。
     */
    fun kindOf(mimeType: String?, fileName: String?): StartupMediaKind? {
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        return when {
            mime.startsWith("image/") -> StartupMediaKind.IMAGE
            mime.startsWith("video/") -> StartupMediaKind.VIDEO
            else -> kindOfFileName(fileName)
        }
    }

    fun extensionFor(kind: StartupMediaKind, fileName: String?, mimeType: String?): String {
        val fromName = fileName?.trim().orEmpty()
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all { ch -> ch.isLetterOrDigit() } }
        if (fromName != null) return fromName
        val fromMime = mimeType?.trim()?.lowercase().orEmpty().substringAfterLast('/', "")
        if (fromMime.isNotEmpty() && fromMime.length <= 5 && fromMime.all { ch -> ch.isLetterOrDigit() }) {
            return fromMime
        }
        return when (kind) {
            StartupMediaKind.IMAGE -> "png"
            StartupMediaKind.VIDEO -> "mp4"
        }
    }

    fun maxBytes(kind: StartupMediaKind): Long = when (kind) {
        StartupMediaKind.IMAGE -> MAX_IMAGE_BYTES
        StartupMediaKind.VIDEO -> MAX_VIDEO_BYTES
    }

    fun isWithinSizeLimit(kind: StartupMediaKind, bytes: Long): Boolean =
        bytes > 0L && bytes <= maxBytes(kind)

    /** 元素探测拿到的大小已知且超限时就别拷了；未知（-1）交给拷贝时的硬上限兜住。 */
    fun declaredSizeTooLarge(kind: StartupMediaKind, declaredBytes: Long): Boolean =
        declaredBytes > maxBytes(kind)

    fun customDurationDefault(kind: StartupMediaKind): Int = when (kind) {
        // 视频太短看不出内容，给它一个更像"播放"的默认值
        StartupMediaKind.VIDEO -> 900
        StartupMediaKind.IMAGE -> CUSTOM_DURATION_DEFAULT_MS
    }

    fun clampDuration(millis: Int, kind: StartupMediaKind): Int {
        if (millis <= 0) return customDurationDefault(kind)
        return millis.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    fun clampScale(percent: Int): Int {
        if (percent <= 0) return DEFAULT_SCALE
        return percent.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /** 落地文件名只用固定的 stem + 白名单扩展名，不把用户给的名字带进文件系统。 */
    fun fileNameFor(kind: StartupMediaKind, extension: String): String =
        "intro_${kind.wireName}.${extension.lowercase()}"

    /**
     * 边读边写，超过 [limit] 立刻返回 -1（调用方负责删掉半成品）。
     * 返回实际写入的字节数；0 表示源是空的。
     *
     * 放在这里而不是 Store 里，是为了能用 JVM 单测直接压这个上限：
     * "复制到一半才发现超了" 是这段代码最容易写错的地方。
     */
    fun copyWithLimit(input: InputStream, target: File, limit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        target.outputStream().use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > limit) return -1L
                output.write(buffer, 0, read)
            }
            output.flush()
        }
        return total
    }

    /** 解码长边：屏幕长边和 [MAX_DECODE_PX] 取小，再乘缩放；永远是个有限的正数。 */
    fun decodeBoundPx(screenLongSidePx: Int, scalePercent: Int): Int {
        val side = if (screenLongSidePx <= 0) MAX_DECODE_PX else screenLongSidePx
        val scaled = (side.coerceAtMost(MAX_DECODE_PX) * (clampScale(scalePercent) / 100f)).toInt()
        return scaled.coerceIn(64, MAX_DECODE_PX)
    }
}
