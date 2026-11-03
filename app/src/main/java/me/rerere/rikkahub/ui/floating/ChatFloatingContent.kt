package me.rerere.rikkahub.ui.floating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Stop
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.ASK_USER_TOOL_NAME
import me.rerere.rikkahub.data.companion.CompanionAiState
import me.rerere.rikkahub.data.companion.CompanionInteraction
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import me.rerere.rikkahub.ui.components.message.tools.ToolApprovalPreview
import me.rerere.rikkahub.ui.components.message.tools.effectiveTarget
import me.rerere.rikkahub.data.model.GenerationPhase
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ui.drawAppLogo
import me.rerere.rikkahub.ui.components.ui.logoMorphShape
import me.rerere.rikkahub.ui.components.ui.rememberAppLogoLayers
import me.rerere.rikkahub.ui.components.richtext.MaterialMarkdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.uuid.Uuid

// 和 Material3 LoadingIndicator 的参数一致；放宽 visibilityThreshold 让低阻尼弹簧在间隔内结束
private val MorphSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 200f, visibilityThreshold = 0.1f)
private const val MORPH_INTERVAL_MILLIS = 650L
private const val SPIN_DURATION_MILLIS = 4666

/** 悬浮窗展示的会话状态 */
data class FloatingChatUiState(
    val conversationId: Uuid,
    val title: String,
    val generating: Boolean,
    /** 网络重试等临时提示 */
    val processingStatus: String?,
    /** 本轮回复在做什么 */
    val phase: GenerationPhase,
    /** 本轮是否已经有回复 */
    val hasReply: Boolean,
    /** 本轮回复的正文（Markdown），还没写正文时是思考内容 */
    val preview: String,
    val previewIsReasoning: Boolean,
    val pendingTools: List<UIMessagePart.Tool>,
    val queuedCount: Int,
    val queuePaused: Boolean,
    /** 这一轮出现的错误 */
    val error: ChatError?,
    /** 本轮生成开始的时间（毫秒），用来给自动互动生成不会重复的事件键 */
    val generationStartedAt: Long? = null,
) {
    val failed: Boolean get() = !generating && error != null
}

/**
 * 收起时的小图标。
 *
 * [companion] 是设置里选的角色：默认（[FloatingCompanionSettings] 原样）还是原来的应用 logo 气泡，
 * 行为、动画、状态徽标都和过去一致；选了猫/小龙/自定义图片时交给 [FloatingCompanionBubble]，
 * 那里有眨眼、点击抬手、粒子和短台词。调用方不传 [companion] 时不会改变现有表现。
 */
@Composable
fun ChatFloatingBubble(
    state: FloatingChatUiState,
    onClick: () -> Unit,
    companion: FloatingCompanionSettings = FloatingCompanionSettings(),
    onMinimizedChange: ((Boolean) -> Unit)? = null,
    aiState: CompanionAiState = CompanionAiState(),
    onAiTrigger: (() -> Unit)? = null,
) {
    val settings = remember(companion) { companion.normalized() }
    if (settings.effectivePreset == FloatingCompanionPreset.LOGO) {
        LogoChatFloatingBubble(state = state, onClick = onClick)
    } else {
        PortraitCompanionBubble(
            state = state,
            companion = settings,
            onClick = onClick,
            onMinimizedChange = onMinimizedChange,
            aiState = aiState,
            onAiTrigger = onAiTrigger,
        )
    }
}

/** 原来的应用 logo 气泡：生成中轮廓不停变形，结束后在角上标出结果 */
@Composable
private fun LogoChatFloatingBubble(state: FloatingChatUiState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val logoLayers = rememberAppLogoLayers()
    val shapePosition = remember { Animatable(0f) }
    val spin = remember { Animatable(0f) }
    // 收起到面板时窗口隐藏，帧还在跑，停下动画
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val morphing = state.generating && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // 节奏照搬 LoadingIndicator：固定间隔变一个形状，弹簧和计时同时跑，另外整体匀速自转
    LaunchedEffect(morphing) {
        if (!morphing) {
            // 停在一个完整的形状上
            shapePosition.animateTo(ceil(shapePosition.value), MorphSpring)
            return@LaunchedEffect
        }
        launch {
            spin.animateTo(
                targetValue = spin.value + 360f,
                animationSpec = infiniteRepeatable(tween(SPIN_DURATION_MILLIS, easing = LinearEasing)),
            )
        }
        while (true) {
            val morph = launch { shapePosition.animateTo(floor(shapePosition.value) + 1f, MorphSpring) }
            delay(MORPH_INTERVAL_MILLIS)
            morph.join()
        }
    }

    val logoShape = logoMorphShape(shapePosition.value, extraRotation = spin.value)

    // 不用 Surface：它会吃掉触摸事件，拖动交给 FloatingX 处理
    Box(modifier = Modifier.padding(6.dp)) {
        Box(
            modifier = Modifier
                .size(52.dp)
                // 不能用 elevation 阴影：轮廓是凹多边形，每帧都要重新三角剖分，变形时会把 RenderThread 卡住几十秒
                .dropShadow(logoShape, Shadow(radius = 6.dp, offset = DpOffset(0.dp, 2.dp), alpha = 0.3f))
                .clip(logoShape)
                .clickable(onClick = onClick)
                .drawAppLogo(logoLayers)
        )
        when {
            state.generating -> {}
            state.error != null -> StatusBadge(HugeIcons.Alert01, colors.error, colors.onError)
            state.pendingTools.isNotEmpty() -> StatusBadge(HugeIcons.Wrench01, colors.error, colors.onError)
            state.hasReply -> StatusBadge(HugeIcons.Tick01, colors.primary, colors.onPrimary)
        }
    }
}

