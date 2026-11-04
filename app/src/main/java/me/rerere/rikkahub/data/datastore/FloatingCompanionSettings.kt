package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI
import kotlin.uuid.Uuid

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

    @SerialName("gemini_cat")
    GEMINI_CAT,

    @SerialName("glm_fox")
    GLM_FOX,

    @SerialName("qwen_girl")
    QWEN_GIRL,
}

/** Stable storage identity; legacy aliases keep their original character. */
val FloatingCompanionPreset.characterKey: String
    get() = when (this) {
        FloatingCompanionPreset.CAT, FloatingCompanionPreset.WHALE_MAID -> "whale_maid"
        FloatingCompanionPreset.DRAGON, FloatingCompanionPreset.DRAGON_GIRL -> "dragon_girl"
        FloatingCompanionPreset.SUN_READER -> "sun_reader"
        FloatingCompanionPreset.GEMINI_CAT -> "gemini_cat"
        FloatingCompanionPreset.GLM_FOX -> "glm_fox"
        FloatingCompanionPreset.QWEN_GIRL -> "qwen_girl"
        FloatingCompanionPreset.CUSTOM -> "custom"
        FloatingCompanionPreset.LOGO -> "logo"
    }

/** User-authored values only. Built-in artwork and copy remain catalog defaults. */
@Serializable
data class FloatingCompanionOverrides(
    val name: String = "",
    val tapLines: List<String> = emptyList(),
    val aiPersonaPrompt: String = "",
    val customImagePath: String? = null,
    val customTapImagePath: String? = null,
) {
    fun normalized() = copy(
        name = name.trim().take(FloatingCompanionLimits.MAX_NAME_LENGTH),
        tapLines = tapLines.map { it.trim().take(FloatingCompanionLimits.MAX_TAP_LINE_LENGTH) }
            .filter { it.isNotEmpty() }.distinct().take(FloatingCompanionLimits.MAX_TAP_LINES),
        // r16: 用户自写人设是“整段优先”，normalized 不再 trim/take——
        // 旧版在这里静默截到 800 字，重进设置就把长人设改坏了。长度只在保存入口显式校验。
        aiPersonaPrompt = aiPersonaPrompt,
        customImagePath = normalizeOwnedPath(customImagePath),
        customTapImagePath = normalizeOwnedPath(customTapImagePath),
    )
}

/**
 * 小助手用哪个模型生成台词（r10）。
 *
 * [OFF] 是默认值：不发任何请求，只显示设置里的静态台词；点了角色也只是打开面板。
 * 用户挑一次模型之后（或者显式选"跟随当前会话"）点击、输入和自动互动才有真实模型参与。
 * 选 [FIXED] 但模型被删掉时不会悄悄换成别的模型，而是明确报"没有配置模型"。
 */
@Serializable
enum class CompanionModelMode {
    @SerialName("off")
    OFF,

    @SerialName("follow_conversation")
    FOLLOW_CONVERSATION,

    @SerialName("fixed")
    FIXED,
}

