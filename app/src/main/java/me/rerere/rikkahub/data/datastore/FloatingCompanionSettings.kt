package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI

/**
 * 悬浮窗收起时的角色。
 *
 * [LOGO] 是原来的应用图标气泡（默认值，行为保持不变）；[CAT]/[DRAGON] 是代码里画的原创角色；
 * [CUSTOM] 用用户自己拷进私有目录的图片（PNG/GIF/Animated WebP 由 Coil 解码）。
 */
@Serializable
enum class FloatingCompanionPreset {
    @SerialName("logo")
    LOGO,

    @SerialName("cat")
    CAT,

    @SerialName("dragon")
    DRAGON,

    @SerialName("custom")
    CUSTOM,

    @SerialName("whale_maid")
    WHALE_MAID,

    @SerialName("dragon_girl")
    DRAGON_GIRL,

    @SerialName("sun_reader")
    SUN_READER,
}

/**
 * 悬浮窗角色设置。
 *
 * 只描述"画什么、叫什么、说什么"，怎么画在 `ui/floating/FloatingCompanion.kt`。
 * 和 [AppearanceSettings] 一样，字段可能被手改 JSON 写坏（越界大小、空台词、乱七八糟的路径），
 * 读出来先过 [normalized] 再用。
 *
 * 自定义图片只允许应用私有目录 `filesDir/floating_companions/` 里的文件：
 * 悬浮窗会从后台出现，不能因为一条设置就去访问网络、加载大小未知的远程图片。
 */
