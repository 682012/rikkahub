package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 聊天界面布局风格。三种风格共用同一份消息数据，只是呈现方式不同：
 * - [CLASSIC]：原来的左右气泡，老用户习惯的样子；
 * - [STUDIO]：工作台，上下文状态、会话仪表板，信息更密；
 * - [FOCUS]：专注，弱化装饰、少噪声，适合长时间阅读。
 */
@Serializable
enum class ChatUiStyle {
    @SerialName("classic")
    CLASSIC,

    @SerialName("studio")
    STUDIO,

    @SerialName("focus")
    FOCUS,
}

/** 壁纸填充方式：[CROP] 铺满裁切，[FIT] 完整显示（可能留边）。 */
@Serializable
enum class WallpaperScale {
    @SerialName("crop")
    CROP,

    @SerialName("fit")
    FIT,
}

/**
 * 外观设置：聊天界面风格 + 壁纸相关参数。
 *
 * 这里只放"长什么样"的数据，具体绘制由各界面读取；所有浮点字段都可能在历史配置里出现坏值
 * （裁剪、跨版本合并、手改 JSON 都可能写进 NaN），读出来统一过 [normalized] 再用。
 */
@Serializable
data class AppearanceSettings(
    val chatUiStyle: ChatUiStyle = ChatUiStyle.STUDIO,
    /** 壁纸来源：应用私有目录里的本地文件（file://...）或网络图片地址；空/NULL 表示没设置。 */
    val wallpaper: String? = null,
    /** 壁纸是否延伸到状态栏/导航栏等系统栏后面。 */
    val immersiveWallpaper: Boolean = true,
    /** 壁纸本身的不透明度，0 完全看不见，1 原样显示。 */
    val wallpaperOpacity: Float = 1f,
    /** 压在壁纸上的遮罩强度，越大壁纸越暗、文字越清楚。 */
    val wallpaperScrim: Float = 0.20f,
    /** 前景面板（消息、卡片、输入区）的不透明度，越大越不透明。 */
    val surfaceOpacity: Float = 0.86f,
    /** 壁纸模糊半径（dp），0 表示不模糊。 */
    val wallpaperBlur: Float = 0f,
    val wallpaperScale: WallpaperScale = WallpaperScale.CROP,
    /** 壁纸裁切时保留的焦点，0..1 的比例坐标。 */
    val wallpaperAlignmentX: Float = 0.5f,
    val wallpaperAlignmentY: Float = 0.5f,
    /** Preserve light/dark mode while coordinating panels with the visible wallpaper hue. */
    val wallpaperAdaptiveTint: Boolean = true,
    /**
     * 聊天页顶部信息栏是否展开：默认 false 只留一行“提供商 · 模型 · TPS”摘要，
     * 展开后才显示详细 TPS 与上下文面板。写入统一走 ChatStyleHeader 的回调 + 设置仓库。
     */
    val chatHeaderExpanded: Boolean = false,
    /**
     * 有壁纸时的沉浸聊天（r10）：消息不再垫一整块厚卡片，正文只留一层轻薄文字底，
     * 顶部栏与输入区改成半透明薄面板，缺的对比度由整层壁纸压暗补上。
     *
     * 默认开，但只在真的画着壁纸/渐变时才有区别——没有壁纸时这条开关完全不影响观感，
     * 保持原来的默认外观。关掉就是“恢复卡片”，回到 r9 的厚卡片观感。
     *
     * 强度不新增滑块：沿用 [surfaceOpacity]（设置里叫“面板强度”），沉浸模式下它决定文字底有多厚。
     */
    val immersiveChat: Boolean = true,
) {
    /**
     * 把可能存在的坏值收敛成一份能直接拿去画的设置。
     *
     * - NaN / Infinity 一律退回该字段的默认值，而不是夹到边界，避免一次坏写入永久改变观感；
     * - 0..1 的比例字段夹到 0..1，面板不透明度夹到 [MIN_SURFACE_OPACITY]..1（再低文字就看不清）；
     * - 模糊夹到 0..[MAX_BLUR_DP]；
     * - 只有空白的壁纸路径当作没设置，省得各个界面各写一遍 isBlank 判断。
     */
    fun normalized(): AppearanceSettings {
        val trimmedWallpaper = wallpaper?.trim()
        return copy(
            wallpaper = trimmedWallpaper?.takeIf { it.isNotEmpty() },
            wallpaperOpacity = wallpaperOpacity.unitOr(1f),
            wallpaperScrim = wallpaperScrim.unitOr(0.20f),
            surfaceOpacity = surfaceOpacity.finiteOr(0.86f).coerceIn(MIN_SURFACE_OPACITY, 1f),
            wallpaperBlur = wallpaperBlur.finiteOr(0f).coerceIn(0f, MAX_BLUR_DP),
            wallpaperAlignmentX = wallpaperAlignmentX.unitOr(0.5f),
            wallpaperAlignmentY = wallpaperAlignmentY.unitOr(0.5f),
        )
    }

    /** 是否真的有一张壁纸可画（空字符串也算没有）。 */
    fun hasWallpaper(): Boolean = !wallpaper.isNullOrBlank()

    /**
     * 这一刻该不该按沉浸模式画：既要开关开着，也要底下真的有一层背景（壁纸或助手渐变）。
     * 没背景时一律按原来的默认外观走。
     */
    fun immersiveChatActive(backgroundActive: Boolean): Boolean = immersiveChat && backgroundActive

    companion object {
        /** 面板不透明度的下限：再低消息会被壁纸吃掉。 */
        const val MIN_SURFACE_OPACITY = 0.6f

        /** 模糊上限（dp），和设置里滑块的取值范围一致。 */
        const val MAX_BLUR_DP = 32f
    }
}

/** 非有限值（NaN、±Inf）退回默认值，其余原样返回。 */
private fun Float.finiteOr(default: Float): Float = if (isFinite()) this else default

/** 非有限值退回默认值，再夹到 0..1。 */
private fun Float.unitOr(default: Float): Float = finiteOr(default).coerceIn(0f, 1f)
