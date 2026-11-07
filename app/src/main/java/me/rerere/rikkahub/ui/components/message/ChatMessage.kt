package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.SelectableContent
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalWorkspaceId
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.chatPanel
import me.rerere.rikkahub.ui.theme.rememberChatStyleSpec
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import me.rerere.ui.charts.ChartCard
import me.rerere.ui.charts.ChartSpec
import me.rerere.ui.webview.WebViewContentCache
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    /** tool_batch 逐条批准：只执行被勾选的成员下标；null = 界面不提供逐条批准（仍可整批批准/拒绝）。 */
    onToolMembersApproval: ((toolCallId: String, indices: Set<Int>) -> Unit)? = null,
) {
    val message = node.messages[node.selectIndex]
    if (message.isContextCheckpoint) {
        ChatMessageContextCheckpoint(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            modifier = modifier,
        )
        return
    }
    val settings = LocalSettings.current.displaySetting
    val chatFontFamily = LocalChatFontFamily.current ?: rememberChatFontFamily(settings)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = chatFontFamily
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val wallpaperPanel = if (me.rerere.rikkahub.ui.theme.LocalWallpaperActive.current &&
        !me.rerere.rikkahub.ui.theme.LocalImmersiveChat.current) {
        Modifier.background(chatPanel(colorScheme.surfaceContainerLow), RoundedCornerShape(rememberChatStyleSpec().bubbleCorner))
            .padding(8.dp)
    } else Modifier
    Column(
        modifier = modifier.fillMaxWidth().then(wallpaperPanel),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        CompositionLocalProvider(
            LocalTextStyle provides textStyle,
            LocalWorkspaceId provides assistant?.workspaceId?.toString(),
        ) {
            MessagePartsBlock(
                assistant = assistant,
                role = message.role,
                // 折叠状态按节点 id 记，切换分支/回收复用都不会串
                messageId = node.id.toString(),
                parts = message.parts,
                annotations = message.annotations,
                loading = loading,
                model = model,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onToolMembersApproval = onToolMembersApproval,
                onUserMessageClick = if (message.role == MessageRole.USER) onEdit else null,
            )

            message.translation?.let { translation ->
                CollapsibleTranslationText(
                    content = translation,
                    onClickCitation = {}
                )
            }
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        val motionScheme = MaterialTheme.motionScheme
        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically(motionScheme.defaultSpatialSpec()) { it / 2 } +
                fadeIn(motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(motionScheme.fastSpatialSpec()) { it / 2 } +
                fadeOut(motionScheme.fastEffectsSpec())
        ) {
            Column(
                modifier = Modifier.animateContentSize()
            ) {
                ChatMessageActionButtons(
                    message = message,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation
                )
            }
        }

        EditedFilesList(
            parts = message.parts,
            assistant = assistant,
        )

        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(message = message)
        }

    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

// 思考链折叠时默认保留的尾部步骤数
private const val COLLAPSED_VISIBLE_STEPS = 2

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    messageId: String,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToolMembersApproval: ((toolCallId: String, indices: Set<Int>) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    // 气泡圆角/底色随风格走；有壁纸时 chatPanel 会补一档安全不透明度
    val styleSpec = rememberChatStyleSpec()
    val partsState by rememberUpdatedState(parts)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    // 连续的 Text part 合成一条，套同一个折叠容器；图片/视频/文件/图表各自独立，永远不会被裁
    val renderUnits = remember(groupedParts) { groupedParts.toMessageRenderUnits() }
    // 长消息正文折叠开关（DisplaySetting 字段未落地时按默认 true 走）
    val collapseLongMessages = settings.displaySetting.collapseLongMessages
    // 渐隐条要融进正文底色：用户气泡 / 助手气泡 / 无气泡时的面板色
    val collapseFadeColor = when {
        role == MessageRole.USER -> chatPanel(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.displaySetting.bubbleOpacity)
        )

        settings.displaySetting.showAssistantBubble -> chatPanel(
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity)
        )

        else -> chatPanel(MaterialTheme.colorScheme.surface)
    }

    // 单个 Text part 的正文渲染。折叠容器只是套在外面：正文照旧整段交给 Markdown，
    // 不做 substring 截断，选择/复制拿到的还是全文
    val renderTextPart: @Composable (UIMessagePart.Text, Int) -> Unit = { part, index ->
        // 只有正在生成的最后一段文字可能停在没写完的语法上
        val streamingText = loading && index == parts.lastIndex
        if (role == MessageRole.USER) {
            ChatMessageBubble(
                role = role,
                color = chatPanel(
                    MaterialTheme.colorScheme.primaryContainer
                        .copy(alpha = settings.displaySetting.bubbleOpacity),
                ),
                restCorner = styleSpec.bubbleCorner,
                tailCorner = styleSpec.bubbleTailCorner,
                onClick = { onUserMessageClick?.invoke() },
                // 用户气泡不占满整行，和助手的回复错开
                modifier = Modifier
                    .padding(start = 24.dp)
                    .animateContentSize(),
            ) {
                MarkdownBlock(
                    content = part.text.replaceRegexes(
                        assistant = assistant,
                        scope = AssistantAffectScope.USER,
                        visual = true,
                    ),
                    onClickCitation = handleClickCitation
                )
            }
        } else {
            if (settings.displaySetting.showAssistantBubble) {
                ChatMessageBubble(
                    role = role,
                    color = chatPanel(
                        MaterialTheme.colorScheme.surfaceContainerHigh
                            .copy(alpha = settings.displaySetting.bubbleOpacity),
                    ),
                    restCorner = styleSpec.bubbleCorner,
                    tailCorner = styleSpec.bubbleTailCorner,
                    modifier = Modifier.animateContentSize(),
                ) {
                    MarkdownBlock(
                        content = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.ASSISTANT,
                            visual = true,
                        ),
                        streaming = streamingText,
                        onClickCitation = handleClickCitation,
                    )
                }
            } else {
                MarkdownBlock(
                    content = part.text.replaceRegexes(
                        assistant = assistant,
                        scope = AssistantAffectScope.ASSISTANT,
                        visual = true,
                    ),
                    streaming = streamingText,
                    onClickCitation = handleClickCitation,
                    modifier = Modifier
                        .then(if (me.rerere.rikkahub.ui.theme.LocalImmersiveChat.current) {
                            Modifier.background(chatPanel(MaterialTheme.colorScheme.surface), RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        } else Modifier)
                        .animateContentSize()
                )
            }
        }
    }

    // 非文字 part：图片/视频/音频/文件。永远在折叠裁切区之外，保持原样可点
    var pendingDocumentPreview by remember { mutableStateOf<UIMessagePart.Document?>(null) }
    val renderMediaPart: @Composable (UIMessagePart) -> Unit = { part ->
        when (part) {
            is UIMessagePart.Video -> {
                ChatMessageMediaTile(
                    icon = HugeIcons.Video01,
                    onClick = { context.openLocalFile(part.url) },
                )
            }

            is UIMessagePart.Audio -> {
                ChatMessageMediaTile(
                    icon = HugeIcons.MusicNote03,
                    onClick = { context.openLocalFile(part.url) },
                )
            }

            is UIMessagePart.Image -> {
                val isImageLoading =
                    part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                if (isImageLoading) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .shimmer(isLoading = true)
                    )
                } else {
                    ZoomableAsyncImage(
                        model = part.url,
                        contentDescription = null,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.large)
                            .height(72.dp)
                    )
                }
            }

            is UIMessagePart.Document -> {
                val previewable = remember(part.url, part.fileName, part.mime) {
                    localDocumentPreviewKind(part.fileName, part.mime)
                }
                ChatMessageFileChip(
                    text = part.fileName,
                    // 聊天里的 html/pdf/文本附件应用内直接看，其它格式仍交给外部应用
                    onClick = {
                        if (previewable != null) pendingDocumentPreview = part
                        else context.openLocalFile(part.url)
                    },
                    icon = {
                        when (part.mime) {
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                Icon(
                                    painter = painterResource(R.drawable.docx),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            "application/pdf" -> {
                                Icon(
                                    painter = painterResource(R.drawable.pdf),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            else -> {
                                Icon(
                                    imageVector = HugeIcons.File02,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                )
            }

            else -> {
                // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
            }
        }
    }

    renderUnits.fastForEach { unit ->
        when (unit) {
            is MessageRenderUnit.Thinking -> {
                val block = unit.block
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    // 等待审批的工具不能折叠起来，否则用户看不到审批按钮，这一轮会一直卡在等待审批
                    val firstPendingIndex = block.steps.indexOfFirst { it is ThinkingStep.ToolStep && it.tool.isPending }
                    val collapsedVisibleCount = if (firstPendingIndex == -1) {
                        COLLAPSED_VISIBLE_STEPS
                    } else {
                        maxOf(COLLAPSED_VISIBLE_STEPS, block.steps.size - firstPendingIndex)
                    }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps,
                        collapsedVisibleCount = collapsedVisibleCount,
                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                        cardColors = CardDefaults.cardColors(
                            containerColor = chatPanel(
                                MaterialTheme.colorScheme.surfaceContainerHigh
                                    .copy(alpha = settings.displaySetting.bubbleOpacity),
                            ),
                        ),
                    ) { step ->
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        assistant = assistant,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                    )
                                }
                            }

                            is ThinkingStep.ServerToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageServerToolStep(tool = step.tool)
                                }
                            }

                            is ThinkingStep.NoticeStep -> ChatMessageNoticeStep(notice = step.notice)
                        }
                    }
                }
            }

            is MessageRenderUnit.Chart -> key(unit.block.index) {
                val spec = remember(unit.block.tool.input) { ChartSpec.fromJson(unit.block.tool.inputAsJson()) }
                spec?.let { ChartCard(spec = it) }
            }

            is MessageRenderUnit.Single -> key(unit.index) {
                // Markdown 由 BetterText 渲染时要用它自己的选择容器；图片/文件不需要
                if (unit.part is UIMessagePart.Text) {
                    SelectableContent { renderTextPart(unit.part as UIMessagePart.Text, unit.index) }
                } else {
                    renderMediaPart(unit.part)
                }
            }

            is MessageRenderUnit.TextRun -> key(unit.firstIndex) {
                CollapsibleMessageText(
                    messageId = messageId,
                    textParts = unit.texts,
                    isAssistant = role != MessageRole.USER,
                    // 流式生成中不折（也不显示按钮），等这一轮结束再判断
                    streaming = loading,
                    enabled = collapseLongMessages,
                    collapsedByDefault = settings.displaySetting.collapseLongMessagesByDefault,
                    fadeColor = collapseFadeColor,
                ) {
                    unit.entries.fastForEach { entry ->
                        SelectableContent {
                            renderTextPart(entry.part, entry.index)
                        }
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        ChatMessageCitations(annotations = annotations)
    }

    // 文档附件的应用内预览（html/pdf/文本），点开才读文件
    pendingDocumentPreview?.let { document ->
        LocalDocumentPreviewDialog(
            url = document.url,
            fileName = document.fileName,
            mime = document.mime,
            onDismiss = { pendingDocumentPreview = null },
            onOpenExternally = {
                pendingDocumentPreview = null
                runCatching { context.openLocalFile(document.url) }
            },
        )
    }
}

private val BubbleCorner = 24.dp
private val BubbleTailCorner = 6.dp
private val BubblePressedCorner = 12.dp

/**
 * 消息气泡：大圆角，靠头像一侧的顶角收紧；可点击时按下其余圆角也跟着收紧
 */
@Composable
private fun ChatMessageBubble(
    role: MessageRole,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    restCorner: Dp = BubbleCorner,
    tailCorner: Dp = BubbleTailCorner,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressProgress by animatePressProgress(interactionSource)
    val corner = lerp(restCorner, BubblePressedCorner, pressProgress)
    val shape = if (role == MessageRole.USER) {
        RoundedCornerShape(topStart = corner, topEnd = tailCorner, bottomEnd = corner, bottomStart = corner)
    } else {
        RoundedCornerShape(topStart = tailCorner, topEnd = corner, bottomEnd = corner, bottomStart = corner)
    }
    val contentColor = contentColorFor(color.copy(alpha = 1f))
    Box(
        modifier = modifier
            .clipDrawing(shape)
            .background(color)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick)
                } else {
                    Modifier
                },
            )
            // MarkdownBlock 自带 4dp 的水平内边距
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * 只裁剪绘制内容，不像 [clip] 那样把形状设成图层轮廓。
 *
 * 四个角半径不同时图层轮廓是 Path，Compose 判断触摸点是否落在轮廓内时用 Path 求交，
 * 坐标超过 8192px 后 float 精度不够，求交结果为空，长消息下半部分（例如展开的长代码块）就收不到触摸了。
 */