@Serializable
data class FloatingCompanionSettings(
    val preset: FloatingCompanionPreset = FloatingCompanionPreset.WHALE_MAID,
    /** 待机图片，`file://` 指向私有目录 [FloatingCompanionLimits.DIRECTORY]；null 表示没选。 */
    val customImagePath: String? = null,
    /** 点一下时临时换的图片，可选；null 时沿用待机图片。 */
    val customTapImagePath: String? = null,
    /** 角色名字，空表示用预设默认名（字符串资源）。 */
    val name: String = "",
    /** 点击台词，空表示用预设默认台词。 */
    val tapLines: List<String> = emptyList(),
    val sizeDp: Int = FloatingCompanionLimits.SIZE_DEFAULT_DP,
    /** 关掉后角色不眨眼、不摆尾、没有粒子；状态徽标照常。 */
    val animationEnabled: Boolean = true,
    /** Edge mode keeps only a narrow handle and tiny portrait visible over other apps. */
    val minimized: Boolean = true,
    /** The preview inside the quick-reply panel is opt-in, not another permanent card. */
    val panelPreviewExpanded: Boolean = false,
) {
    /** 真正要画的：选了自定义但没图时退回应用 logo，不留空白。 */
    val effectivePreset: FloatingCompanionPreset
        get() = when {
            preset == FloatingCompanionPreset.CUSTOM && customImagePath.isNullOrBlank() -> FloatingCompanionPreset.WHALE_MAID
            preset == FloatingCompanionPreset.CAT -> FloatingCompanionPreset.WHALE_MAID
            preset == FloatingCompanionPreset.DRAGON -> FloatingCompanionPreset.DRAGON_GIRL
            else -> preset
        }

    /** 大小限制在 56..112dp：太小点不中，太大挡聊天。 */
    val safeSizeDp: Int
        get() = sizeDp.coerceIn(FloatingCompanionLimits.SIZE_MIN_DP, FloatingCompanionLimits.SIZE_MAX_DP)

    /** 名字空着时用 [fallback]（界面上是预设默认名的字符串资源）。 */
    fun displayName(fallback: String): String = name.trim().ifBlank { fallback }

    /** 点击台词：自己填了用自己的，没填用 [fallback]。 */
    fun tapLinesOr(fallback: List<String>): List<String> =
        tapLines.ifEmpty { fallback }.filter { it.isNotBlank() }

    fun normalized(): FloatingCompanionSettings = copy(
        customImagePath = normalizeOwnedPath(customImagePath),
        customTapImagePath = normalizeOwnedPath(customTapImagePath),
        name = name.trim().take(FloatingCompanionLimits.MAX_NAME_LENGTH),
        tapLines = tapLines
            .map { it.trim().take(FloatingCompanionLimits.MAX_TAP_LINE_LENGTH) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(FloatingCompanionLimits.MAX_TAP_LINES),
        sizeDp = safeSizeDp,
        animationEnabled = animationEnabled,
    )
}

/** 角色的硬限制：界面、存储、测试共用同一份数字。 */
object FloatingCompanionLimits {
    const val SIZE_MIN_DP = 56
    const val SIZE_MAX_DP = 112
    const val SIZE_DEFAULT_DP = 68

    /** 触摸区域下限（Material 最小可点尺寸）。 */
    const val MIN_TOUCH_TARGET_DP = 48

    /** 点一下先给反馈，这么久之后再打开原来的审批/进度面板。 */
    const val TAP_DELAY_MILLIS = 400L

    /** 一次点击反馈（抬手/粒子/台词）的时长。 */
    const val TAP_FEEDBACK_MILLIS = 700L

    /** 私有目录里单张图的上限，和相册复制时的上限一致。 */
    const val MAX_IMAGE_BYTES = 16L * 1024 * 1024

    /** 私有目录名，相对 `context.filesDir`。 */
    const val DIRECTORY = "floating_companions"

    const val MAX_NAME_LENGTH = 24
    const val MAX_TAP_LINES = 8
    const val MAX_TAP_LINE_LENGTH = 80

    /** 手指位移超过 touchSlop 的这么多倍就当成拖窗口，不触发点击。 */
    const val DRAG_SLOP_FACTOR = 1.0f
}

/**
 * 私有目录路径的解析与校验。
 *
 * 只用 `java.io` / `java.net`，不碰 Android API，单元测试能直接跑。
 * 需要 Context 的动作（相册拷图、删旧图）在 `ui/floating/FloatingCompanion.kt` 的
 * `FloatingCompanionFiles` 里，两边共用这里的白名单判断。
 */
object FloatingCompanionPaths {
    /** 只看是不是私有目录下的 file 地址，不要求文件存在。 */
    fun isOwnedPath(path: String?, ownedDirectory: File): Boolean =
        resolve(path, ownedDirectory, requireExists = false) != null

    /** 解析成私有目录里的真实文件；不是本目录、或（默认要求）文件不存在时返回 null。 */
    fun resolve(path: String?, ownedDirectory: File, requireExists: Boolean = true): File? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val file = runCatching {
            when {
                raw.startsWith("file://") -> URI(raw).path?.let(::File)
                raw.startsWith("file:") -> File(raw.removePrefix("file:"))
                raw.startsWith("/") -> File(raw)
                // http/https/content 一律拒绝：不让一条设置决定去访问网络或别人的 provider
                else -> null
            }
        }.getOrNull() ?: return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val owned = runCatching { ownedDirectory.canonicalFile }.getOrNull() ?: return null
        // 必须正好在私有目录下（子目录也不认），旧版本留下的嵌套路径不再信任
        if (canonical.parentFile != owned) return null
        if (requireExists && !canonical.isFile) return null
        return canonical
    }

    /** 设置里记录的、现在还留得住的私有文件，备份/迁移时用。 */
    fun ownedFiles(settings: FloatingCompanionSettings, ownedDirectory: File): List<File> =
        listOfNotNull(settings.customImagePath, settings.customTapImagePath)
            .distinct()
            .mapNotNull { resolve(it, ownedDirectory) }
}

/** 只做语法层面的清理；能不能真的读由 [FloatingCompanionPaths] 判断。 */
private fun normalizeOwnedPath(path: String?): String? {
    val raw = path?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return raw.takeIf { it.startsWith("file://") || it.startsWith("/") }
}
