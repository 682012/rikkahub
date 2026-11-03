package me.rerere.rikkahub.ui.floating

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.petterp.floatingx.compose.compose
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import com.petterp.floatingx.core.animation.FxAnimations
import com.petterp.floatingx.core.gesture.FxDrag
import com.petterp.floatingx.core.gesture.FxGesture
import com.petterp.floatingx.core.layout.FxAdsorb
import com.petterp.floatingx.core.layout.FxGravity
import com.petterp.floatingx.core.storage.FxSpStorage
import com.petterp.floatingx.system.permission.FxPermission
import com.petterp.floatingx.system.permission.FxPermissionStrategy
import com.petterp.floatingx.system.systemHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.conversationIntent
import me.rerere.rikkahub.data.companion.CompanionAiController
import me.rerere.rikkahub.data.companion.CompanionDelta
import me.rerere.rikkahub.data.companion.CompanionReplySource
import me.rerere.rikkahub.data.companion.CompanionRequest
import me.rerere.rikkahub.data.companion.CompanionTrigger
import me.rerere.rikkahub.data.companion.CompanionTriggerKind
import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.GenerationPhase
import me.rerere.rikkahub.data.model.generationPhase
import me.rerere.rikkahub.data.model.pendingToolCalls
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatFloatingTracker
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.InboxState
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import kotlin.uuid.Uuid

private const val TAG = "ChatFloatingWindows"
private const val BUBBLE_TAG = "chat_floating_bubble"
private const val PANEL_TAG = "chat_floating_panel"
private const val PANEL_TOP_OFFSET_DP = 8f

/**
 * 把 [ChatFloatingTracker] 的结果显示成系统悬浮窗：收起时是贴边的小图标，展开是固定在顶部的面板。
 * 两个窗口共用这里汇总的一份 [FloatingChatUiState]。FloatingX 只能在主线程操作，协程都跑在 AppScope 上。
 */
