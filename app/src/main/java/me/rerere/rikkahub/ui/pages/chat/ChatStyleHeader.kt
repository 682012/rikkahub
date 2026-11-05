package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatUiStyle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.lastContextTokens
import me.rerere.rikkahub.ui.theme.ChatStyleSpec
import me.rerere.rikkahub.ui.theme.chatPanel
import me.rerere.rikkahub.ui.theme.chatPanelStrong
import me.rerere.rikkahub.ui.theme.chatWallpaperActive

/**
 * 聊天页实际使用的标题栏，四种风格共用同一组数据与回调：
 * 抽屉入口、标题编辑、用量统计、预览切换、新会话一个都不少，只是排布和视觉不同。
 *
 * 顶部信息分两层，两种状态都不丢关键入口：
 * - 主 bar（title + 48dp 命中区图标 / 新会话）始终在位，折叠也不会把 drawer、new 藏起来；
 * - 摘要行常显一行 `提供商 · 模型 · TPS`，长名省略，点右侧箭头展开；
 * - 展开态才追加详细 TPS 与上下文面板（[ChatSpeedStatus] + [ChatStudioStatus]）。
 *
 * 状态来源：持久值读 [Settings.displaySetting] 里的 `appearance.chatHeaderExpanded`，
 * 切换一律走 [onHeaderExpandedChange]。ChatPage 接入示例（不在此文件写 globals）：
 * ```
 * onHeaderExpandedChange = { expanded ->
 *     scope.launch {
 *         store.update { s ->
 *             s.copy(displaySetting = s.displaySetting.copy(
 *                 appearance = s.displaySetting.appearance.copy(chatHeaderExpanded = expanded),
 *             ))
 *         }
 *     }
 * }
 * ```
 * 没接回调时（预览 / 测试）退化为按会话保存的本地状态，key 带 `conversation.id`，不跨会话串。
 */
@Composable
internal fun ChatStyleHeader(
    spec: ChatStyleSpec,
    settings: Settings,
    conversation: Conversation,
    chatModel: Model?,
    title: String,
    meta: String?,
    bigScreen: Boolean,
    previewMode: Boolean,
    onOpenDrawer: () -> Unit,
    onEditTitle: () -> Unit,
    onOpenUsage: () -> Unit,
    onTogglePreview: () -> Unit,
    onNewChat: () -> Unit,
    /** 由主代理从设置仓库喂进来的持久值；默认直接读当前 settings。 */
    headerExpanded: Boolean = settings.displaySetting.appearance.chatHeaderExpanded,
    /** 用户点了展开/收起；null 表示没人接持久化，退化为本地会话内状态。 */
    onHeaderExpandedChange: ((Boolean) -> Unit)? = null,
    generationActive: Boolean? = null,
    hasError: Boolean = false,
    recovering: Boolean = false,
) {
    // 顶部信息现在由自己的折叠开关控制：收起只留摘要，展开就是详细 TPS + 上下文，
    // 三种风格行为一致，不再看旧的 useStudioDisplay 开关脸色
    // 当前请求真正会用的提供商 + 模型，三种风格都常显，不再依赖 usage 开关或消息历史
    val unselectedModelLabel = stringResource(R.string.chat_header_model_unselected)
    val providerInfo = remember(chatModel, settings.providers, unselectedModelLabel) {
        chatHeaderProviderInfo(
            model = chatModel,
            providers = settings.providers,
            unselectedLabel = unselectedModelLabel,
        )
    }
    val speed = conversationSpeedUi(conversation)

    // 有回调就是受控组件（持久化在设置里）；没有回调时按会话保存。
    // key 用会话 id 的字符串：换会话就是新状态，不会把上一个会话的展开状态带过来。
    var localExpanded by rememberSaveable(conversation.id.toString()) { mutableStateOf(headerExpanded) }
    val expanded = if (onHeaderExpandedChange != null) headerExpanded else localExpanded
    val onToggleExpanded: () -> Unit = {
        val next = !expanded
        if (onHeaderExpandedChange != null) onHeaderExpandedChange(next) else localExpanded = next
    }

    // 标题栏形态按风格分派：CLASSIC/FOCUS 用贴顶 app bar，STUDIO 用浮岛卡片，
    // ORBIT（r21 轨道）用贴着状态栏的任务栏 + 常驻运行态轨道。四个分支穷尽，加风格时这里会报错提醒。
    when (spec.style) {
        ChatUiStyle.ORBIT -> TaskRailHeader(
            spec = spec,
            conversation = conversation,
            settings = settings,
            title = title,
            meta = meta,
            generationActive = generationActive,
            hasError = hasError,
            recovering = recovering,
            providerInfo = providerInfo,
            speed = speed,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            bigScreen = bigScreen,
            previewMode = previewMode,
            onOpenDrawer = onOpenDrawer,
            onEditTitle = onEditTitle,
            onOpenUsage = onOpenUsage,
            onTogglePreview = onTogglePreview,
            onNewChat = onNewChat,
        )

        ChatUiStyle.STUDIO -> StudioHeader(
            spec = spec,
            conversation = conversation,
            settings = settings,
            title = title,
            meta = meta,
            providerInfo = providerInfo,
            speed = speed,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            bigScreen = bigScreen,
            previewMode = previewMode,
            onOpenDrawer = onOpenDrawer,
            onEditTitle = onEditTitle,
            onOpenUsage = onOpenUsage,
            onTogglePreview = onTogglePreview,
            onNewChat = onNewChat,
        )

        ChatUiStyle.CLASSIC, ChatUiStyle.FOCUS -> PlainHeader(
            spec = spec,
            conversation = conversation,
            settings = settings,
            title = title,
            meta = meta,
            providerInfo = providerInfo,
            speed = speed,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            bigScreen = bigScreen,
            previewMode = previewMode,
            onOpenDrawer = onOpenDrawer,
            onEditTitle = onEditTitle,
            onOpenUsage = onOpenUsage,
            onTogglePreview = onTogglePreview,
            onNewChat = onNewChat,
        )
    }
}