@Composable
private fun BoxScope.StatusBadge(icon: ImageVector, containerColor: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .size(20.dp)
            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .padding(2.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(10.dp))
    }
}

/** 面板里几个固定/滚动区域的测试标签：仪器测试按这些断言"输入栏没有被状态卡挤出去"。 */
object FloatingPanelTestTags {
    const val PANEL = "floating-panel"
    const val SCROLL = "floating-panel-scroll"
    const val REPLY_BAR = "floating-panel-reply-bar"
    const val REPLY_INPUT = "floating-panel-reply-input"
    const val REPLY_SEND = "floating-panel-reply-send"
    const val REPLY_STOP = "floating-panel-reply-stop"
}

/** 键盘弹出时面板高度再收一档（面板贴顶，收完底部仍在键盘上方）。 */
private const val KEYBOARD_SAFE_FRACTION = 0.6f
private val KeyboardEditingMinHeight = 220.dp

/** 展开后的面板：看进度、审批工具、停止生成和快速回复。[maxHeight] 是窗口能占的高度 */
@Composable
fun ChatFloatingPanel(
    state: FloatingChatUiState,
    maxHeight: Dp,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
    onOpenConversation: () -> Unit,
    onToolApproval: (toolCallId: String, approved: Boolean) -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onResumeQueue: () -> Unit,
    previewExpanded: Boolean = false,
    onPreviewExpandedChange: ((Boolean) -> Unit)? = null,
    companionState: CompanionAiState = CompanionAiState(),
    companionInteractions: List<CompanionInteraction> = emptyList(),
    companionExpanded: Boolean = false,
    onCompanionExpandedChange: ((Boolean) -> Unit)? = null,
    onCompanionRefresh: () -> Unit = {},
    onCompanionRetry: () -> Unit = {},
    onCompanionStop: () -> Unit = {},
    onCompanionSend: (String) -> Boolean = { false },
    onCompanionOpenSettings: () -> Unit = {},
    onCompanionEnableAi: (() -> Unit)? = null,
) {
    var localPreviewExpanded by rememberSaveable(state.conversationId.toString()) { mutableStateOf(previewExpanded) }
    var localCompanionExpanded by rememberSaveable(state.conversationId.toString()) { mutableStateOf(companionExpanded) }
    val showPreview = if(onPreviewExpandedChange == null) localPreviewExpanded else previewExpanded
    val showCompanion = if(onCompanionExpandedChange == null) localCompanionExpanded else companionExpanded
    // 面板里的输入框在用键盘（就一句小助手输入、一封主聊天回复都一样）：面板贴屏幕顶部，
    // 高度再收一档，输入栏和停止键才不会被键盘盖住；小屏大字下靠上面的滚动区滚动，功能都还在
    var editing by remember { mutableStateOf(false) }
    val contentMaxHeight = if (editing) {
        (maxHeight * KEYBOARD_SAFE_FRACTION)
            .coerceAtLeast(KeyboardEditingMinHeight)
            .coerceAtMost(maxHeight)
    } else {
        maxHeight
    }
    val scrollState = rememberScrollState()
    val pendingToolIds = state.pendingTools.map { it.toolCallId }
    // 新的审批出现时滚到审批卡：批准/拒绝按钮不用用户自己在滚动区里找
    LaunchedEffect(pendingToolIds) {
        if (pendingToolIds.isEmpty()) return@LaunchedEffect
        // 等这一层的测量结果出来再滚，第一帧的 maxValue 还是 0
        snapshotFlow { scrollState.maxValue }.first { it > 0 }
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    // Compact by default. Preview and pending approvals can scroll; the reply bar stays reachable.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = contentMaxHeight)
            .testTag(FloatingPanelTestTags.PANEL),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PanelHeader(
                    state = state,
                    onOpenConversation = onOpenConversation,
                    onCollapse = onCollapse,
                    onDismiss = onDismiss,
                )
                // 小助手的真实状态卡和主预览/审批/队列共用这一个受限滚动区：
                // 状态卡展开只是让这里滚动，主聊天回复栏（发送/停止）不会被顶出面板
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .testTag(FloatingPanelTestTags.SCROLL),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CompanionAiStatusCard(
                        state = companionState,
                        interactions = companionInteractions,
                        expanded = showCompanion,
                        onExpandedChange = { expanded ->
                            if (onCompanionExpandedChange != null) onCompanionExpandedChange(expanded)
                            else localCompanionExpanded = expanded
                        },
                        onRefresh = onCompanionRefresh,
                        onRetry = onCompanionRetry,
                        onStop = onCompanionStop,
                        onSend = onCompanionSend,
                        onOpenSettings = onCompanionOpenSettings,
                        onEnableAi = onCompanionEnableAi,
                        onEditingChange = { editing = it },
                    )
                    if (state.preview.isNotEmpty()) {
                        TextButton(onClick = {
                            if(onPreviewExpandedChange != null) onPreviewExpandedChange(!showPreview)
                            else localPreviewExpanded = !showPreview
                        },modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(if(showPreview) R.string.portrait_panel_collapse else R.string.portrait_panel_expand))
                        }
                    }
                    if (showPreview && state.preview.isNotEmpty()) MessagePreview(
                        text = state.preview,
                        isReasoning = state.previewIsReasoning,
                        generating = state.generating,
                    )
                    state.pendingTools.forEach { tool ->
                        PendingToolCard(
                            tool = tool,
                            onApproval = { approved -> onToolApproval(tool.toolCallId, approved) },
                            onOpenConversation = onOpenConversation,
                        )
                    }
                    if (state.queuedCount > 0) {
                        QueueRow(count = state.queuedCount, paused = state.queuePaused, onResume = onResumeQueue)
                    }
                }
                // 主聊天的输入栏固定在最外层：滚动区再怎么长，发送和停止都在
                ReplyBar(
                    conversationId = state.conversationId,
                    generating = state.generating,
                    onSend = onSend,
                    onStop = onStop,
                    onEditingChange = { editing = it },
                )
            }
        }
    }
}

