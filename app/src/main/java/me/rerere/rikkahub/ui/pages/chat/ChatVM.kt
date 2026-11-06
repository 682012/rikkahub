package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.inputassist.InputAssistReviewState
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.draft.ChatDraftStore
import me.rerere.rikkahub.data.draft.ChatDraftStores
import me.rerere.rikkahub.data.draft.ChatSendDraftPolicy
import me.rerere.rikkahub.data.draft.isDraftKeyMigration
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.ChatDraft
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationRuntimeOptions
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.model.chatDraftKey
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.reasoningLevelOf
import me.rerere.rikkahub.data.model.runtime
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.CompressionState
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.web.ApiException
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

// 排队等着的输入可能等很久才轮到自己落库，所以收尾不设超时门槛：
// 超时后把草稿「退回」会让输入框里多出一份还在队列里的重复内容，宁可等真正的结果。


class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    // 主代理接 DI 时传 Koin 单例即可；默认取进程内共享实例，多个聊天页共用一份草稿缓存
    private val draftStore: ChatDraftStore = ChatDraftStores.of(context),
    // 草稿的写盘和「发送确认」都不能挂在页面 ViewModel 上：切页会取消 viewModelScope，
    // 那样用户消息已经落库了，输入框里的旧稿却永远清不掉。主代理接 Koin 的 AppScope 即可。
    private val draftScope: CoroutineScope = ChatDraftStores.appScope,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)

    /**
     * 会话内容是否已经就绪。false 时 [conversation] 里的空列表只是加载中的占位（或上一次打开
     * 留下的可见内容），页面必须把它当成单独的加载态，不能当成「聊天记录被清空」渲染。
     * 就绪后的空列表才是用户真正的空会话/已删除。
     */
    val contentReady: StateFlow<Boolean> = chatService.getConversationContentReadyFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // ---- 会话内运行时选项（m1）：只作用于当前会话，默认全关 ----

    /** 当前会话的运行时选项；还没固定配置的新会话读默认值（优化/审查/知识库都关） */
    val runtimeOptions: StateFlow<ConversationRuntimeOptions> = conversation
        .map { it.runtime }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, conversation.value.runtime)

    /** 正在把选项写回当前会话：面板据此禁用控件，避免在旧值上继续叠开关 */
    private val _runtimeUpdateBusy = MutableStateFlow(false)
    val runtimeUpdateBusy: StateFlow<Boolean> = _runtimeUpdateBusy.asStateFlow()

    /**
     * 写入当前会话的运行时选项。失败时停在原值（面板下一帧读到的还是旧配置，不会显示成已开启），
     * 并把错误挂到这个会话上，而不是静默吞掉。
     */
    fun updateRuntimeOptions(options: ConversationRuntimeOptions) {
        if (_runtimeUpdateBusy.value) return
        _runtimeUpdateBusy.value = true
        viewModelScope.launch {
            try {
                chatService.updateConversationRuntimeOptions(_conversationId, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatService.addError(
                    error = e,
                    conversationId = _conversationId,
                    title = context.getString(R.string.error_title_operation),
                )
            } finally {
                _runtimeUpdateBusy.value = false
            }
        }
    }

    /** 当前会话的输入审查状态；[InputAssistReviewState.Idle] 表示没开或已收起，别的会话读不到这一条 */
    val inputAssistReview: StateFlow<InputAssistReviewState> =
        chatService.getInputAssistReviewFlow(_conversationId)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                InputAssistReviewState.Idle,
            )

    /** 取消排队中/进行中的独立审查（那是要计费的请求） */
    fun cancelInputReview() {
        chatService.cancelInputReview(_conversationId)
    }

    /** 只收起审查卡，不影响正在跑的审查 */
    fun dismissInputReview() {
        chatService.dismissInputReview(_conversationId)
    }

    /**
     * 回读这一轮真正发给模型的优化副本。缓存是磁盘上的私有文件，所以整段切 IO：
     * 页面只拿到一个 String?，不会在主线程读盘。
     */
    suspend fun optimizedInputFor(messageId: Uuid): String? = withContext(Dispatchers.IO) {
        chatService.getOptimizedInput(conversation.value, messageId)
    }

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    val voiceSession = VoiceSessionController(viewModelScope, context::getString) {
        chatService.enqueueVoiceMessage(_conversationId, it)
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 当前会话的压缩状态：手动后台压缩和自动压缩都写同一份，离开页面再回来时从这里接着显示，
     * 不依赖某个弹窗或任务对象还活着。
     */
    val compressionState: StateFlow<CompressionState?> =
        chatService.compressionStates
            .map { states -> states[_conversationId] }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())


    override fun onCleared() {
        voiceSession.stop()
        super.onCleared()
        // 页面走了：先把最后一份输入记下来，再让草稿存储自己把没写完的落盘（不受 viewModelScope 取消影响）
        persistDraftNow()
        draftSyncJob?.cancel()
        draftStore.flushAll()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // ---- 输入草稿 ----

    /** 草稿首次恢复完成之前，聊天页不要把分享进来的内容塞进输入框，免得冲掉恢复出来的草稿 */
    private val _draftReady = MutableStateFlow(false)
    val draftReady: StateFlow<Boolean> = _draftReady.asStateFlow()

    private val draftLock = Any()
    private var draftKey: String? = null
    private var draftSyncJob: Job? = null

    // 正在等落库确认的内容：连点同一份输入时只发一次，期间新输入的别的草稿照常能发
    private val sendingDrafts = HashSet<ChatDraft>()

    /** 会话有内容就按会话存，还没开始的新会话按助手存（换助手就是另一个输入框） */
    private val draftKeyFlow: Flow<String> by lazy {
        combine(conversation, assistant) { current, currentAssistant ->
            chatDraftKey(current.id, currentAssistant.id, current.messageNodes.isNotEmpty())
        }.distinctUntilChanged()
    }

    /** Start only after the conversation and all input state fields have been initialized. */
    private fun startDraftSync() {
        draftSyncJob = viewModelScope.launch {
            yield()
            // 设置还没加载时 `assistant` 是 dummy 助手，这时的 key 是错的，先等它加载完
            withTimeoutOrNull(5_000) { settingsStore.awaitLoaded() }
            // Loading placeholders may carry the globally selected assistant, not this chat's owner.
            contentReady.first { it }
            draftKeyFlow.collectLatest { key -> syncDraft(key) }
        }
    }

    private suspend fun syncDraft(key: String) {
        val wasReady = _draftReady.value
        _draftReady.value = false
        val previousKey = synchronized(draftLock) { draftKey }
        val previousInput = inputState.snapshotDraft()
        // Commit ownership before suspending, but never carry old assistant text into a new key.
        if (previousKey != null && previousKey != key && !isDraftKeyMigration(previousKey, key)) {
            if (wasReady || previousInput.hasPersistableState) draftStore.saveNow(previousKey, previousInput)
            inputState.clearInput()
        }
        synchronized(draftLock) { draftKey = key }
        when {
            previousKey == null || previousKey == key ->
                restoreDraft(key, replaceInput = false)

            // 刚发出第一条消息：key 从助手挪到会话。输入框里剩下的字属于这个会话，
            // 直接存到新 key 上，不能在切换时被当成「上一个 key 的残留」清掉。
            isDraftKeyMigration(previousKey, key) -> {
                draftStore.save(key, inputState.snapshotDraft())
                draftStore.flush(previousKey)
                restoreDraft(key, replaceInput = false)
            }

            // 切助手（或者别的原因换了 key）：旧输入先按旧 key 写下来（刚打的字可能还停在
            // debounce 里），再读新 key。旧内容不会被写到新 key 上，也不会因为切换丢掉。
            else -> {
                draftStore.flush(previousKey)
                restoreDraft(key, replaceInput = false)
            }
        }
        // 草稿里记着的思考档位跟着输入框一起恢复：还没落库的新会话靠它记住上次的选择
        applyDraftReasoning(inputState.reasoningLevel)
        _draftReady.value = true
        snapshotFlow { inputState.snapshotDraft() }
            .distinctUntilChanged()
            .collect { draft -> draftStore.save(key, draft) }
    }

    /**
     * 恢复草稿。[replaceInput] 为 true 表示这是切换 key，新 key 没有草稿时要把输入清掉，
     * 不能把上一个助手/会话的字留在新 key 的输入框里。
     *
     * [replaceInput] 为 false（首次恢复、key 迁移）时只有输入框还是空的时候才填草稿：
     * 读盘期间用户可能已经打了字，那份内容不能让位给磁盘上的旧稿。
     */
    private suspend fun restoreDraft(key: String, replaceInput: Boolean) {
        val baseline = inputState.snapshotDraft()
        val stored = draftStore.load(key)?.takeIf { it.hasPersistableState }
        // 读盘期间用户已经动了输入（或分享内容先到了）：以当前输入为准，迟到的草稿不能覆盖它
        if (inputState.snapshotDraft() != baseline) return
        // 读盘期间又切了 key：这份草稿不属于当前的输入框了
        if (synchronized(draftLock) { draftKey } != key) return
        when {
            replaceInput && stored != null && stored != baseline -> applyDraft(stored)
            replaceInput && stored == null && !baseline.isEmpty -> inputState.clearInput()
            !replaceInput && stored != null && stored != baseline && baseline.isEmpty -> applyDraft(stored)
        }
    }

    private fun applyDraft(draft: ChatDraft) {
        // 编辑状态只在原消息还在时恢复：那条消息被删了/换分支了，恢复成编辑就发不出去
        val editingExists = draft.editingMessage?.let { id ->
            conversation.value.currentMessages.any { it.id == id }
        } == true
        inputState.applyDraft(draft, restoreEditing = editingExists)
    }

    /**
     * 把草稿里记着的思考档位放回这段会话。
     *
     * 只在这段对话自己还没选过档位时用草稿里的值（会话上已有的选择以会话为准），
     * 而且和默认值相同就不写：仅仅打开一次页面不会把默认档位钉死在会话上。
     */
    private suspend fun applyDraftReasoning(level: ReasoningLevel?) {
        if (level == null) return
        val current = conversation.value
        if (current.config?.reasoningLevel != null) return
        if (level == settings.value.reasoningLevelOf(current)) return
        chatService.updateChatAssistant(_conversationId) { it.copy(reasoningLevel = level) }
    }

    /**
     * 把现在的输入立刻记下来并落盘（页面暂停/离开用）。
     * 只是内存入队 + 马上写，不挂起，Compose 生命周期回调里可以放心调。
     */
    fun persistDraftNow() {
        val key = synchronized(draftLock) { draftKey } ?: return
        val draft = inputState.snapshotDraft()
        if (_draftReady.value || draft.hasPersistableState) draftStore.saveNow(key, draft)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 流式输出时会话每个 chunk 都在变，助手和模型只取决于下面这几个字段
    private val configuredConversation = conversation.distinctUntilChanged { old, new ->
        old.assistantId == new.assistantId && old.config == new.config &&
            old.modeInjectionIds == new.modeInjectionIds && old.lorebookIds == new.lorebookIds
    }

    // 会话视角下的助手：会话开始后，模型、思考级别、搜索等以会话上固定的配置为准
    val assistant: StateFlow<Assistant> = combine(settings, configuredConversation) { settings, conversation ->
        settings.getAssistantOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.getAssistantOf(conversation.value))

    // 当前模型
    val currentChatModel: StateFlow<Model?> = combine(settings, configuredConversation) { settings, conversation ->
        settings.getChatModelOf(conversation)
    }.stateIn(viewModelScope, SharingStarted.Lazily, settings.value.getChatModelOf(conversation.value))

    init {
        chatService.addConversationReference(_conversationId)
        context.writeStringPreference("lastConversationId", _conversationId.toString())
        viewModelScope.launch {
            try {
                chatService.initializeConversation(_conversationId)
                startDraftSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatService.addError(e, conversationId = _conversationId)
                startDraftSync()
            }
        }
    }

    // 错误状态
    // 按会话过滤：A 会话的报错条不能挂在 B 页面上（conversationId 为 null 的是全局错误，照常显示）。
    // 后台/子代理的通知走 inbox，本来就是按 conversationId 投递的；这里补的是 UI 层的错误串会话。
    val errors: StateFlow<List<ChatError>> = chatService.errors
        .map { list -> list.filter { it.conversationId == null || it.conversationId == _conversationId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    /** 只清当前会话页可见的错误，不能顺手抹掉另一个对话正在看的任务错误。 */
    fun clearAllErrors() {
        errors.value.forEach { error -> chatService.dismissError(error.id) }
    }

    val messageQueue = chatService.getInboxFlow(_conversationId)

    fun removeQueuedMessage(id: Uuid) = chatService.removeInboxItem(_conversationId, id)

    fun beginEditQueuedMessage(id: Uuid) = chatService.beginEditQueuedMessage(_conversationId, id)

    fun finishEditQueuedMessage(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishEditQueuedMessage(_conversationId, id, parts)

    fun resumeMessageQueue() = chatService.resumeInbox(_conversationId)

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(fn: (Settings) -> Settings): Job {
        return viewModelScope.launch {
            var oldSettings: Settings? = null
            var newSettings: Settings? = null
            settingsStore.update { old ->
                fn(old).also { new ->
                    oldSettings = old
                    newSettings = new
                }
            }
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings ?: return@launch, newSettings ?: return@launch)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 聊天页对助手的修改：新会话直接改助手，会话开始后只改当前会话
    fun updateAssistant(assistant: Assistant) {
        // 思考档位只属于这段对话：同时记进草稿，页面离开或进程重启后还能恢复同一个档位
        if (assistant.reasoningLevel != this.assistant.value.reasoningLevel) {
            inputState.reasoningLevel = assistant.reasoningLevel
        }
        viewModelScope.launch {
            chatService.updateChatAssistant(_conversationId) { assistant }
        }
    }

    // 新会话开始前归入抽屉里选中的文件夹
    suspend fun updateDraftFolder(folderId: Uuid?) {
        chatService.updateDraftConversationFolder(_conversationId, folderId)
    }

    // 设置聊天模型
    fun setChatModel(model: Model) {
        viewModelScope.launch {
            chatService.updateChatAssistant(_conversationId) { it.copy(chatModelId = model.id) }
        }
    }

    // 切换搜索方式
    fun updateSearch(enableWebSearch: Boolean, builtInSearch: Boolean) {
        viewModelScope.launch {
            chatService.updateChatSearch(_conversationId, enableWebSearch, builtInSearch)
        }
    }

    // Update checker
    val updateState = settingsStore.settingsFlow
        .map { settings ->
            !BuildConfig.IS_PLAY_STORE &&
                !settings.init &&
                settings.displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
        }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (enabled) updateChecker.updateState else flowOf(UiState.Loading)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            UiState.Loading,
        )

    /**
     * 处理消息发送
     *
     * 返回 true 表示已经交给 [ChatService]；false 表示没发（空内容、连点同一份、或 answer=true
     * 但没有可用模型），这时输入必须留着。
     *
     * 点发送先保留快照，ChatService 将消息可靠存入待发送队列后就清掉已提交内容，不再等待模型空闲。
     * 确认成功后清已提交的内容（同一 key），追加的新文字和附件保留；
     * 确认失败或者内容已经变了就什么都不做——用户等确认期间打的新字不会被清掉，
     * 失败的输入也还在原地，不用重打。
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>, answer: Boolean = true): Boolean {
        // 内容还没就绪时 conversation 里可能只是空壳占位：它的 assistantId 是"当前选中的助手"，
        // 不是这个会话真正的助手。这时发出去会把消息和草稿记到另一个助手名下，宁可等这一下。
        if (!contentReady.value) return false
        if (content.isEmptyInputMessage() || !_draftReady.value) return false
        // answer=true 但没有模型时这一轮必然失败，不能先把输入清掉
        if (answer && currentChatModel.value == null) return false

        val sent = inputState.snapshotDraft()
        // 连点：同一份内容还在等落库确认，不再发一次；这期间新输入的别的草稿照常能发
        // （只比内容，连点时光标位置可能已经变了）
        val duplicated = synchronized(draftLock) {
            val already = sendingDrafts.any { it.sameContent(sent) }
            if (!already) sendingDrafts.add(sent)
            already
        }
        if (duplicated) return false
        val key = synchronized(draftLock) { draftKey }

        // 先把快照写下来（同步入队 + 立刻写盘），进程随时死都能恢复
        if (key != null) draftStore.saveNow(key, sent)

        analytics.logEvent("ai_send_message", null)
        val receipt = chatService.sendMessageWithReceipt(_conversationId, content, answer, assistantId = assistant.value.id)
        if (receipt == null) {
            synchronized(draftLock) { sendingDrafts.removeAll { it.sameContent(sent) } }
            return false
        }

        // 等确认必须在页面生命周期之外：切页时 viewModelScope 会被取消，
        // 那时用户消息其实已经落库，草稿却再也清不掉了。
        draftScope.launch {
            val persisted = try {
                receipt.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            finishSend(key, sent, persisted)
        }
        return true
    }

    /** 队列受理确认回来：成功才清「仍是这一份」的草稿，失败保持原样（输入框本来就没清）。 */
    private suspend fun finishSend(key: String?, sent: ChatDraft, persisted: Boolean) {
        synchronized(draftLock) { sendingDrafts.removeAll { it.sameContent(sent) } }
        if (!persisted) return

        // 清输入框必须在主线程，而且 key 只能在主线程上现取：
        // 敲下发送后会话立刻有了第一条消息，draftKeyFlow 会在确认回来前后把 key 从
        // `assistant:<a>:<c>` 迁到 `conversation:<c>`。以前在 IO 上先取一次 key 再挂起，
        // 迁移刚好落在挂起中间时 stillSameKey 变 false，发送成功但输入框不清——就是用户看到的那条。
        withContext(Dispatchers.Main.immediate) {
            val keyNow = synchronized(draftLock) { draftKey }
            ChatSendDraftPolicy.remainingInputAfterAcceptance(
                key, keyNow, persisted, inputState.snapshotDraft(), sent,
            )?.let { remaining ->
                inputState.applyDraft(remaining)
                // 受理后清掉已提交部分；后来输入的字和附件保留，旧 debounce 不得写回。
                if (keyNow != null) draftStore.saveNow(keyNow, remaining)
            }
            // 落盘层只清「内容仍是这一份」的草稿（ack 内部按内容比对）：
            // 用户在等确认期间续写的字不会被清掉（续写不清）。
            // 要 ack 的 key 由策略算：原 key + （若已迁移）本会话的会话 key，
            // 迁移判定带会话 id（见 isDraftKeyMigration），所以不会把 A 的确认清到 B 的草稿上。
            ChatSendDraftPolicy.keysToAck(key, keyNow).forEach { ackKey ->
                draftStore.ack(ackKey, sent)
            }
        }
    }


    /**
     * 编辑历史消息。提交前不清输入：清早了编辑失败这条就没了。
     * 返回 true 表示已经交给 [ChatService]，编辑成功且输入仍是提交的那份时才清。
     */
    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid): Boolean {
        if (parts.isEmptyInputMessage()) return false
        analytics.logEvent("ai_edit_message", null)
        val submitted = inputState.snapshotDraft()
        val submittedKey = synchronized(draftLock) { draftKey }
        submittedKey?.let { draftStore.saveNow(it, submitted) }

        draftScope.launch {
            val edited = try {
                chatService.editMessage(_conversationId, messageId, parts)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!edited) return@launch
            submittedKey?.let { draftStore.ack(it, submitted) }
            withContext(Dispatchers.Main.immediate) {
                if (synchronized(draftLock) { draftKey } != submittedKey) return@withContext
                if (!inputState.snapshotDraft().sameContent(submitted)) return@withContext
                inputState.clearInput()
                synchronized(draftLock) { draftKey }?.let { key -> draftStore.save(key, ChatDraft()) }
            }
        }
        return true
    }

    fun handleCompressContext(additionalPrompt: String, keepRecentMessages: Int): Job {
        return chatService.startBackgroundCompression(_conversationId, additionalPrompt, keepRecentMessages)
    }

    /** 取消本会话正在跑的后台压缩；自动压缩跟着生成走，由停止生成处理 */
    fun handleCancelCompression() {
        chatService.cancelBackgroundCompression(_conversationId)
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(message: UIMessage) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message.id)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            chatService.updateTitle(_conversationId, title)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            // 会话没了，它的输入草稿也没意义了；按会话 key 清掉，免得以后重建同 id 恢复出旧草稿
            draftStore.forget(chatDraftKey(conversation.id, conversation.assistantId, hasMessages = true))
            chatService.deleteConversation(conversation.id)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            chatService.toggleConversationPinned(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            try {
                chatService.moveConversationToAssistant(conversation.id, targetAssistantId)
                if (conversation.id == _conversationId) settingsStore.selectAssistant(targetAssistantId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                chatService.addError(e, conversationId = _conversationId)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            chatService.generateTitle(conversation.id, force)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        viewModelScope.launch {
            chatService.clearTranslationField(_conversationId, messageId)
        }
    }

    fun selectMessageNode(nodeId: Uuid, selectIndex: Int) {
        viewModelScope.launch {
            try {
                chatService.selectMessageNode(_conversationId, nodeId, selectIndex)
            } catch (e: ApiException) {
                // 界面上的分支已经过时（比如刚被清理掉），切换不了就算了
                Log.w(TAG, "selectMessageNode: ${e.message}")
            }
        }
    }

    fun updateConversationSystemPrompt(systemPrompt: String?) {
        viewModelScope.launch {
            chatService.updateConversationSystemPrompt(_conversationId, systemPrompt)
        }
    }

    fun updateWorkspaceCwd(workspaceCwd: String?) {
        viewModelScope.launch {
            chatService.updateWorkspaceCwd(_conversationId, workspaceCwd)
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.setNodeFavorite(_conversationId, node.id, favorite = !currentlyFavorited)
        }
    }

}