class ChatFloatingWindows(
    private val context: Application,
    private val appScope: AppScope,
    private val tracker: ChatFloatingTracker,
    lazyChatService: Lazy<ChatService>,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    companionSource: Lazy<CompanionReplySource>,
) {
    private val chatService by lazyChatService
    private var bubble: FxControl? = null
    private var panel: FxControl? = null

    // 小助手的状态机：只在悬浮窗可见时接受触发，离开窗口会取消在途请求，本身不起定时器
    private val companionController = CompanionAiController(
        scope = appScope,
        source = object : CompanionReplySource {
            override suspend fun describeModel(conversationId: Uuid?) =
                companionSource.value.describeModel(conversationId)

            override suspend fun generate(
                conversationId: Uuid?,
                request: CompanionRequest,
                onDelta: (CompanionDelta) -> Unit,
            ) = companionSource.value.generate(conversationId, request, onDelta)
        },
        conversationId = { tracker.window.value?.conversationId },
    )
    private val companionEvents = CompanionEventDetector()

    /**
     * 悬浮窗权限的当前值。没有权限时窗口根本装不出来（[render] 会直接取消），
     * 所以小助手的"可见性"必须跟着权限走：否则会出现"有请求在花钱、屏幕上什么都没有"。
     */
    private val overlayGranted = MutableStateFlow(FxPermission.isGranted(context))

    // 悬浮窗显示期间一直汇总，气泡和面板切换时直接拿到当前状态，不会先空一帧
    private val uiState: StateFlow<FloatingChatUiState?> = tracker.window
        .map { it?.conversationId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else chatState(id) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    // 悬浮窗的 Context 不是 Activity，默认的 UriHandler 打开链接时缺少 NEW_TASK 会崩溃
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "openUri: no activity for $uri", e)
            }
        }
    }

    init {
        appScope.launch {
            tracker.window.map { it?.expanded }.distinctUntilChanged().collect(::render)
        }
        // 窗口有任何变化就重新读一遍权限：在系统设置里收回权限后，下一次事件不再发请求
        appScope.launch {
            tracker.window.collect { refreshOverlayPermission() }
        }
        // 窗口出现/关掉 + 悬浮窗权限共同决定小助手能不能说话：离开窗口或没有权限都不发请求，在途的立刻取消
        appScope.launch {
            combine(tracker.window, overlayGranted) { window, granted -> window != null && granted }
                .distinctUntilChanged()
                .collect { visible ->
                    companionController.setVisible(visible)
                    if (visible) companionController.refreshModel()
                }
        }
        // 模型相关的设置变了（换模式/换模型/关掉 AI）：先停在途请求再刷新，
        // 状态卡里的模型和下一句真正用的模型才是同一个
        appScope.launch {
            settingsStore.settingsFlow
                .map { it.displaySetting.floatingCompanion.toModelKey() }
                .distinctUntilChanged()
                .collect {
                    restartCompanionForModelChange()
                    refreshOverlayPermission()
                }
        }
        // 跟随会话：换了会话就等于换了模型，同样先停再刷新（窗口没显示时不动控制器）
        appScope.launch {
            tracker.window.map { it?.conversationId }.distinctUntilChanged().collect {
                if (companionController.isVisible()) restartCompanionForModelChange()
            }
        }
        // 自动互动由真实的会话状态变化驱动：生成开始/结束、出错、等审批各算一个事件，
        // 去重冷却和每小时预算在 CompanionTriggerPolicy 里，同一次事件不会重复花钱
        appScope.launch {
            var previous: FloatingChatUiState? = null
            uiState.collect { current ->
                if (current == null) {
                    previous = null
                    return@collect
                }
                val events = companionEvents.detect(previous, current)
                previous = current
                if (events.isEmpty()) return@collect
                val companion = settingsStore.settingsFlow.value.displaySetting.floatingCompanion
                if (!companion.aiEnabled || !companion.autoEventsEnabled) return@collect
                // 每个事件提交前都重新确认窗口和权限：没有 UI 就不花钱
                events.forEach { submitCompanion(it, companion) }
            }
        }
    }

    /**
     * 模型设置或跟随会话变了：先停掉还在跑的请求（它用的是旧模型，结果不该写到新设置上），
     * 再让控制器按新设置重读一次模型。不在跑的时候只刷新，不伪造一次取消。
     */
    private fun restartCompanionForModelChange() {
        if (companionController.state.value.busy) companionController.stop()
        companionController.refreshModel()
    }

    /** 读一次悬浮窗权限，并让可见性跟着更新。返回当前值。 */
    private fun refreshOverlayPermission(): Boolean {
        val granted = FxPermission.isGranted(context)
        if (overlayGranted.value != granted) overlayGranted.value = granted
        return granted
    }

    /**
     * 没有悬浮窗权限或没有窗口时：把可见性置回 false（在途请求一并取消），返回 false。
     * 这样策略层会把任何提交记成 NOT_VISIBLE 挡下，不会出现"屏幕上看不见、账单在走"。
     */
    private fun companionCanSpeak(): Boolean {
        if (refreshOverlayPermission() && tracker.window.value != null) return true
        companionController.setVisible(false)
        return false
    }

    /** 面板里说一句/点一下角色都走这里；返回值给面板输入框用：false 时不丢用户草稿。 */
    private fun submitCompanion(
        trigger: CompanionTrigger,
        companion: FloatingCompanionSettings =
            settingsStore.settingsFlow.value.displaySetting.floatingCompanion,
    ): Boolean {
        companionCanSpeak()
        return companionController.submit(trigger, companion)
    }

    private fun chatState(id: Uuid): Flow<FloatingChatUiState> {
        // 只显示这一轮的错误。开始时间要用启动任务时记下的，等这里观察到任务再取会晚于快速失败时报的错
        val started = combine(chatService.errors, chatService.getGenerationStartedAtFlow(id)) { errors, since ->
            since to since?.let { began -> errors.lastOrNull { it.conversationId == id && it.timestamp >= began } }
        }
        val conversation = combine(chatService.getConversationFlow(id), chatService.getReplyMessageIdFlow(id), ::Pair)
        return combine(
            conversation,
            chatService.getGenerationJobStateFlow(id).map { it != null },
            chatService.getProcessingStatusFlow(id),
            chatService.getInboxFlow(id),
            started,
        ) { (conversation, replyMessageId), generating, processingStatus, queue, (startedAt, error) ->
            buildFloatingChatUiState(
                conversation = conversation,
                replyMessageId = replyMessageId,
                generating = generating,
                processingStatus = processingStatus,
                queue = queue,
                error = error,
                generationStartedAt = startedAt,
            )
        }.flowOn(Dispatchers.Default)
    }

    private fun render(expanded: Boolean?) {
        // 后台弹不出权限申请页，没有权限就不显示
        if (expanded == null || !refreshOverlayPermission()) {
            bubble?.cancel()
            panel?.cancel()
            bubble = null
            panel = null
            return
        }
        val bubble = bubble ?: installBubble().also { bubble = it }
        if (expanded) {
            bubble.hide()
            (panel ?: installPanel().also { panel = it }).show()
        } else {
            panel?.hide()
            bubble.show()
        }
    }

    private fun installBubble(): FxControl = FloatingX.install(BUBBLE_TAG) {
        compose {
            WindowContent { state ->
                val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
                val companionAi by companionController.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
                ChatFloatingBubble(state = state, onClick = tracker::expand, companion = settings.displaySetting.floatingCompanion,
                    aiState = companionAi,
                    onAiTrigger = {
                        // 点击本身就是授权：有模型、并且窗口真的画得出来，才真的生成一句；
                        // 没权限/没窗口时什么都不发，状态里写"悬浮窗没显示"
                        submitCompanion(CompanionTrigger(CompanionTriggerKind.TAP))
                    },
                    onMinimizedChange = { minimized -> appScope.launch {
                        settingsStore.update { latest -> latest.copy(displaySetting = latest.displaySetting.copy(
                            floatingCompanion = latest.displaySetting.floatingCompanion.copy(minimized = minimized))) }
                    } },
                )
            }
        }
        anchor(FxGravity.CENTER_END, dy = -dp(96f))
        margin(left = dp(8f), right = dp(8f))
        adsorb(FxAdsorb.horizontal())
        persist(FxSpStorage(context))
        animation(FxAnimations.scale())
        systemHost(context) { permission(FxPermissionStrategy.skip()) }
    }

    private fun installPanel(): FxControl = FloatingX.install(PANEL_TAG) {
        compose {
            WindowContent { state ->
                val conversationId = state.conversationId
                val currentSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
                val companionState by companionController.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
                val companionInteractions by companionController.interactions.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
                ChatFloatingPanel(
                    state = state,
                    maxHeight = rememberPanelMaxHeight(),
                    onCollapse = tracker::collapse,
                    onDismiss = tracker::dismiss,
                    onOpenConversation = { openConversation(conversationId) },
                    onToolApproval = { toolCallId, approved ->
                        chatService.handleToolApproval(conversationId, toolCallId, approved)
                    },
                    onStop = { appScope.launch { chatService.stopGeneration(conversationId) } },
                    onSend = { text -> chatService.sendMessage(conversationId, listOf(UIMessagePart.Text(text))) },
                    onResumeQueue = { chatService.resumeInbox(conversationId) },
                    previewExpanded = currentSettings.displaySetting.floatingCompanion.panelPreviewExpanded,
                    onPreviewExpandedChange = { expanded -> appScope.launch {
                        settingsStore.update { latest -> latest.copy(displaySetting = latest.displaySetting.copy(
                            floatingCompanion = latest.displaySetting.floatingCompanion.copy(panelPreviewExpanded = expanded))) }
                    } },
                    companionState = companionState,
                    companionInteractions = companionInteractions,
                    companionExpanded = currentSettings.displaySetting.floatingCompanion.companionStatusExpanded,
                    onCompanionExpandedChange = { expanded -> appScope.launch {
                        settingsStore.update { latest -> latest.copy(displaySetting = latest.displaySetting.copy(
                            floatingCompanion = latest.displaySetting.floatingCompanion.copy(companionStatusExpanded = expanded))) }
                    } },
                    onCompanionRefresh = companionController::refreshModel,
                    onCompanionRetry = {
                        // 再说一句同样要先确认窗口还在：没权限就不发请求
                        companionCanSpeak()
                        companionController.repeatLast()
                    },
                    onCompanionStop = companionController::stop,
                    onCompanionSend = { text ->
                        // submit 返回 false（正忙/冷却/窗口不可见）时面板保留用户原话
                        submitCompanion(CompanionTrigger(CompanionTriggerKind.INPUT, detail = text))
                    },
                    onCompanionOpenSettings = ::openAppSettings,
                    onCompanionEnableAi = {
                        // 一次点击就是授权：先停掉在跑的旧请求，再把模式落盘并刷新模型。
                        // 之后能不能真的发（有会话模型、有权限）由引擎和状态卡照实显示，界面不假装成功
                        appScope.launch {
                            if (companionController.state.value.busy) companionController.stop()
                            settingsStore.update { latest ->
                                latest.copy(
                                    displaySetting = latest.displaySetting.copy(
                                        floatingCompanion = latest.displaySetting.floatingCompanion.copy(
                                            aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION,
                                        ),
                                    ),
                                )
                            }
                            companionController.refreshModel()
                        }
                    },
                )
            }
        }
        anchor(FxGravity.TOP_CENTER, dy = dp(PANEL_TOP_OFFSET_DP))
        gesture(FxGesture(click = false, longPress = false, drag = FxDrag.DISABLED))
        animation(FxAnimations.fade())
        systemHost(context) {
            permission(FxPermissionStrategy.skip())
            layoutParams {
                // 面板里有输入框，窗口要能获得焦点才能弹键盘；收起时窗口是 GONE，不会抢走其他应用的焦点
                it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                // wrap_content 的窗口会先按对话框宽度（约 320dp）测量，面板会被压窄
                it.width = WindowManager.LayoutParams.MATCH_PARENT
            }
            onBackPressed {
                tracker.collapse()
                true
            }
        }
    }

    // 窗口隐藏时生命周期退回 STARTED，只在显示时订阅，流式输出不会让隐藏的窗口跟着重组
    @Composable
    private fun WindowContent(content: @Composable (FloatingChatUiState) -> Unit) {
        val state by uiState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
        val conversationId = state?.conversationId
        val conversationFlow = remember(conversationId) {
            conversationId?.let { chatService.getConversationFlow(it) } ?: flowOf(null)
        }
        val conversation by conversationFlow.collectAsStateWithLifecycle(initialValue = null, minActiveState = Lifecycle.State.RESUMED)
        val workspaceId = conversation?.let { settings.getAssistantOf(it).workspaceId?.toString() }
        RikkahubTheme {
            CompositionLocalProvider(
                LocalUriHandler provides uriHandler,
                me.rerere.rikkahub.ui.context.LocalWorkspaceId provides workspaceId,
                me.rerere.rikkahub.ui.context.LocalWorkspaceArtifactOpener provides { id, link ->
                    tracker.collapse()
                    context.startActivity(me.rerere.rikkahub.workspaceArtifactIntent(context, id, link.path, link.fragment)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            ) {
                state?.let { content(it) }
            }
        }
    }

    // 面板窗口是 wrap_content 且不受屏幕限制（FloatingX 用了 NO_LIMITS），内容多了会伸出屏幕底部，按可用高度封顶
    @Composable
    private fun rememberPanelMaxHeight(): Dp {
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        return remember(configuration, density) {
            with(density) { ((safeScreenHeight() - dp(PANEL_TOP_OFFSET_DP)) * .62f).toDp().coerceAtMost(440.dp) }
        }
    }

    // 和 FloatingX 摆放窗口用的安全区一致：R 及以上去掉屏幕级的系统栏和刘海，R 以下没有公开入口，用不含导航栏的高度
    private fun safeScreenHeight(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return context.resources.displayMetrics.heightPixels
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return metrics.bounds.height() - insets.top - insets.bottom
    }

    private fun openConversation(conversationId: Uuid) {
        // 有悬浮窗权限的应用允许从后台启动 Activity
        context.startActivity(
            conversationIntent(context, conversationId.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** "去选择模型"入口：回到应用里配置，悬浮窗里不重复造一套模型选择器。 */
    private fun openAppSettings() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "openAppSettings failed", it) }
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density
}

/** [replyMessageId] 是本轮正在写的助手消息，这一轮还没有输出时为空 */
internal fun buildFloatingChatUiState(
    conversation: Conversation,
    replyMessageId: Uuid?,
    generating: Boolean,
    processingStatus: String?,
    queue: InboxState,
    error: ChatError?,
    generationStartedAt: Long? = null,
): FloatingChatUiState {
    // 不看末尾也不看节点选中的分支：重新生成较早的消息时，后面的节点还在，用户也可能切回了旧回复
    val reply = replyMessageId?.let { id ->
        conversation.messageNodes.firstNotNullOfOrNull { node -> node.messages.firstOrNull { it.id == id } }
    }
    val text = reply?.parts?.filterIsInstance<UIMessagePart.Text>()?.lastOrNull()?.text?.trim().orEmpty()
    val reasoning = reply?.parts?.filterIsInstance<UIMessagePart.Reasoning>()?.lastOrNull()?.reasoning?.trim().orEmpty()
    return FloatingChatUiState(
        conversationId = conversation.id,
        title = conversation.title,
        generating = generating,
        processingStatus = processingStatus,
        phase = reply?.generationPhase() ?: GenerationPhase.Waiting,
        hasReply = reply != null,
        // 还没写正文时显示思考内容。按 Markdown 渲染，不能截断，切在代码块中间后面会整段错乱
        preview = text.ifEmpty { reasoning },
        previewIsReasoning = text.isEmpty(),
        // 和 ChatService 判断是否在等审批用同一个条件，否则快速回复会被审批挡住却看不到原因
        pendingTools = conversation.pendingToolCalls,
        queuedCount = queue.items.size,
        queuePaused = queue.paused,
        error = error,
        generationStartedAt = generationStartedAt,
    )
}

/** 只和小助手"用哪个模型"有关的设置：换模式、换模型、开关 AI。其它设置变了不用停请求。 */
private data class CompanionModelKey(
    val mode: CompanionModelMode,
    val modelId: Uuid?,
    val aiEnabled: Boolean,
)

private fun FloatingCompanionSettings.toModelKey(): CompanionModelKey =
    CompanionModelKey(aiModelMode, aiModelId, aiEnabled)