@Composable
private fun PanelHeader(
    state: FloatingChatUiState,
    onOpenConversation: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.generating) {
            ContainedLoadingIndicator(modifier = Modifier.size(40.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (state.failed) colors.errorContainer else colors.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = state.statusIcon(),
                    contentDescription = null,
                    tint = if (state.failed) colors.onErrorContainer else colors.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.statusText(),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.failed) colors.error else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenConversation) {
            Icon(HugeIcons.Fullscreen, contentDescription = stringResource(R.string.floating_chat_open_conversation))
        }
        IconButton(onClick = onCollapse) {
            Icon(HugeIcons.Remove01, contentDescription = stringResource(R.string.floating_chat_collapse))
        }
        IconButton(onClick = onDismiss) {
            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.floating_chat_close))
        }
    }
}

// 用不带应用功能的 MaterialMarkdown：应用里的 MarkdownBlock 依赖导航、Activity 等悬浮窗里没有的东西
@Composable
private fun MessagePreview(text: String, isReasoning: Boolean, generating: Boolean) {
    val scrollState = rememberScrollState()
    // 生成中跟随最新内容。Markdown 在后台解析，内容变高晚于文本变化，按可滚动距离跟随
    LaunchedEffect(generating) {
        if (generating) snapshotFlow { scrollState.maxValue }.collect { scrollState.scrollTo(it) }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (isReasoning) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        MaterialMarkdown(
            content = text,
            style = MaterialTheme.typography.bodyMedium,
            streaming = generating,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(scrollState)
                .padding(12.dp),
        )
    }
}