/**
 * ORBIT（r21「轨道」）：贴着状态栏的紧凑任务栏 + 常驻运行态轨道。
 *
 * 与另外三种的真差异在信息落点，而不是配色：
 * - 任务栏只有标题和四个入口（抽屉/用量/大纲/新会话），48dp 命中区一个不少；
 * - 下面一行是轨道：运行态圆点 + 提供商 · 模型 + TPS + 上下文表盘 + 展开箭头，
 *   折叠状态也把这些常显着，不点开也能看到"现在跑到哪了"；
 * - 左缘一条状态色竖轨，把整块和卡片区分开；下缘收圆角，贴着状态栏像任务台而不是浮岛。
 *
 * 速度口径、上下文口径和展开详情里的面板完全共用一份数据（[ChatSpeedUi] /
 * [ChatStudioStatus]），所以轨道上的数字与展开后的数字不会对不上。
 */
@Composable
private fun TaskRailHeader(
    spec: ChatStyleSpec,
    settings: Settings,
    conversation: Conversation,
    title: String,
    meta: String?,
    generationActive: Boolean?,
    hasError: Boolean,
    recovering: Boolean,
    providerInfo: ChatHeaderProviderInfo,
    speed: ChatSpeedUi,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    bigScreen: Boolean,
    previewMode: Boolean,
    onOpenDrawer: () -> Unit,
    onEditTitle: () -> Unit,
    onOpenUsage: () -> Unit,
    onTogglePreview: () -> Unit,
    onNewChat: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val wallpaper = chatWallpaperActive()
    // 运行态统一由纯函数推导：恢复 / 出错 / 生成中优先，其余按会话数据说话
    val runState = orbitHeaderRunState(
        generationActive = generationActive,
        hasError = hasError,
        recovering = recovering,
        conversation = conversation,
    )
    val contextRatio = remember(conversation, settings.providers) {
        orbitHeaderContextRatio(conversation, settings)
    }
    // 表盘是"能放才放"：宽度与字体缩放一起算，宁可不画也不挤掉模型名和 TPS
    val density = LocalDensity.current
    val widthDp = LocalWindowInfo.current.containerDpSize.width.value
    val gaugeRatio = contextRatio?.takeIf {
        spec.railContextGauge && orbitContextGaugeVisible(widthDp, density.fontScale)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatHeaderRootTag)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
            color = chatPanelStrong(scheme.surfaceContainerLow),
            border = BorderStroke(
                width = 1.dp,
                color = scheme.outlineVariant.copy(alpha = if (wallpaper) 0.45f else 0.3f),
            ),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // 左缘状态竖轨，用 matchParentSize 画，不吃布局尺寸
                OrbitRailStripe(runState)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (!bigScreen) {
                            CompactIcon(stringResource(R.string.chat_style_open_drawer), onOpenDrawer) {
                                Icon(HugeIcons.Menu03, null)
                            }
                        }
                        HeaderTitle(
                            title = title,
                            meta = meta,
                            providerInfo = providerInfo,
                            dense = true,
                            titleStyle = MaterialTheme.typography.titleSmallEmphasized,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clickable(onClick = onEditTitle),
                        )
                        // 与 STUDIO 一样用文字按钮：轨道风格也把"用量"当常驻入口，不用再猜图标
                        TextButton(
                            onClick = onOpenUsage,
                            modifier = Modifier.size(48.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.chat_style_usage),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                        CompactIcon(stringResource(R.string.chat_style_toggle_preview), onTogglePreview) {
                            Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, null)
                        }
                        CompactIcon(stringResource(R.string.chat_style_new_chat), onNewChat) {
                            Icon(HugeIcons.MessageAdd01, null)
                        }
                    }
                    HeaderSummaryRow(
                        info = providerInfo,
                        speed = speed,
                        expanded = expanded,
                        onToggle = onToggleExpanded,
                        runStateDot = runState,
                        contextGauge = gaugeRatio,
                        modelEmphasis = true,
                        shape = RoundedCornerShape(12.dp),
                        container = chatPanel(scheme.surfaceContainerHigh),
                    )
                }
            }
        }
        HeaderDetail(
            spec = spec,
            settings = settings,
            conversation = conversation,
            expanded = expanded,
            onOpenUsage = onOpenUsage,
        )
    }
}