private fun Modifier.clipDrawing(shape: Shape): Modifier = drawWithCache {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    onDrawWithContent {
        clipPath(path) { this@onDrawWithContent.drawContent() }
    }
}

@Composable
private fun ChatMessageCitations(annotations: List<UIMessageAnnotation>) {
    var expand by remember { mutableStateOf(false) }
    val urls = remember(annotations) {
        annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>().map { it.url }
    }
    Column(
        modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChatMessageFileChip(
            text = stringResource(R.string.citations_count, annotations.size),
            onClick = { expand = !expand },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { FaviconRow(urls = urls, size = 18.dp) },
        )
        if (expand) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.FileCitation -> Text(annotation.title.ifBlank { annotation.fileId })
                                is UIMessageAnnotation.ContainerFileCitation -> Text(annotation.title.ifBlank { annotation.fileId })
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 渲染单元：把相邻的 Text part 聚成一条 [TextRun]，这样一个折叠容器就能包住多段正文，
 * 而图片/视频/文件/图表始终各自独立，不会被裁切或挡住。
 */
private sealed interface MessageRenderUnit {
    data class Thinking(val block: MessagePartBlock.ThinkingBlock) : MessageRenderUnit
    data class Chart(val block: MessagePartBlock.ChartBlock) : MessageRenderUnit
    data class Single(val index: Int, val part: UIMessagePart) : MessageRenderUnit

    data class TextRun(val entries: List<IndexedTextPart>) : MessageRenderUnit {
        val texts: List<String> get() = entries.map { it.part.text }
        val firstIndex: Int get() = entries.first().index
    }
}

private data class IndexedTextPart(val index: Int, val part: UIMessagePart.Text)

private fun List<MessagePartBlock>.toMessageRenderUnits(): List<MessageRenderUnit> {
    val units = mutableListOf<MessageRenderUnit>()
    var textRun = mutableListOf<IndexedTextPart>()

    fun flushTextRun() {
        if (textRun.isNotEmpty()) {
            units += MessageRenderUnit.TextRun(textRun.toList())
            textRun = mutableListOf()
        }
    }

    fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                flushTextRun()
                units += MessageRenderUnit.Thinking(block)
            }

            is MessagePartBlock.ChartBlock -> {
                flushTextRun()
                units += MessageRenderUnit.Chart(block)
            }

            is MessagePartBlock.ContentBlock -> {
                val part = block.part
                if (part is UIMessagePart.Text) {
                    textRun += IndexedTextPart(block.index, part)
                } else {
                    flushTextRun()
                    units += MessageRenderUnit.Single(block.index, part)
                }
            }
        }
    }
    flushTextRun()
    return units
}
