package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.ai.provider.Model
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatUiStyle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.theme.ChatStyleSpec
import me.rerere.rikkahub.ui.theme.chatPanelStrong
import me.rerere.rikkahub.ui.theme.chatWallpaperActive

/**
 * 聊天页实际使用的标题栏，三种风格共用同一组数据与回调：
 * 抽屉入口、标题编辑、用量统计、预览切换、新会话一个都不少，只是排布和视觉不同。
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
) {
    // 上下文状态条还受旧的 useStudioDisplay 开关控制：那个开关管"统计怎么呈现"，风格只管排版
    val showStatusStrip = spec.headerShowStatusStrip && settings.displaySetting.useStudioDisplay
    // 当前请求真正会用的提供商 + 模型，三种风格都常显，不再依赖 usage 开关或消息历史
    val unselectedModelLabel = stringResource(R.string.chat_header_model_unselected)
    val providerInfo = remember(chatModel, settings.providers, unselectedModelLabel) {
        chatHeaderProviderInfo(
            model = chatModel,
            providers = settings.providers,
            unselectedLabel = unselectedModelLabel,
        )
    }
    if (spec.headerFloating) {
        StudioHeader(
            spec = spec,
            conversation = conversation,
            settings = settings,
            title = title,
            meta = meta,
            providerInfo = providerInfo,
            bigScreen = bigScreen,
            previewMode = previewMode,
            showStatusStrip = showStatusStrip,
            onOpenDrawer = onOpenDrawer,
            onEditTitle = onEditTitle,
            onOpenUsage = onOpenUsage,
            onTogglePreview = onTogglePreview,
            onNewChat = onNewChat,
        )
    } else {
        PlainHeader(
            spec = spec,
            conversation = conversation,
            settings = settings,
            title = title,
            meta = meta,
            providerInfo = providerInfo,
            bigScreen = bigScreen,
            previewMode = previewMode,
            showStatusStrip = showStatusStrip,
            onOpenDrawer = onOpenDrawer,
            onEditTitle = onEditTitle,
            onOpenUsage = onOpenUsage,
            onTogglePreview = onTogglePreview,
            onNewChat = onNewChat,
        )
    }
}

/** STUDIO：浮岛标题栏 + 上下文条，标题、用量、预览、新会话都收在这一块。 */
@Composable
private fun StudioHeader(
    spec: ChatStyleSpec,
    settings: Settings,
    conversation: Conversation,
    title: String,
    meta: String?,
    providerInfo: ChatHeaderProviderInfo,
    bigScreen: Boolean,
    previewMode: Boolean,
    showStatusStrip: Boolean,
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
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
        HeaderSelectedModelRow(providerInfo)
        ChatSpeedStatus(conversation)
        if (showStatusStrip) {
            ChatStudioStatus(
                conversation = conversation,
                settings = settings,
                style = spec.style,
                onClick = onOpenUsage,
            )
        }
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
    bigScreen: Boolean,
    previewMode: Boolean,
    showStatusStrip: Boolean,
    onOpenDrawer: () -> Unit,
    onEditTitle: () -> Unit,
    onOpenUsage: () -> Unit,
    onTogglePreview: () -> Unit,
    onNewChat: () -> Unit,
) {
    val focus = spec.style == ChatUiStyle.FOCUS
    Surface(color = chatPanelStrong(MaterialTheme.colorScheme.background)) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
        HeaderSelectedModelRow(providerInfo)
        ChatSpeedStatus(conversation)
        if (showStatusStrip) {
            ChatStudioStatus(
                conversation = conversation,
                settings = settings,
                style = spec.style,
                onClick = onOpenUsage,
            )
        }
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

/** Full-width row reserves most space for the model, rather than truncating provider+model together. */
@Composable
private fun HeaderSelectedModelRow(info: ChatHeaderProviderInfo) {
    val description = stringResource(R.string.chat_header_current_model)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = chatPanelStrong(MaterialTheme.colorScheme.surfaceContainerLow),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                .testTag(ChatHeaderModelTag)
                .semantics(mergeDescendants = true) { contentDescription = "$description: ${info.text}" },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            info.providerName?.let { provider ->
                Text(provider, modifier = Modifier.weight(.38f, fill = false), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(info.modelName, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
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