/** 轨道表盘读数：与 [ChatStudioStatus] 同一份口径（最后一条助手消息的模型容量 + 上轮上下文）。 */
private fun orbitHeaderContextRatio(conversation: Conversation, settings: Settings): Float? {
    val used = conversation.lastContextTokens()
    val capacity = conversation.currentMessages
        .lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.let { settings.findModelById(it.modelId)?.contextLength }
    return orbitContextRatio(used, capacity)
}

/** STUDIO：浮岛标题栏 + 一行摘要 + 按需展开的详情。 */
@Composable
private fun StudioHeader(
    spec: ChatStyleSpec,
    settings: Settings,
    conversation: Conversation,
    title: String,
    meta: String?,
    providerInfo: ChatHeaderProviderInfo,
    speed: ChatSpeedUi,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    bigScreen: Boolean,
    previewMode: Boolean,
    onOpenDrawer: () -> Unit,
    onEditTitle: () -> Unit,
    onOpenUsage: () -> Unit,
    onTogglePreview: () -> Unit,
    onNewChat: () -> Unit,
) {
    val wallpaper = chatWallpaperActive()
    val newChatDescription = stringResource(R.string.chat_style_new_chat)
    // 320dp 竖屏放不下带文字的新会话按钮，退回纯图标
    val wideEnough = LocalWindowInfo.current.containerDpSize.width >= 460.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatHeaderRootTag)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = chatPanelStrong(MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (wallpaper) 0.45f else 0.3f),
            ),
            shadowElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!bigScreen) {
                    CompactIcon(stringResource(R.string.chat_style_open_drawer), onOpenDrawer) {
                        Icon(HugeIcons.Menu03, null)
                    }
                }
                HeaderTitle(
                    title = title,
                    meta = meta,
                    providerInfo = providerInfo,
                    dense = spec.headerDenseMeta,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onEditTitle),
                )
                TextButton(
                    onClick = onOpenUsage,
                    modifier = Modifier.size(48.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_style_usage),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                CompactIcon(
                    description = stringResource(R.string.chat_style_toggle_preview),
                    onClick = onTogglePreview,
                ) {
                    Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, null)
                }
                if (wideEnough) {
                    FilledTonalButton(
                        onClick = onNewChat,
                        modifier = Modifier.height(48.dp).semantics { contentDescription = newChatDescription },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(HugeIcons.MessageAdd01, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.chat_style_new_chat),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                } else {
                    CompactIcon(stringResource(R.string.chat_style_new_chat), onNewChat) {
                        Icon(HugeIcons.MessageAdd01, null)
                    }
                }
            }
        }
        HeaderSummaryRow(
            info = providerInfo,
            speed = speed,
            expanded = expanded,
            onToggle = onToggleExpanded,
        )
        HeaderDetail(
            spec = spec,
            settings = settings,
            conversation = conversation,
            expanded = expanded,
            onOpenUsage = onOpenUsage,
        )
    }
}

