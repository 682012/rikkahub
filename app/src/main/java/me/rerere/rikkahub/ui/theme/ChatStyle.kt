package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.datastore.ChatUiStyle

/**
 * 三种聊天界面风格的布局参数。
 *
 * 这里只描述"长什么样"：标题栏是不是浮岛、消息间距、气泡圆角、输入区形态、阅读区宽度上限。
 * 消息数据、网络、工具、审批、语音这些逻辑一律不在样式层出现，换风格也不会丢功能。
 */
@Immutable
data class ChatStyleSpec(
    val style: ChatUiStyle,
    /** 标题栏用浮岛卡片（STUDIO），false 表示贴着状态栏的传统 app bar（CLASSIC/FOCUS）。 */
    val headerFloating: Boolean,
    /** 标题下方元数据是否紧凑成一行小字（STUDIO/FOCUS 更紧凑）。 */
    val headerDenseMeta: Boolean,
    /** 新会话按钮是否带文字标签。 */
    val headerNewChatLabel: Boolean,
    /** 是否在标题栏下常驻上下文状态条（还要同时满足旧的 useStudioDisplay 开关）。 */
    val headerShowStatusStrip: Boolean,
    /** 消息阅读区最大宽度；[Dp.Infinity] 表示铺满。 */
    val contentMaxWidth: Dp,
    /** 相邻消息之间的垂直间距。 */
    val messageSpacing: Dp,
    /** 消息列表左右留白。 */
    val listSidePadding: Dp,
    /** 气泡主圆角。 */
    val bubbleCorner: Dp,
    /** 气泡靠头像一侧收紧的角；等于 [bubbleCorner] 时就是无尾巴的全圆角卡片。 */
    val bubbleTailCorner: Dp,
    /** 输入区容器圆角。 */
    val inputCorner: Dp,
    /** 输入区左右留白。 */
    val inputSidePadding: Dp,
    /** 输入区是浮在工作区上方的独立面板（STUDIO），false 是贴底的横条（CLASSIC/FOCUS）。 */
    val inputFloating: Boolean,
    /** 输入区底部渐变遮罩强度，0 表示不额外铺遮罩。 */
    val inputScrimStrength: Float,
    /** 抽屉内容是否用更明显的浮层色（有壁纸时避免透到看不清）。 */
    val drawerSheetTone: Boolean,
    /** 抽屉顶部信息是否压缩（FOCUS）。 */
    val drawerHeaderCompact: Boolean,
) {
    companion object {
        /** CLASSIC：r5 原形态，左右气泡 + 贴底输入条 + 普通 app bar。 */
        val Classic = ChatStyleSpec(
            style = ChatUiStyle.CLASSIC,
            headerFloating = false,
            headerDenseMeta = false,
            headerNewChatLabel = false,
            headerShowStatusStrip = true,
            contentMaxWidth = Dp.Infinity,
            messageSpacing = 12.dp,
            listSidePadding = 16.dp,
            bubbleCorner = 24.dp,
            bubbleTailCorner = 6.dp,
            inputCorner = 28.dp,
            inputSidePadding = 8.dp,
            inputFloating = false,
            inputScrimStrength = 1f,
            drawerSheetTone = false,
            drawerHeaderCompact = false,
        )

        /** STUDIO：低噪声工作台，浮岛标题栏 + 上下文条 + 浮动输入面板。 */
        val Studio = ChatStyleSpec(
            style = ChatUiStyle.STUDIO,
            headerFloating = true,
            headerDenseMeta = true,
            headerNewChatLabel = true,
            headerShowStatusStrip = true,
            contentMaxWidth = 880.dp,
            messageSpacing = 10.dp,
            listSidePadding = 12.dp,
            bubbleCorner = 18.dp,
            bubbleTailCorner = 18.dp,
            inputCorner = 24.dp,
            inputSidePadding = 12.dp,
            inputFloating = true,
            inputScrimStrength = 0.75f,
            drawerSheetTone = true,
            drawerHeaderCompact = false,
        )

        /** FOCUS：专注阅读，收敛标题栏、加宽间距、极简输入面板。 */
        val Focus = ChatStyleSpec(
            style = ChatUiStyle.FOCUS,
            headerFloating = false,
            headerDenseMeta = true,
            headerNewChatLabel = false,
            headerShowStatusStrip = false,
            contentMaxWidth = 680.dp,
            messageSpacing = 20.dp,
            listSidePadding = 20.dp,
            bubbleCorner = 16.dp,
            bubbleTailCorner = 16.dp,
            inputCorner = 20.dp,
            inputSidePadding = 16.dp,
            inputFloating = false,
            inputScrimStrength = 0.55f,
            drawerSheetTone = true,
            drawerHeaderCompact = true,
        )

        fun of(style: ChatUiStyle): ChatStyleSpec = when (style) {
            ChatUiStyle.CLASSIC -> Classic
            ChatUiStyle.STUDIO -> Studio
            ChatUiStyle.FOCUS -> Focus
        }
    }
}

/**
 * 当前生效的风格参数。
 *
 * 只读 [LocalAppearance]（由主题/页面统一提供 normalized 后的外观设置），
 * 这样换风格时 header、composer、消息卡、抽屉一起变，不用每处各自读 Settings。
 */
@Composable
fun rememberChatStyleSpec(): ChatStyleSpec {
    val style = LocalAppearance.current.chatUiStyle
    return remember(style) { ChatStyleSpec.of(style) }
}

/** 是否真的有壁纸/渐变在底下（决定要不要叠模糊、渐变遮罩这些额外采样）。 */
@Composable
fun chatWallpaperActive(): Boolean = LocalWallpaperActive.current

/**
 * 页面底色：全局沉浸时是透明（让壁纸露出来），否则是主题背景色。
 *
 * 用 Unspecified 兜底，避免宿主没提供 [LocalPageContainer] 时把整个聊天页画成黑色。
 */
@Composable
fun chatPageContainerColor(): Color {
    val page = LocalPageContainer.current
    return if (page == Color.Unspecified) MaterialTheme.colorScheme.background else page
}

/**
 * 面板色（气泡、输入区、抽屉、浮岛标题栏）。
 *
 * 没有壁纸时返回原色；有壁纸时由 [appearanceSurface] 给一档安全的不透明度，
 * 保证壁纸再花也不会把文字吃掉。集中在这里调用，样式层只依赖一个入口。
 */
@Composable
fun chatPanel(base: Color, strong: Boolean = false): Color = appearanceSurface(base, strong)

/** 面板色但明确"这一层必须压得住字"（标题栏、抽屉、输入框）。 */
@Composable
fun chatPanelStrong(base: Color): Color = appearanceSurface(base, strong = true)

/**
 * 消息外框：包住一整条消息的那块大圆角卡片。
 *
 * 沉浸模式下这块直接透明——壁纸一路铺到消息之间，每条消息只留自己的轻薄正文底。
 * 没壁纸或关了沉浸时还是原来的面板色，r9 观感一点不变。
 */
@Composable
fun chatFrame(base: Color): Color = appearanceFrame(base)