@Composable
private fun PendingToolCard(
    tool: UIMessagePart.Tool,
    onApproval: (approved: Boolean) -> Unit,
    onOpenConversation: () -> Unit,
) {
    val isAskUser = tool.toolName == ASK_USER_TOOL_NAME
    // 网关代理调用（tool_call）要显示被代理的真实工具名与内层参数：
    // 否则用户在审批弹窗里只看到 "tool_call" 和一层嵌套 JSON，根本不知道自己批准了什么
    val proxyTarget = remember(tool.toolName, tool.input) { tool.effectiveTarget() }
    val targetName = (proxyTarget?.first ?: tool.toolName).substringAfterLast("__")
    val inputPreview = (proxyTarget?.second?.toString() ?: tool.input)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isAskUser) {
                    stringResource(R.string.floating_chat_ask_user)
                } else {
                    stringResource(R.string.floating_chat_tool_request, targetName)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (!isAskUser && inputPreview.isNotBlank()) {
                // 完整入参：批量工具最后一个 write/shell 常常排在第 300 个字符之后，
                // 截断加 Ellipsis 会让"批准"按钮指向用户根本没看到的那部分载荷
                ToolApprovalPreview(
                    text = inputPreview,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (isAskUser) {
                    // 问答有多个问题和选项，在应用里回答
                    Button(onClick = onOpenConversation) { Text(stringResource(R.string.floating_chat_answer)) }
                } else {
                    OutlinedButton(onClick = { onApproval(false) }) { Text(stringResource(R.string.chat_message_tool_deny)) }
                    Button(onClick = { onApproval(true) }) { Text(stringResource(R.string.chat_message_tool_approve)) }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(count: Int, paused: Boolean, onResume: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                if (paused) R.string.chat_page_queue_paused_count else R.string.chat_page_queue_pending_count,
                count,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (paused) {
            TextButton(onClick = onResume) { Text(stringResource(R.string.chat_page_queue_resume)) }
        }
    }
}

@Composable
private fun ReplyBar(
    conversationId: Uuid,
    generating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onEditingChange: ((Boolean) -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    var text by rememberSaveable(conversationId) { mutableStateOf("") }

    fun send() {
        val content = text.trim()
        if (content.isEmpty()) return
        onSend(content)
        text = ""
        focusManager.clearFocus()
    }

    val focusModifier = if (onEditingChange != null) {
        Modifier.onFocusChanged { onEditingChange(it.isFocused) }
    } else {
        Modifier
    }

    Row(
        modifier = Modifier.fillMaxWidth().testTag(FloatingPanelTestTags.REPLY_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .testTag(FloatingPanelTestTags.REPLY_INPUT)
                .then(focusModifier),
            // 生成中发送的消息会排队，等这一轮结束后再发
            placeholder = {
                Text(
                    stringResource(
                        if (generating) R.string.floating_chat_reply_queued_placeholder else R.string.floating_chat_reply_placeholder
                    )
                )
            },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        if (generating) {
            FilledTonalIconButton(
                onClick = onStop,
                modifier = Modifier.testTag(FloatingPanelTestTags.REPLY_STOP),
            ) {
                Icon(HugeIcons.Stop, contentDescription = stringResource(R.string.stop))
            }
        }
        FilledIconButton(
            onClick = ::send,
            enabled = text.isNotBlank(),
            modifier = Modifier.testTag(FloatingPanelTestTags.REPLY_SEND),
        ) {
            Icon(HugeIcons.ArrowUp02, contentDescription = stringResource(R.string.send))
        }
    }
}

private fun FloatingChatUiState.statusIcon(): ImageVector = when {
    error != null -> HugeIcons.Alert01
    pendingTools.isNotEmpty() -> HugeIcons.Wrench01
    hasReply -> HugeIcons.Tick01
    else -> HugeIcons.Sparkles
}

@Composable
private fun FloatingChatUiState.statusText(): String {
    if (!generating) {
        return when {
            error != null -> error.title ?: error.error.message ?: stringResource(R.string.floating_chat_status_failed)
            pendingTools.any { it.toolName == ASK_USER_TOOL_NAME } -> stringResource(R.string.floating_chat_status_awaiting_answer)
            pendingTools.isNotEmpty() -> stringResource(R.string.floating_chat_status_awaiting_approval)
            hasReply -> stringResource(R.string.floating_chat_status_done)
            else -> stringResource(R.string.floating_chat_status_idle)
        }
    }
    // 网络重试等提示优先
    processingStatus?.let { return it }
    return when (phase) {
        is GenerationPhase.RunningTool -> stringResource(R.string.notification_live_update_tool, phase.toolName)
        is GenerationPhase.Thinking -> stringResource(R.string.notification_live_update_thinking)
        is GenerationPhase.Writing -> stringResource(R.string.notification_live_update_writing)
        GenerationPhase.Waiting -> stringResource(R.string.notification_live_update_title)
    }
}