/** CLASSIC（原样式）与 FOCUS（收敛样式）共用的贴顶标题栏。 */
@Composable
private fun PlainHeader(
    spec: ChatStyleSpec,
    settings: Settings,
    conversation: Conversation,
    title: String,
    meta: String?,
    providerInfo: ChatHeaderProviderInfo,
    speed: ChatSpeedUi,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    bigScreen: Boolean,
    previewMode: Boolean,
    onOpenDrawer: () -> Unit,
    onEditTitle: () -> Unit,
    onOpenUsage: () -> Unit,
    onTogglePreview: () -> Unit,
    onNewChat: () -> Unit,
) {
    val focus = spec.style == ChatUiStyle.FOCUS
    Surface(color = chatPanelStrong(MaterialTheme.colorScheme.background)) {
    Column(modifier = Modifier.fillMaxWidth().testTag(ChatHeaderRootTag)) {
        if (focus) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    )
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!bigScreen) {
                    CompactIcon(stringResource(R.string.chat_style_open_drawer), onOpenDrawer) {
                        Icon(HugeIcons.Menu03, null)
                    }
                }
                HeaderTitle(
                    title = title,
                    meta = meta,
                    providerInfo = providerInfo,
                    dense = true,
                    titleStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onEditTitle),
                )
                // FOCUS 不常驻状态条，用这个按钮按需进同一份统计数据
                CompactIcon(stringResource(R.string.chat_style_usage), onOpenUsage) {
                    Icon(HugeIcons.ChartColumn, null)
                }
                CompactIcon(stringResource(R.string.chat_style_toggle_preview), onTogglePreview) {
                    Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, null)
                }
                CompactIcon(stringResource(R.string.chat_style_new_chat), onNewChat) {
                    Icon(HugeIcons.MessageAdd01, null)
                }
            }
        } else {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    if (!bigScreen) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(HugeIcons.Menu03, stringResource(R.string.chat_style_open_drawer))
                        }
                    }
                },
                title = {
                    HeaderTitle(
                        title = title,
                        meta = meta,
                        providerInfo = providerInfo,
                        dense = false,
                        metaSize = if (settings.displaySetting.useStudioDisplay) 11.sp else 10.sp,
                        modifier = Modifier.clickable(onClick = onEditTitle),
                    )
                },
                actions = {
                    TextButton(onClick = onOpenUsage) {
                        Text(
                            text = stringResource(R.string.chat_style_usage),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    IconButton(onClick = onTogglePreview) {
                        Icon(
                            imageVector = if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet,
                            contentDescription = stringResource(R.string.chat_style_toggle_preview),
                        )
                    }
                    IconButton(onClick = onNewChat) {
                        Icon(
                            imageVector = HugeIcons.MessageAdd01,
                            contentDescription = stringResource(R.string.chat_style_new_chat),
                        )
                    }
                },
            )
        }
        HeaderSummaryRow(
            info = providerInfo,
            speed = speed,
            expanded = expanded,
            onToggle = onToggleExpanded,
        )
        HeaderDetail(
            spec = spec,
            settings = settings,
            conversation = conversation,
            expanded = expanded,
            onOpenUsage = onOpenUsage,
        )
    }
    }
}

/** 标题 + 一行“提供商 · 模型”（meta 跟在后面）；长文本单行省略，窄屏和大字都不会顶开按钮。 */
@Composable
private fun HeaderTitle(
    title: String,
    meta: String?,
    providerInfo: ChatHeaderProviderInfo,
    dense: Boolean,
    metaSize: TextUnit = 11.sp,
    titleStyle: TextStyle? = null,
    modifier: Modifier = Modifier,
) {
    val subtitle = meta?.takeIf { it.isNotBlank() }
    Column(modifier = modifier.padding(horizontal = 6.dp, vertical = if (dense) 4.dp else 6.dp)) {
        Text(
            text = title,
            maxLines = 1,
            style = titleStyle ?: MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) Text(
            text = subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = LocalContentColor.current.copy(0.8f),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = metaSize),
        )
    }
}