/**
 * 悬浮窗角色设置。
 *
 * 只描述"画什么、叫什么、说什么、谁来想台词"，怎么画在 `ui/floating/FloatingCompanion.kt`。
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

    // ---- r10：AI 小助手 ----

    /** 台词由谁想：默认 [CompanionModelMode.OFF]，即不发请求、沿用静态台词。 */
    val aiModelMode: CompanionModelMode = CompanionModelMode.OFF,
    /** [CompanionModelMode.FIXED] 时用的模型；模型被删掉时报错，不自动换别的模型。 */
    val aiModelId: Uuid? = null,
    /** 自定义个性提示；空表示用内置角色提示（每个立绘一份，默认即用）。 */
    val aiPersonaPrompt: String = "",
    /** 自动互动（生成开始/结束、出错、等审批时主动说一句）需要显式打开。 */
    val autoEventsEnabled: Boolean = false,
    /** 面板里的小助手状态卡是否展开；和预览开关一样只是显示状态。 */
    val companionStatusExpanded: Boolean = false,
    /** r12: inactive characters' overrides; the legacy fields above hold the active character. */
    val characterOverrides: Map<String, FloatingCompanionOverrides> = emptyMap(),
) {
    val characterKey: String get() = preset.characterKey

    private fun activeOverrides() = FloatingCompanionOverrides(
        name = name, tapLines = tapLines, aiPersonaPrompt = aiPersonaPrompt,
        customImagePath = customImagePath, customTapImagePath = customTapImagePath,
    )

    /** Lazy, lossless migration: save the old active fields before loading another character. */
    fun selectPreset(next: FloatingCompanionPreset): FloatingCompanionSettings {
        val current = normalized()
        if (next.characterKey == current.characterKey) return current.copy(preset = next)
        val saved = current.characterOverrides + (current.characterKey to current.activeOverrides())
        val target = saved[next.characterKey] ?: FloatingCompanionOverrides()
        return current.copy(
            preset = next, characterOverrides = saved,
            name = target.name, tapLines = target.tapLines, aiPersonaPrompt = target.aiPersonaPrompt,
            customImagePath = target.customImagePath, customTapImagePath = target.customTapImagePath,
        )
    }
    /** Shared imported files remain valid for every character, not only the selected one. */
    fun referencesImage(path: String): Boolean =
        path == customImagePath || path == customTapImagePath ||
            characterOverrides.values.any { path == it.customImagePath || path == it.customTapImagePath }

    /** AI 回应是否开启；关着时点击、输入都不发请求。 */
    val aiEnabled: Boolean
        get() = aiModelMode != CompanionModelMode.OFF

    /** 真正要画的：自定义没有待机图时临时用小鲸；旧别名仍映射回原角色。 */
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

    /**
     * 当前打开的角色格里的自写人设（原始值，不 trim）。
     *
     * [ownerCharacterKey] 是编辑器打开时记下的所有者；保存/读取都认这一格，
     * 不会因为此刻又切了角色就写到另一只身上。
     */
    fun personaOverrideOf(ownerCharacterKey: String): String {
        val owner = ownerCharacterKey.trim()
        if (owner == characterKey) return aiPersonaPrompt
        return characterOverrides[owner]?.aiPersonaPrompt.orEmpty()
    }

    /**
     * 把用户编辑的整段人设保存到 [ownerCharacterKey] 那一格（r16）。
     *
     * - 只改这一只的覆盖格，名字、图片、模型、开关和其他角色都不动；
     * - 超过 [FloatingCompanionLimits.MAX_PERSONA_LENGTH] 时 `require` 失败，**绝不 take**；
     * - 不 trim、不改写用户已有的换行和空格；空/全空白才表示“恢复内置”。
     */
    fun withPersonaOverride(ownerCharacterKey: String, prompt: String): FloatingCompanionSettings {
        val owner = ownerCharacterKey.trim()
        require(owner.isNotEmpty()) { "persona owner must not be blank" }
        require(prompt.length <= FloatingCompanionLimits.MAX_PERSONA_LENGTH) {
            "persona is ${prompt.length} UTF-16 units, limit is ${FloatingCompanionLimits.MAX_PERSONA_LENGTH}"
        }
        val saved = if (prompt.isBlank()) "" else prompt
        if (owner == characterKey) return copy(aiPersonaPrompt = saved)
        val existing = characterOverrides[owner] ?: FloatingCompanionOverrides()
        return copy(characterOverrides = characterOverrides + (owner to existing.copy(aiPersonaPrompt = saved)))
    }

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
        // r16: 同上，读设置不再改已有 persona；超长只会在保存入口被显式拒绝。
        aiPersonaPrompt = aiPersonaPrompt,
        characterOverrides = characterOverrides.mapValues { (_, value) -> value.normalized() },
        // 自动互动依赖 AI 回应：关掉 AI 后残留的 true 不应该在下次打开时凭空开始花钱
        autoEventsEnabled = autoEventsEnabled && aiModelMode != CompanionModelMode.OFF,
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

    /**
     * 人设编辑/导入的上限（Kotlin `String.length`，即 UTF-16 code units）。
     *
     * r15 及以前是 800，完整内置卡（约 4500 字符）会被静默截掉大半。
     * r16 起内置卡走只读资产目录、不经过这个上限；用户自写覆盖保存到这里校验。
     * 12000 是素材包要求的下限，16000 给用户留出改写空间，仍远小于设置文件的风险线。
     */
    const val MAX_PERSONA_LENGTH = 16_000

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
        (listOfNotNull(settings.customImagePath, settings.customTapImagePath) +
            settings.characterOverrides.values.flatMap { listOfNotNull(it.customImagePath, it.customTapImagePath) })
            .distinct()
            .mapNotNull { resolve(it, ownedDirectory) }
}

/** 只做语法层面的清理；能不能真的读由 [FloatingCompanionPaths] 判断。 */
private fun normalizeOwnedPath(path: String?): String? {
    val raw = path?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return raw.takeIf { it.startsWith("file:") || it.startsWith("/") }
}
