package me.rerere.rikkahub.data.companion

import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings

/**
 * 一个图片位（待机 / 点击）最终会画出来的东西从哪来。
 *
 * 设置页缩略图和悬浮窗必须用同一套决策，否则会出现"设置里说没图、浮窗里却在画小鲸"这种分裂。
 * 这里只做纯逻辑：自定义路径能不能读由调用方先过 `FloatingCompanionFiles.resolve`，
 * 结果以 `resolvedCustomIdle`/`resolvedCustomTap` 传进来，绝不在这里碰 Context、文件或网络。
 */
enum class CompanionImageOrigin {
    /** 用户自己选进私有目录的图片。 */
    CUSTOM,

    /** 角色自带的原字节 PNG（`companions/six/<id>/<idle|tap>.png`）。 */
    BUILT_IN,

    /** 应用图标气泡：整块没有立绘，画的是 launcher 图标。 */
    APP_ICON,

    /** 这个位置没有图：点击位会沿用待机图。 */
    NONE,
}

/** 单个图片位的解析结果。 */
data class CompanionImageSlot(
    val origin: CompanionImageOrigin,
    /** 真正会画出来的地址（asset 或已验证的 `file://`）；[CompanionImageOrigin.APP_ICON]/[CompanionImageOrigin.NONE] 为 null。 */
    val model: String?,
    /** 设置里配了这一位的自定义路径。 */
    val hasCustom: Boolean,
    /** 配了自定义路径但读不出来（文件被删、路径越界）。和"没设置"是两件事，不能混着报。 */
    val customBroken: Boolean,
    /** 这一位的内置图；`CUSTOM`/`LOGO` 的点击位没有。 */
    val builtInModel: String?,
    /** 点击时不换图：没有点击图，或者点击图和待机图是同一张。 */
    val fallsBackToIdle: Boolean,
) {
    val showsImage: Boolean get() = model != null
}

/** 两张缩略图 + 设置页文案要用的整体决策。 */
data class CompanionImagePlan(
    /** 真正会渲染的预设（`CUSTOM` 没图时退成小鲸，`CAT`/`DRAGON` 落回新角色）。 */
    val effectivePreset: FloatingCompanionPreset,
    /** 画的是应用图标气泡，不是任何一只角色。 */
    val isAppIcon: Boolean,
    /** 内置角色 id（`gpt`/`deepseek`…）；`CUSTOM`/`LOGO` 为 null。 */
    val characterId: String?,
    /** 选了"我的图片"但没设待机图，现在临时借小鲸立绘用。 */
    val customPresetEmpty: Boolean,
    val idle: CompanionImageSlot,
    val tap: CompanionImageSlot,
)

object CompanionImageResolution {
    /**
     * 和 `PortraitCompanionBubble` 的取图顺序一致：
     * 待机 = 自定义（可读）→ 当前角色的内置待机图；LOGO 走应用图标气泡，不画立绘。
     * 点击 = 自定义（可读）→ 当前角色的内置点击图 → 没有就沿用待机图。
     *
     * @param resolvedCustomIdle 已由 `FloatingCompanionFiles.resolve` 验证过的待机图地址，没设置或不可读时 null
     * @param resolvedCustomTap 同上，点击图
     */
    fun plan(
        settings: FloatingCompanionSettings,
        resolvedCustomIdle: String?,
        resolvedCustomTap: String?,
    ): CompanionImagePlan {
        val effective = settings.effectivePreset
        val isAppIcon = effective == FloatingCompanionPreset.LOGO
        val hasCustomIdle = !settings.customImagePath.isNullOrBlank()
        val hasCustomTap = !settings.customTapImagePath.isNullOrBlank()

        if (isAppIcon) {
            // 图标气泡（LogoChatFloatingBubble）根本不看自定义图，这里照实说，不假装图已就绪。
            return CompanionImagePlan(
                effectivePreset = effective,
                isAppIcon = true,
                characterId = null,
                customPresetEmpty = false,
                idle = CompanionImageSlot(
                    origin = CompanionImageOrigin.APP_ICON,
                    model = null,
                    hasCustom = hasCustomIdle,
                    customBroken = false,
                    builtInModel = null,
                    fallsBackToIdle = false,
                ),
                tap = CompanionImageSlot(
                    origin = CompanionImageOrigin.NONE,
                    model = null,
                    hasCustom = hasCustomTap,
                    customBroken = false,
                    builtInModel = null,
                    fallsBackToIdle = true,
                ),
            )
        }

        val builtInIdle = CompanionCatalog.portraitAsset(effective)
        val builtInTap = CompanionCatalog.portraitTapAsset(effective)
        val idleModel = resolvedCustomIdle ?: builtInIdle
        val idle = CompanionImageSlot(
            origin = if (resolvedCustomIdle != null) CompanionImageOrigin.CUSTOM else CompanionImageOrigin.BUILT_IN,
            model = idleModel,
            hasCustom = hasCustomIdle,
            customBroken = hasCustomIdle && resolvedCustomIdle == null,
            builtInModel = builtInIdle,
            fallsBackToIdle = false,
        )

        val tapModel = resolvedCustomTap ?: builtInTap
        val tap = CompanionImageSlot(
            origin = when {
                resolvedCustomTap != null -> CompanionImageOrigin.CUSTOM
                builtInTap != null -> CompanionImageOrigin.BUILT_IN
                else -> CompanionImageOrigin.NONE
            },
            model = tapModel?.takeIf { it != idleModel },
            hasCustom = hasCustomTap,
            customBroken = hasCustomTap && resolvedCustomTap == null,
            builtInModel = builtInTap,
            fallsBackToIdle = tapModel == null || tapModel == idleModel,
        )

        return CompanionImagePlan(
            effectivePreset = effective,
            isAppIcon = false,
            characterId = CompanionCatalog.profile(effective)?.id,
            customPresetEmpty = settings.preset == FloatingCompanionPreset.CUSTOM && !hasCustomIdle,
            idle = idle,
            tap = tap,
        )
    }
}