/**
 * 折叠态就靠这一行：提供商 + 模型 + TPS 在同一条线上。
 *
 * - 提供商单独限宽（weight 比例 + 先省略），不会把模型挤没；
 * - 模型拿剩余宽度，单行省略；展开时放宽到两行，长名点了就能看全；
 * - 速度固定在右侧，永远是同一份 [conversationSpeedText]；
 * - 右侧箭头 48dp 命中区，折叠/展开各有 contentDescription + stateDescription + 同一个 testTag。
 */
@Composable
private fun HeaderSummaryRow(
    info: ChatHeaderProviderInfo,
    speed: ChatSpeedUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * ORBIT（轨道）在摘要行最前面加一个运行态圆点：状态常驻，不用点开面板才知道。
     * 其它三种风格不传，摘要行与 r8 观感完全一致。
     */
    runStateDot: OrbitRunState? = null,
    /** ORBIT 在速度右侧加上下文表盘；null 表示这个宽度/字体下放不下，宁可不画。 */
    contextGauge: Float? = null,
    /** ORBIT 把当前模型加重：这一行是"这次请求用哪个模型"的主载体。 */
    modelEmphasis: Boolean = false,
    shape: Shape = MaterialTheme.shapes.small,
    container: Color = chatPanelStrong(MaterialTheme.colorScheme.surfaceContainerLow),
) {
    val summary = stringResource(R.string.chat_header_summary_description)
    val toggleLabel = stringResource(
        if (expanded) R.string.chat_header_collapse_details else R.string.chat_header_expand_details
    )
    val toggleState = stringResource(
        if (expanded) R.string.chat_header_state_expanded else R.string.chat_header_state_collapsed
    )
    // 折叠行只有一行位置，可见数值仍是同一个 valueText；覆盖步数与缺测原因挂在无障碍描述里，
    // 与消息底部、展开详情用的是同一份 ChatSpeedUi。
    val speedText = speed.valueText
    val speedA11y = listOfNotNull(
        speedText,
        chatSpeedCoverageText(speed),
        chatSpeedReasonText(speed),
    ).joinToString(" · ")
    val summaryText = chatHeaderSummaryText(info, speedText)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ChatHeaderSummaryTag)
                .heightIn(min = 40.dp)
                .padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            runStateDot?.let { OrbitRunDot(it) }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .testTag(ChatHeaderModelTag)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$summary: $summaryText"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                info.providerName?.let { provider ->
                    Text(
                        text = provider,
                        modifier = Modifier.weight(0.45f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ChatHeaderProviderSeparator.trim(),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = info.modelName,
                    modifier = Modifier.weight(1f),
                    maxLines = if (expanded) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    style = if (modelEmphasis) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                )
            }
            Text(
                text = speedText,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .testTag(ChatHeaderSpeedTag)
                    .then(
                        if (speedA11y != speedText) {
                            Modifier.semantics { contentDescription = speedA11y }
                        } else {
                            Modifier
                        }
                    ),
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            contextGauge?.let { OrbitContextGauge(it) }
            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(ChatHeaderToggleTag)
                    .semantics {
                        contentDescription = toggleLabel
                        stateDescription = toggleState
                    },
            ) {
                Icon(
                    imageVector = if (expanded) HugeIcons.ArrowUp02 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 展开态详情：详细 TPS + 上下文面板，折叠时整块不进组合（不是隐藏），
 * 所以收起后高度立刻少两块面板，动画也只是一层轻量 fade + 竖直伸缩。
 */
@Composable
private fun HeaderDetail(
    spec: ChatStyleSpec,
    settings: Settings,
    conversation: Conversation,
    expanded: Boolean,
    onOpenUsage: () -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ChatHeaderDetailTag),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChatSpeedStatus(conversation)
            // 展开就两种信息都给：详细 TPS + 上下文；三种风格同一套行为
            ChatStudioStatus(
                conversation = conversation,
                settings = settings,
                style = spec.style,
                onClick = onOpenUsage,
            )
        }
    }
}

/** 图标保持 48dp 命中区，窄屏收起文字标签，不缩触控面积。 */
@Composable
private fun CompactIcon(
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            content()
        }
    }
}
