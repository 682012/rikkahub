package me.rerere.rikkahub.service

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.RetryAttempt
import me.rerere.ai.agent.httpStatusOf
import me.rerere.rikkahub.data.ai.retryPolicy
import me.rerere.common.http.NetworkHandover
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.LearningTurnMetrics
import me.rerere.rikkahub.data.ai.learning.AssistantLearningRepository
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.ai.ChatTurnContext
import me.rerere.rikkahub.data.ai.ConversationMetaGenerator
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.buildCompressPrompt
import me.rerere.rikkahub.data.ai.tools.InvalidMcpServerNamesException
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.forModel
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.event.CompressionOutcome
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.organization.ConversationLineageResolver
import me.rerere.rikkahub.data.model.TitleBranchAnchor
import me.rerere.rikkahub.data.model.TitleTrigger
import me.rerere.rikkahub.data.model.TitleWriteGuard
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.cancelInterruptedTools
import me.rerere.rikkahub.data.model.deleteMessage
import me.rerere.rikkahub.data.model.editMessage
import me.rerere.rikkahub.data.model.fillModelSnapshots
import me.rerere.rikkahub.data.model.fork
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.data.model.migrateToAssistant
import me.rerere.rikkahub.data.ai.tools.background.BackgroundToolOwner
import me.rerere.rikkahub.data.model.getStoredAssistantOf
import me.rerere.rikkahub.data.model.insertContextCheckpoint
import me.rerere.rikkahub.data.model.isMateriallyNewContext
import me.rerere.rikkahub.data.model.isAwaitingToolApproval
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.refinedTitle
import me.rerere.rikkahub.data.model.replaceUserRegexes
import me.rerere.rikkahub.data.model.selectBranch
import me.rerere.rikkahub.data.model.titleBranchAnchor
import me.rerere.rikkahub.data.model.titleDecision
import me.rerere.rikkahub.data.model.titleFingerprint
import me.rerere.rikkahub.data.model.titleState
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withAssistantUpdate
import me.rerere.rikkahub.data.model.withEarlyTitle
import me.rerere.rikkahub.data.model.withForcedTitle
import me.rerere.rikkahub.data.model.withManualTitle
import me.rerere.rikkahub.data.model.withNodeFavorite
import me.rerere.rikkahub.data.model.withToolApproval
import me.rerere.rikkahub.data.model.withTranslation
import me.rerere.rikkahub.data.model.withoutConversationFields
import me.rerere.rikkahub.data.model.withoutUnresumableToolCalls
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.util.concurrent.ConcurrentHashMap
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/** 流式生成落库间隔：进程被强杀时最多丢这么久的内容 */
private const val STREAM_PERSIST_INTERVAL_MS = 1_500L
private const val STREAM_TOOL_DRAFT_PERSIST_INTERVAL_MS = 500L

/** 手动压缩的终态状态在内存里保留多久，够 UI 显示一次结果 */
private const val COMPRESSION_STATE_RETENTION_MS = 10 * 60 * 1000L

/**
 * 被单飞挡掉的标题请求最多等前面那个多久。早标题比一轮结束的标题慢时，
 * 精修靠这次补做；等太久说明快速模型出问题了，直接放弃，不能一直挂着。
 */
private const val TITLE_FOLLOWUP_WAIT_MS = 8_000L

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val requestFactory: ChatRequestFactory,
    private val learningRepository: AssistantLearningRepository,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val translationHandler: TranslationHandler,
    private val metaGenerator: ConversationMetaGenerator,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val folderRepository: FolderRepository,
    private val backgroundShellManager: BackgroundShellManager,
    private val subagentRunner: me.rerere.rikkahub.data.ai.subagent.SubagentRunner,
    private val backgroundToolManager: me.rerere.rikkahub.data.ai.tools.background.BackgroundToolManager,
    private val networkHandover: NetworkHandover,
) {
    /** 一轮生成的结果 */
    private sealed interface TurnResult {
        data class Finished(val reason: AgentFinishReason) : TurnResult

        /** 没能完成：错误已经报告给用户，消息队列也已暂停 */
        data object Failed : TurnResult
    }

    private val conversationStore = object : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation {
            // 会话落库即视为开始，此时把助手的配置固定到会话上
            val settings = settingsStore.awaitLoaded()
            return { it.bindConfig(settings).fillModelSnapshots(settings) }
        }

        override suspend fun write(previous: Conversation?, current: Conversation) {
            if (previous == null && !conversationRepo.existsConversationById(current.id)) {
                conversationRepo.insertConversation(current)
            } else {
                conversationRepo.updateConversation(current)
            }
            if (previous != null && previous.messageNodes !== current.messageNodes) {
                withContext(Dispatchers.IO) { deleteUnreferencedFiles(previous, current) }
            }
        }
    }

    private val inboxDirectory = File(context.filesDir, "conversation-inbox")
    private val reconnectTickets = ConcurrentHashMap<Uuid, ChatReconnectTicket>()
    private val recoveryLedger = ChatRecoveryLedger(clock = SystemClock::elapsedRealtime)
    private val recoveryJobs = ConcurrentHashMap<Uuid, Job>()

    private fun clearRecovery(id: Uuid) {
        reconnectTickets.remove(id)
        recoveryJobs.remove(id)?.cancel()
        recoveryLedger.clear(id)
        appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(id, "", ""))
    }

    private fun scheduleRecovery(session: ConversationSession, ticket: ChatReconnectTicket, sender: String) {
        val wait = settingsStore.settingsFlow.value.retryPolicy().delayFor(recoveryLedger.attempts(session.id))
        session.processingStatus.value = context.getString(R.string.r21_recovery_scheduled, wait.inWholeSeconds)
        appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(session.id, sender, session.processingStatus.value.orEmpty()))
        val job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            delay(wait)
            if (reconnectTickets[session.id] !== ticket) return@launch
            // Timed retry also works when the network stays on the same route (timeout/5xx/half stream).
            try {
                tryReconnect(session, timed = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(session) {
                    if (reconnectTickets[session.id] === ticket) {
                        clearRecovery(session.id)
                        session.awaitingNetworkRecovery = false
                        session.processingStatus.value = null
                        session.inbox.pause(InboxPauseReason.ERROR)
                        addError(e, session.id)
                        appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(session.id, sender,
                            context.getString(R.string.r21_recovery_stopped), terminal = true))
                        session.scheduleIdleCheck()
                    }
                }
                return@launch
            }
            if (reconnectTickets[session.id] !== ticket) return@launch
            delay((ChatAutoRecovery.TICKET_TTL_MS - wait.inWholeMilliseconds).coerceAtLeast(0L))
            synchronized(session) {
                if (reconnectTickets[session.id] === ticket) {
                    reconnectTickets.remove(session.id)
                    recoveryLedger.clear(session.id)
                    session.awaitingNetworkRecovery = false
                    session.processingStatus.value = null
                    session.inbox.pause(InboxPauseReason.ERROR)
                    addError(IllegalStateException(context.getString(R.string.r21_recovery_timeout)), session.id)
                    appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(session.id, sender,
                        context.getString(R.string.r21_recovery_timeout), terminal = true))
                    session.scheduleIdleCheck()
                }
            }
        }
        recoveryJobs.put(session.id, job)?.cancel()
        job.invokeOnCompletion { recoveryJobs.remove(session.id, job) }
        job.start()
    }

    private val sessionManager = ConversationSessionManager(
        scope = appScope,
        store = conversationStore,
        createInitialConversation = { id ->
            Conversation.ofId(id, assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id)
        },
        onGenerationFinished = ::onSessionGenerationFinished,
        onSessionRemoved = { id ->
            clearRecovery(id)
            pendingStreamPersists.remove(id)?.cancel()
            lastStreamPersistAt.remove(id)
            memoryConsolidationScheduler.onConversationLeft(id)
        },
        createInbox = { id ->
            ConversationInbox(FileConversationInboxStorage(inboxDirectory, id), onPersistenceFailure = { error ->
                Log.w(TAG, "Queue pause could not be saved for $id", error)
                addError(error, id)
            })
        },
    )

    // 流式生成期间定期落库，进程被强杀时最多丢最近一次落库之后的内容
    private val lastStreamPersistAt = ConcurrentHashMap<Uuid, Long>()
    private val pendingStreamPersists = ConcurrentHashMap<Uuid, Job>()

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching { sessionManager.cleanup() }

    // Background completions are delivered at the next existing model step, never start a turn.

    init {
        // Queue completion notices only. An idle parent does not incur an unsolicited model request.
        subagentRunner.noticeSink = { event ->
            val session = sessionManager.getOrCreate(event.conversationId)
            synchronized(session) {
                if (!session.isChangingOwner) session.inbox.post(event.key, event.notice, wakeOnArrival = false)
            }
        }
        backgroundToolManager.noticeSink = { event ->
            val session = sessionManager.getOrCreate(event.conversationId)
            synchronized(session) {
                if (!session.isChangingOwner) session.inbox.post(event.key, event.notice, wakeOnArrival = false)
            }
        }
        appScope.launch {
            backgroundShellManager.noticeEvents.collect { event ->
                when (event) {
                    is BackgroundShellNoticeEvent.Posted -> postNotice(event.conversationId, event.key, event.notice)
                    is BackgroundShellNoticeEvent.Withdrawn ->
                        sessionManager.get(event.conversationId)?.inbox?.withdraw(event.key)
                }
            }
        }
    }

    init {
        appScope.launch(Dispatchers.IO) {
            settingsStore.awaitLoaded()
            // Pending user input is owned by the service, including conversations with no open page.
            inboxDirectory.listFiles().orEmpty().filter { it.extension == "json" }.forEach { file ->
                val id = runCatching { Uuid.parse(file.nameWithoutExtension) }.getOrNull() ?: return@forEach
                try {
                    val session = sessionManager.getOrCreate(id)
                    session.inbox.restore()
                    if (networkHandover.state.value.available && settingsStore.settingsFlow.value.retryPolicy().maxRetries > 0)
                        session.inbox.resumeAfterNetwork()
                    dispatchInbox(id)
                } catch (e: Exception) {
                    addError(e, id, title = context.getString(R.string.r18_queue_restore_failed))
                }
            }
            networkHandover.state.collect { route ->
                if (!route.available) return@collect
                delay(NetworkHandover.STABILIZE_MILLIS)
                if (!networkHandover.state.value.available) return@collect
                sessionManager.snapshot().forEach { session ->
                    try {
                        if (!tryReconnect(session) && reconnectTickets[session.id] == null &&
                            settingsStore.settingsFlow.value.retryPolicy().maxRetries > 0 && session.inbox.resumeAfterNetwork()) {
                            session.processingStatus.value = null
                            dispatchInbox(session.id)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        addError(e, session.id)
                    }
                }
            }
        }
    }

    private fun onSessionGenerationFinished(session: ConversationSession, cause: Throwable?) {
        // 取消只来自停止（已自行暂停）或删除，这里再暂停会盖掉停止后用户发送时恢复的队列
        if (cause != null && cause !is CancellationException && !session.awaitingNetworkRecovery) session.inbox.pause()
        if (session.state.value.isAwaitingToolApproval) {
            session.inbox.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
        }
        appScope.launch(Dispatchers.IO) { if (!tryReconnect(session)) dispatchInbox(session.id) }
    }

    // 保留 UI/Web 的入口，生命周期和状态查询统一交给 SessionManager。
    fun addConversationReference(conversationId: Uuid) {
        sessionManager.acquire(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessionManager.release(conversationId)
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
        sessionManager.getConversationFlow(conversationId)

    /** 会话的最新内容：已加载的以内存为准，否则读数据库 */
    suspend fun getConversation(conversationId: Uuid): Conversation? =
        sessionManager.get(conversationId)?.takeIf { it.isInitialized }?.state?.value
            ?: conversationRepo.getConversationById(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        sessionManager.getGenerationJobStateFlow(conversationId)

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> =
        sessionManager.getProcessingStatusFlow(conversationId)

    fun getReplyMessageIdFlow(conversationId: Uuid): StateFlow<Uuid?> =
        sessionManager.getReplyMessageIdFlow(conversationId)

    fun getGenerationStartedAtFlow(conversationId: Uuid): StateFlow<Long?> =
        sessionManager.getGenerationStartedAtFlow(conversationId)

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = sessionManager.getConversationJobs()

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        block: suspend () -> Unit,
    ): Job {
        if (!keepAliveInBackground) return appScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                pendingStreamPersists.remove(conversationId)?.cancel()
                lastStreamPersistAt.remove(conversationId)
            }
        }

        return appScope.launch(start = CoroutineStart.LAZY) {
            val generationId = Uuid.random()
            val foregroundStarted = ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                pendingStreamPersists.remove(conversationId)?.cancel()
                lastStreamPersistAt.remove(conversationId)
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        withLoadedSession(conversationId) { session ->
            if (settingsStore.awaitLoaded().assistants.any { it.id == session.state.value.assistantId }) {
                settingsStore.selectAssistant(session.state.value.assistantId)
            }
        }
    }

    private suspend fun ensureInitialized(session: ConversationSession) {
        session.initialize(
            load = { loadConversation(session.id) },
            create = { newConversation(session.id, session.inbox.state.value.ownerAssistantId ?: session.state.value.assistantId) },
        )
        session.inbox.bindOwner(session.state.value.assistantId)
    }

    /** 持有会话引用并确保已加载后执行 [block]；[createIfMissing] 为 false 时数据库里没有就抛 NotFoundException */
    private suspend fun <T> withLoadedSession(
        conversationId: Uuid,
        createIfMissing: Boolean = true,
        block: suspend (ConversationSession) -> T,
    ): T = sessionManager.withSession(conversationId) { session ->
        session.initialize(
            load = { loadConversation(conversationId) },
            create = {
                if (!createIfMissing) throw NotFoundException("Conversation not found")
                newConversation(conversationId, session.inbox.state.value.ownerAssistantId ?: session.state.value.assistantId)
            },
        )
        session.inbox.bindOwner(session.state.value.assistantId)
        block(session)
    }

    // 新建对话, 并添加预设消息
    private suspend fun newConversation(conversationId: Uuid, assistantId: Uuid? = null): Conversation {
        val settings = settingsStore.awaitLoaded()
        val assistant = if (assistantId == null) settings.getCurrentAssistant()
            else settings.assistants.firstOrNull { it.id == assistantId }
                ?: error("The assistant owning this pending message no longer exists")
        return Conversation.ofId(
            id = conversationId,
            assistantId = assistant.id,
            newConversation = true
        ).updateCurrentMessages(assistant.presetMessages)
    }

    // 引入会话配置之前创建的会话没有固定配置，加载时按助手当前的值补上，之后不再随助手变化。
    private suspend fun loadConversation(conversationId: Uuid): Conversation? {
        val conversation = conversationRepo.getConversationById(conversationId) ?: return null
        // settingsFlow 在启动初期还是占位值，固定配置必须基于真实设置
        val bound = conversation.bindConfig(settingsStore.awaitLoaded())
        if (bound !== conversation) conversationRepo.updateConversationConfig(bound)
        return bound
    }

    /**
     * 加载会话后在最新状态上修改并落库。
     * 删除消息或切换分支也可能解除工具审批阻塞，落库后重新检查队列。
     */
    private suspend fun mutate(conversationId: Uuid, transform: (Conversation) -> Conversation): Conversation =
        withLoadedSession(conversationId) { session ->
            val updated = session.tryUpdate(transform)
            if (updated == null) {
                addError(IllegalStateException(context.getString(R.string.r21_assistant_busy)), conversationId)
                return@withLoadedSession session.state.value
            }
            session.flush().also { dispatchInbox(conversationId) }
        }

    // 只删上次落库时引用、这次落库不再引用的文件；只在内存里出现过的文件宁可漏删也不误删
    private fun deleteUnreferencedFiles(previous: Conversation, current: Conversation) {
        val session = sessionManager.get(current.id)
        val queuedFiles = (session?.inbox?.state?.value?.messages.orEmpty() +
            listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val retainedFiles = (current.files + queuedFiles).toSet()
        val deletedFiles = previous.files.filter { it !in retainedFiles }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "deleteUnreferencedFiles: $deletedFiles")
        }
    }

    // ---- 发送消息 ----

    fun getInboxFlow(conversationId: Uuid): StateFlow<InboxState> =
        sessionManager.getInboxFlow(conversationId)

    /** 删除收件箱里的一项：用户输入连同只被它引用的附件一起删，通知直接丢弃 */
    fun removeInboxItem(conversationId: Uuid, itemId: Uuid) {
        (sessionManager.get(conversationId)?.inbox?.remove(itemId) as? QueuedMessage)
            ?.let(::cleanupQueuedAttachments)
        dispatchInbox(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessionManager.get(conversationId)?.inbox?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessionManager.get(conversationId)?.inbox?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchInbox(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        // Draft-origin input keeps its own attachment references until persistence is acknowledged.
        if (previous.persisted != null) return
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessionManager.snapshot()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.inbox.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    fun resumeInbox(conversationId: Uuid) {
        appScope.launch(Dispatchers.IO) {
            val session = sessionManager.get(conversationId) ?: return@launch
            session.inbox.resume()
            if (!tryReconnect(session, force = true)) dispatchInbox(conversationId)
        }
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        sendMessageWithReceipt(conversationId, content, answer)
    }

    /** Receipt means durable queue acceptance, NOT waiting for an older generation to finish. */
    fun sendMessageWithReceipt(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        assistantId: Uuid? = null,
    ): CompletableDeferred<Boolean>? {
        if (content.isEmptyInputMessage()) return null
        val accepted = CompletableDeferred<Boolean>()
        val parts = content.toList()
        appScope.launch(Dispatchers.IO) {
            var acceptedByQueue = false
            try {
                sessionManager.withSession(conversationId) { session ->
                    session.inbox.restore()
                    // Only a brand-new draft may acquire its first owner from the sender.
                    if (!session.isInitialized && !conversationRepo.existsConversationById(conversationId)) {
                        session.inbox.bindOwner(assistantId ?: session.state.value.assistantId)
                    }
                    ensureInitialized(session)
                    synchronized(session) {
                        check(!session.isChangingOwner && !session.isAutoFiling) { context.getString(R.string.r21_assistant_busy) }
                        check(assistantId == null || assistantId == session.state.value.assistantId) {
                            context.getString(R.string.r21_assistant_changed)
                        }
                        session.inbox.bindOwner(session.state.value.assistantId)
                        clearRecovery(conversationId)
                        session.awaitingNetworkRecovery = false
                        // Sending a new user message is explicit permission to continue an old paused queue.
                        session.inbox.resume()
                        val queued = session.inbox.enqueue(parts, answer, persisted = CompletableDeferred())
                        acceptedByQueue = queued != null
                        accepted.complete(acceptedByQueue)
                        dispatchInbox(conversationId)
                    }
                }
            } catch (e: Exception) {
                accepted.complete(false)
                if (e is CancellationException) throw e
                addError(e, conversationId, title = context.getString(
                    if (acceptedByQueue) R.string.r18_queue_dispatch_failed else R.string.r18_queue_accept_failed))
            }
        }
        return accepted
    }

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = sessionManager.getOrCreate(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(!session.isChangingOwner && !session.isAutoFiling) { context.getString(R.string.r21_assistant_busy) }
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.inbox.state.value.paused || session.inbox.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(!session.state.value.isAwaitingToolApproval) {
                context.getString(R.string.chat_page_voice_tools_before_resume)
            }
            clearRecovery(conversationId)
            session.awaitingNetworkRecovery = false
            session.processingStatus.value = null
            if (session.inbox.state.value.messages.isEmpty()) session.inbox.resume()
            session.inbox.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            dispatchInbox(conversationId)
        }
        return reply
    }

    /**
     * 放入一条运行时通知，例如后台命令结束。
     * 在已有轮次的下一步或用户下次发送时取走；后台完成不能自行触发计费轮次。
     */
    fun postNotice(conversationId: Uuid, key: String, notice: UIMessagePart.Notice, expectedAssistantId: Uuid? = null) {
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            if (session.isChangingOwner) return
            if (expectedAssistantId != null) {
                if (session.isInitialized && session.state.value.assistantId != expectedAssistantId) return
                session.inbox.bindOwner(expectedAssistantId)
            }
            session.inbox.post(key, notice, wakeOnArrival = false)
        }
    }

    /** 会话空闲时从收件箱开始下一轮：先发用户输入，只剩通知时单独开一轮 */
    private fun dispatchInbox(conversationId: Uuid): Job? {
        val session = sessionManager.get(conversationId) ?: return null
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            if (session.getJob() != null || session.state.value.isAwaitingToolApproval) return null
            if (session.awaitingNetworkRecovery || session.isChangingOwner || session.isAutoFiling) return null
            if (!networkHandover.state.value.available && settingsStore.settingsFlow.value.retryPolicy().maxRetries > 0) {
                session.inbox.pause(InboxPauseReason.NETWORK)
                session.processingStatus.value = context.getString(R.string.r18_waiting_network)
                return null
            }
            return when (val next = session.inbox.takeNext() ?: return null) {
                is InboxTurn.Message -> {
                    session.submittingMessage = next.message
                    sendQueuedMessage(session, next.message)
                }

                InboxTurn.Notices -> sendQueuedNotices(session)
            }
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        // 新消息开始初始化前就清掉旧回复，初始化失败或任务启动前取消也不能留下旧预览。
        session.replyMessageId.value = null
        val job = launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = queued.answer,
        ) {
            try {
                ensureInitialized(session)
                finishInterruptedPendingTools(session)

                val assistant = settingsStore.awaitLoaded().getStoredAssistantOf(session.state.value)
                val processedContent = queued.parts.replaceUserRegexes(assistant)

                // 添加消息到列表
                val previousIds = session.state.value.currentMessages.mapTo(HashSet()) { it.id }
                if (session.state.value.messageNodes.none { node -> node.messages.any { it.id == queued.id } }) {
                    session.update {
                        it.copy(
                            messageNodes = it.messageNodes + UIMessage(
                                id = queued.id,
                                role = MessageRole.USER,
                                parts = processedContent,
                            ).toMessageNode(),
                        )
                    }
                }
                session.flush()
                session.inbox.acknowledge(queued.id)
                session.submittingMessage = null
                // 用户消息已经写进会话：输入草稿这时才可以清掉
                queued.persisted?.complete(true)

                // 消息一落库就按已配置的标题规则在后台起临时标题，不等 AI 回复；
                // 快速模型慢、没配置或报错都不影响这一轮继续生成
                session.state.value.currentMessages
                    .firstOrNull { it.id == queued.id }
                    ?.let(TitleTrigger::of)
                    ?.let { trigger -> launchEarlyTitle(conversationId, trigger) }

                // 开始补全
                val result = if (queued.answer) runTurn(session) else null

                queued.reply?.completeWith(runCatching {
                    check(result != TurnResult.Failed) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check((result as? TurnResult.Finished)?.reason !is AgentFinishReason.AwaitingApproval) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    session.state.value.currentMessages
                        .filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                runCatching { session.inbox.requeueInFlight("initialization or persistence failed") }
                    .onFailure { addError(it, conversationId) }
                // 没写进会话：草稿要留着，别把它当成已经发出去了
                queued.persisted?.complete(false)
                e.printStackTrace()
                reportFailure(session, e, R.string.error_title_send_message)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            if (cause != null) {
                // 取消（停止生成、会话被回收）时消息可能还没落库：没完成就按「没写进去」处理。
                // 已经 complete(true) 的是生成阶段被取消，这里不会改结果。
                queued.persisted?.complete(false)
            }
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    /** 只有通知在等时开的一轮：通知写在一条新的助手消息开头，模型的回复接在后面 */
    private fun sendQueuedNotices(session: ConversationSession): Job {
        val conversationId = session.id
        session.replyMessageId.value = null
        val job = launchGenerationJob(conversationId = conversationId) {
            try {
                // 通知来自已经开始过的会话；会话刚被删除时不能重新创建
                session.initialize(
                    load = { loadConversation(conversationId) },
                    create = { throw NotFoundException("Conversation not found") },
                )
                // 加载前不知道是否在等审批；审批后执行完工具，这一轮会自己取走通知
                if (session.state.value.isAwaitingToolApproval) return@launchGenerationJob
                val queued = session.inbox.peekNotices()
                if (queued.isEmpty()) return@launchGenerationJob
                val fresh = queued.filterNot { it.isDeliveredIn(session.state.value.currentMessages) }
                if (fresh.isNotEmpty()) {
                    session.update {
                        it.copy(messageNodes = it.messageNodes + UIMessage(
                            role = MessageRole.ASSISTANT, parts = fresh.map { item -> item.toDeliveryPart() },
                        ).toMessageNode())
                    }
                    session.flush()
                }
                session.inbox.acknowledgeNotices(queued)
                if (fresh.isEmpty()) return@launchGenerationJob
                runTurn(session)
                _generationDoneFlow.emit(conversationId)
            } catch (e: NotFoundException) {
                Log.w(TAG, "sendQueuedNotices: conversation $conversationId not found, dropping notices")
                session.inbox.takeNotices()
            } catch (e: Exception) {
                reportFailure(session, e, R.string.error_title_generation)
            }
        }
        session.setJob(job)
        return job
    }

    // 发送、重新生成和审批失败时报告错误并暂停队列，排队的消息等用户处理完再继续
    private fun reportFailure(session: ConversationSession, error: Exception, @StringRes title: Int) {
        if (error is CancellationException) throw error
        clearRecovery(session.id)
        session.awaitingNetworkRecovery = false
        session.processingStatus.value = null
        session.inbox.pause()
        appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(session.id, "",
            context.getString(R.string.r21_recovery_stopped), terminal = true))
        addError(error, session.id, title = context.getString(title))
    }

    // ---- 重新生成消息 ----

    /** 重新生成用户消息时去掉它之后的节点再回复；重新生成助手消息时在它的节点上新开一个分支 */
    fun regenerateAtMessage(conversationId: Uuid, messageId: Uuid) {
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            if (session.isChangingOwner || session.isAutoFiling) {
                addError(IllegalStateException(context.getString(R.string.r21_assistant_busy)), conversationId)
                return
            }
            clearRecovery(conversationId)
            session.awaitingNetworkRecovery = false
            val previousJob = session.getJob()
            val job = launchGenerationJob(conversationId = conversationId) {
                try {
                    previousJob?.join()
                    session.replyMessageId.value = null
                    ensureInitialized(session)
                    val node = session.state.value.getMessageNodeByMessageId(messageId)
                        ?: return@launchGenerationJob

                    if (node.role == MessageRole.USER) {
                        session.tryUpdate { conversation ->
                            val index = conversation.messageNodes.indexOfFirst { it.id == node.id }
                            // 查找之后这条消息被删掉了
                            if (index == -1) return@tryUpdate null
                            conversation.copy(messageNodes = conversation.messageNodes.take(index + 1))
                        } ?: return@launchGenerationJob
                        session.flush()
                        runTurn(session)
                    } else {
                        try { learningRepository.markRegenerated(session.state.value.assistantId.toString(), messageId.toString()) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Log.w(TAG, "Could not record regeneration", e) }
                        runTurn(session, regenerateNodeId = node.id)
                    }

                    _generationDoneFlow.emit(conversationId)
                } catch (e: Exception) {
                    reportFailure(session, e, R.string.error_title_regenerate_message)
                }
            }

            session.setJob(job)
        }
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = sessionManager.getOrCreate(conversationId)
        synchronized(session) {
            if (session.isChangingOwner || session.isAutoFiling) {
                addError(IllegalStateException(context.getString(R.string.r21_assistant_busy)), conversationId)
                return
            }
            val previousJob = session.getJob()

            val hasOtherPendingTools = session.state.value.currentMessages.any { message ->
                message.getTools().any { it.isPending && it.toolCallId != toolCallId }
            }

            val job = launchGenerationJob(
                conversationId = conversationId,
                keepAliveInBackground = !hasOtherPendingTools,
            ) {
                try {
                    afterPreviousGeneration(previousJob) {
                        ensureInitialized(session)
                        val newApprovalState = when {
                            answer != null -> ToolApprovalState.Answered(answer)
                            approved -> ToolApprovalState.Approved
                            else -> ToolApprovalState.Denied(reason)
                        }

                        val updated = session.tryUpdate { it.withToolApproval(toolCallId, newApprovalState) }
                            ?: return@afterPreviousGeneration
                        session.flush()

                        // Only continue generation when all pending tools are handled
                        if (!updated.isAwaitingToolApproval) {
                            clearRecovery(conversationId)
                            session.awaitingNetworkRecovery = false
                            runTurn(session)
                        }

                        _generationDoneFlow.emit(conversationId)
                    }
                } catch (e: Exception) {
                    reportFailure(session, e, R.string.error_title_tool_approval)
                }
            }

            session.setJob(job, cancelPrevious = false)
        }
    }

    // ---- 生成回复 ----

    /**
     * 生成一轮回复：请求模型、执行工具，直到模型不再调用工具、有工具需要审批或达到步数上限。
     *
     * 默认接着当前分支的末尾回复；[regenerateNodeId] 不为空时只发送这个节点之前的消息，回复作为它的新分支。
     * 发送的最后一条是助手消息时从它继续（比如审批之后），回复写回那条消息。
     * 失败时报告错误、暂停消息队列并返回 [TurnResult.Failed]；取消时直接抛出。
     */
    private suspend fun runTurn(session: ConversationSession, regenerateNodeId: Uuid? = null): TurnResult {
        val conversationId = session.id
        // 先清理上一轮状态；确定历史后，仅为真正的续写恢复消息 ID。
        session.replyMessageId.value = null
        // 生成开始前失败时通知只用来取消 Live Update，用不到发送者名称
        var senderName = ""
        val attemptEpoch = networkHandover.state.value.epoch
        val generationJob = currentCoroutineContext()[Job]
        var reconnectCheckpoint: ChatReconnectCheckpoint? = null
        var activeReplyNodeId: Uuid? = null
        var previousReplySelectIndex: Int? = null
        var learningTurn: ChatTurnContext? = null
        var retries = 0
        var recoveryProgressVisible = recoveryLedger.attempts(conversationId) > 0
        var observedReply: UIMessage? = null
        var turnFinishReason: AgentFinishReason? = null
        try {
            val settings = settingsStore.awaitLoaded()
            // fork: 上下文到阈值时先插入摘要检查点，不删除历史
            if (regenerateNodeId == null) maybeAutoCompress(session, settings)
            // 清掉上一轮的建议；没法继续执行的工具调用发给模型会被拒绝，先去掉。
            // 重新生成时只清理要发给模型的节点，被重新生成的节点保持原样，旧回复留作分支
            val conversation = session.update { latest ->
                val untilNode = regenerateNodeId
                    ?.let { nodeId -> latest.messageNodes.indexOfFirst { it.id == nodeId } }
                    ?.takeIf { it != -1 }
                    ?: latest.messageNodes.size
                latest.copy(chatSuggestions = emptyList()).withoutUnresumableToolCalls(untilNode)
            }

            val nodes = conversation.messageNodes
            previousReplySelectIndex = regenerateNodeId?.let { id -> nodes.firstOrNull { it.id == id }?.selectIndex }
            val history = if (regenerateNodeId != null) {
                val index = nodes.indexOfFirst { it.id == regenerateNodeId }
                check(index != -1) { "Message node $regenerateNodeId not found" }
                conversation.currentMessages.take(index)
            } else {
                conversation.currentMessages
            }
            val replyNodeId = when {
                history.lastOrNull()?.role == MessageRole.ASSISTANT -> nodes[history.lastIndex].id
                regenerateNodeId != null -> regenerateNodeId
                else -> Uuid.random()
            }
            activeReplyNodeId = replyNodeId
            reconnectCheckpoint = ChatReconnectCheckpoint(history.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT })
            // 接着上一条助手消息写时就是它，否则等第一次输出。要在 createContext 之前换掉，
            // 它会挂起也可能失败，期间或失败后不能还指着上一轮的回复
            session.replyMessageId.value = history.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.id

            val turn = requestFactory.createContext(settings, conversation)
            learningTurn = turn
            senderName = if (turn.assistant.useAssistantAvatar) {
                turn.assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
            } else {
                turn.model.displayName
            }
            warnIfToolsUnavailable(turn, conversationId)

            var pendingNotices = emptyList<QueuedNotice>()
            val (request, hooks) = requestFactory.createAgent(
                turn = turn,
                messages = history,
                processingStatus = session.processingStatus,
                takePendingParts = {
                    val queued = session.inbox.peekNotices()
                    val (delivered, fresh) = queued.partition { it.isDeliveredIn(session.state.value.currentMessages) }
                    session.inbox.acknowledgeNotices(delivered)
                    pendingNotices = fresh
                    fresh.map { it.toDeliveryPart() }
                },
            )
            var finishReason: AgentFinishReason? = null
            val persistedToolStates = HashMap<String, Pair<kotlin.time.Instant?, Boolean>>()
            try {
                runAgent(request, hooks).flowOn(Dispatchers.IO).collect { event ->
                    when (event) {
                        is AgentEvent.MessageUpdated -> {
                            observedReply = event.message
                            if (recoveryProgressVisible) {
                                appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(conversationId, "", ""))
                                recoveryProgressVisible = false
                            }
                            reconnectCheckpoint?.observe(event.message)
                            // 生成过程中只有网络重试的提示会留着，收到新内容说明重试已经成功
                            session.processingStatus.value = null
                            session.update { it.upsertMessage(replyNodeId, event.message) }
                            // 流式内容此刻只在内存里，定时落库避免强杀丢掉整段回复
                            val tools = event.message.getTools()
                            val lifecycleChanged = tools.any { tool ->
                                val previous = persistedToolStates[tool.toolCallId]
                                (tool.startedAt != null && previous?.first != tool.startedAt) ||
                                    (tool.isExecuted && previous?.second != true)
                            }
                            if (lifecycleChanged || pendingNotices.isNotEmpty()) {
                                // Commit start before effects and each completed result without stream throttling.
                                // AgentLoop waits for this collector, so a killed process cannot silently replay a write.
                                session.flush()
                                // Commit notice parts before removing their durable queue entries.
                                session.inbox.acknowledgeNotices(pendingNotices)
                                pendingNotices = emptyList()
                                tools.forEach { tool ->
                                    persistedToolStates[tool.toolCallId] = tool.startedAt to tool.isExecuted
                                }
                            } else {
                                val receivingToolInput = tools.any {
                                    it.startedAt == null && !it.isExecuted && it.input.isNotEmpty()
                                }
                                persistStreamingThrottled(session, if (receivingToolInput)
                                    STREAM_TOOL_DRAFT_PERSIST_INTERVAL_MS else STREAM_PERSIST_INTERVAL_MS)
                            }
                            session.replyMessageId.value = event.message.id
                            // 通知等边缘副作用由 ChatNotificationManager 消费；
                            // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, event.message, senderName)
                            )
                        }

                        is AgentEvent.Retrying -> {
                            retries++
                            recoveryProgressVisible = true
                            logRetry(event.attempt)
                            session.processingStatus.value = retryStatus(event.attempt)
                            appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(conversationId, senderName,
                                retryStatus(event.attempt)))
                        }

                        is AgentEvent.Finished -> {
                            Log.i(TAG, "runTurn: finished (${event.reason})")
                            finishReason = event.reason
                            turnFinishReason = event.reason
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    session.processingStatus.value = null
                    // 可能被取消了，或者意外结束，兜底保存
                    pendingStreamPersists.remove(session.id)?.cancel()
                    val finished = session.finishGeneration()
                    // 生成结束：取消 Live Update 通知，后台时发送完成通知
                    appEventBus.emit(
                        AppEvent.ChatGenerationEnded(
                            conversationId = conversationId,
                            senderName = senderName,
                            contentPreview = if (finishReason == AgentFinishReason.Completed)
                                finished.currentMessages.lastOrNull()?.toText()?.take(50)?.trim().orEmpty()
                            else null,
                        )
                    )
                }
            }

            val reason = checkNotNull(finishReason)
            // 等待审批时这一轮还没结束，标题、建议和记忆整理等审批之后再做
            if (reason !is AgentFinishReason.AwaitingApproval) {
                // 标题：发消息时已经按原话起过就用那个；这里只在还没有标题时才补一个，
                // 有临时标题时看内容是否真的往前走了、再精修一次，已经精修过的会话不重复起标题
                launchEndOfTurnTitle(conversationId)
                sessionManager.launchWithSession(conversationId) { generateSuggestion(conversationId) }
                if (turn.assistant.enableMemory) memoryConsolidationScheduler.onTurnFinished(session.state.value)
            }
            clearRecovery(conversationId)
            session.awaitingNetworkRecovery = false
            return TurnResult.Finished(reason)
        } catch (e: CancellationException) {
            clearRecovery(conversationId)
            session.awaitingNetworkRecovery = false
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            throw e
        } catch (e: InvalidMcpServerNamesException) {
            clearRecovery(conversationId)
            session.awaitingNetworkRecovery = false
            session.inbox.pause()
            appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(conversationId, senderName,
                context.getString(R.string.r21_recovery_stopped), terminal = true))
            addError(
                error = IllegalStateException(
                    context.getString(R.string.error_mcp_invalid_server_name, e.names.joinToString(", "))
                ),
                conversationId = conversationId,
            )
            return TurnResult.Failed
        } catch (e: Exception) {
            // 兜底取消 Live Update 通知（生成开始前失败时上面的 finally 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))
            synchronized(session) {
                generationJob?.ensureActive()
                val retry = settingsStore.settingsFlow.value.retryPolicy()
                val checkpoint = reconnectCheckpoint
                val blocker = ChatAutoRecovery.blockReason(e,
                    httpStatus = httpStatusOf(e),
                    awaitingApproval = session.state.value.isAwaitingToolApproval,
                    canResume = checkpoint?.canResume == true)
                val recoverable = retry.maxRetries > 0 && retry.retryOn(e) && blocker == null &&
                    activeReplyNodeId != null && recoveryLedger.noteFailure(conversationId, retry.maxRetries)
                if (recoverable) {
                    val current = session.state.value.currentMessages
                    reconnectTickets[conversationId] = ChatReconnectTicket(
                        failedEpoch = attemptEpoch, incarnation = session.incarnation,
                        userMessage = current.lastOrNull { it.role == MessageRole.USER },
                        replyNodeId = checkNotNull(activeReplyNodeId), checkpoint = checkpoint?.message,
                        failedTail = current.lastOrNull(),
                        interruptedMessageId = observedReply?.id, regenerateNodeId = regenerateNodeId,
                        previousSelectIndex = previousReplySelectIndex,
                    )
                    session.awaitingNetworkRecovery = true
                    session.inbox.pause(InboxPauseReason.NETWORK)
                    scheduleRecovery(session, checkNotNull(reconnectTickets[conversationId]), senderName)
                } else {
                    val attempts = recoveryLedger.attempts(conversationId)
                    val stoppedMessage = when (blocker) {
                        ChatRecoveryBlocker.AUTH -> context.getString(R.string.r21_recovery_blocked_auth)
                        ChatRecoveryBlocker.QUOTA -> context.getString(R.string.r21_recovery_blocked_quota)
                        ChatRecoveryBlocker.REQUEST_FORMAT -> context.getString(R.string.r21_recovery_blocked_request)
                        ChatRecoveryBlocker.UNKNOWN_SIDE_EFFECT -> context.getString(R.string.r21_recovery_blocked_side_effect)
                        else -> if (retry.maxRetries > 0 && attempts >= retry.maxRetries)
                            context.getString(R.string.r21_recovery_gave_up, attempts)
                        else context.getString(R.string.r21_recovery_stopped)
                    }
                    clearRecovery(conversationId)
                    session.awaitingNetworkRecovery = false
                    session.inbox.pause()
                    appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(conversationId, senderName,
                        stoppedMessage, terminal = true))
                }
            }
            e.printStackTrace()
            addError(e, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "runTurn: $e")
            Logging.log(TAG, e.stackTraceToString())
            return TurnResult.Failed
        } finally {
            val owner = learningTurn
            val reply = observedReply
            if (owner != null && reply != null && turnFinishReason !is AgentFinishReason.AwaitingApproval) {
                val metric = LearningTurnMetrics.from(owner.assistant.id, conversationId, owner.model.id,
                    reply, retries, turnFinishReason)
                // No model call and no message body is retained. Repository I/O never blocks reply delivery.
                appScope.launch(Dispatchers.IO) {
                    try {
                        learningRepository.recordTurn(owner.assistant.id.toString(), metric)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Log.w(TAG, "Could not record learning metrics", e) }
                }
            }
        }
    }

    private fun tryReconnect(session: ConversationSession, force: Boolean = false, timed: Boolean = false): Boolean {
        synchronized(session) {
            val ticket = reconnectTickets[session.id] ?: return false
            if (session.getJob() != null || session.state.value.isAwaitingToolApproval || session.isChangingOwner || session.isAutoFiling) return false
            val pause = session.inbox.state.value
            if (!force && (pause.pauseReason != InboxPauseReason.NETWORK || !pause.paused)) return false
            val route = networkHandover.state.value
            val current = session.state.value.currentMessages
            val user = current.lastOrNull { it.role == MessageRole.USER }
            val unchanged = session.incarnation == ticket.incarnation && user == ticket.userMessage &&
                current.lastOrNull() == ticket.failedTail
            val expired = !force && !recoveryLedger.resumeAllowed(session.id)
            if (!unchanged || settingsStore.settingsFlow.value.retryPolicy().maxRetries <= 0 || expired) {
                clearRecovery(session.id)
                session.awaitingNetworkRecovery = false
                session.processingStatus.value = null
                session.inbox.pause(InboxPauseReason.ERROR)
                if (unchanged && expired) {
                    addError(IllegalStateException(context.getString(R.string.r21_recovery_timeout)), session.id)
                    appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(session.id, "",
                        context.getString(R.string.r21_recovery_timeout), terminal = true))
                }
                session.scheduleIdleCheck()
                return false
            }
            if (!route.available || (!force && !timed && !ticket.canResume(route.epoch, true, session.incarnation, user))) return false
            session.inbox.resumeAfterNetwork()
            reconnectTickets.remove(session.id)
            recoveryJobs.remove(session.id)?.cancel()
            session.awaitingNetworkRecovery = false
            val job = launchGenerationJob(conversationId = session.id) {
                try {
                    // Resume from the completed checkpoint, retaining received code in a separate
                    // branch. A retry must never silently erase a half-written tool request.
                    var keptDraftNode = false
                    session.update { conversation ->
                        conversation.copy(messageNodes = conversation.messageNodes.mapNotNull { node ->
                            if (node.id != ticket.replyNodeId) return@mapNotNull node
                            val retained = ticket.restoreReplyVariantsPreservingDrafts(node.messages)
                            keptDraftNode = retained.isNotEmpty()
                            if (retained.isEmpty()) null else node.copy(messages = retained,
                                selectIndex = ticket.restoredSelectIndex(node.selectIndex, retained.size))
                        })
                    }
                    session.flush()
                    _errors.update { errors -> errors.filterNot { it.conversationId == session.id &&
                        it.error is IOException && it.error !is InboxPersistenceException } }
                    appEventBus.tryEmit(AppEvent.ChatRecoveryStatus(session.id, "",
                        context.getString(R.string.r21_recovery_resumed)))
                    val retryNode = if (ticket.checkpoint == null && keptDraftNode) ticket.replyNodeId
                        else ticket.retryRegenerateNodeId
                    runTurn(session, regenerateNodeId = retryNode)
                    _generationDoneFlow.emit(session.id)
                } catch (e: Exception) { reportFailure(session, e, R.string.error_title_generation) }
            }
            session.setJob(job)
            return true
        }
    }

    // 模型不支持工具调用时，搜索和 MCP 工具都不会生效，提醒一下
    private fun warnIfToolsUnavailable(turn: ChatTurnContext, conversationId: Uuid) {
        if (ModelAbility.TOOL in turn.model.abilities) return
        if (shouldUseExternalWebSearch(turn.assistant, turn.model) ||
            mcpManager.getAllAvailableTools(turn.assistant).isNotEmpty()
        ) {
            addError(
                IllegalStateException(context.getString(R.string.tools_warning)),
                conversationId,
                title = context.getString(R.string.error_title_tool_unavailable)
            )
        }
    }

    /**
     * 流式生成期间的节流落库。
     *
     * 每个 token 都写库太重，而只在生成结束时写又会在进程被强杀时丢掉整段回复。
     * 这里按 [STREAM_PERSIST_INTERVAL_MS] 节流，写库失败只记日志，不打断生成。
     */
    private suspend fun persistStreamingThrottled(
        session: ConversationSession,
        intervalMillis: Long = STREAM_PERSIST_INTERVAL_MS,
    ) {
        val now = SystemClock.elapsedRealtime()
        val last = lastStreamPersistAt[session.id] ?: 0L
        if (now - last < intervalMillis) {
            // Persist the trailing delta even if the server stalls and sends no next chunk.
            scheduleTrailingStreamPersist(session, intervalMillis - (now - last))
            return
        }
        try {
            session.flush()
            // Failed writes must not advance the durable checkpoint clock.
            lastStreamPersistAt[session.id] = SystemClock.elapsedRealtime()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Streaming flush failed for ${session.id}", e)
        }
    }

    private fun scheduleTrailingStreamPersist(session: ConversationSession, waitMillis: Long) {
        synchronized(pendingStreamPersists) {
            if (pendingStreamPersists[session.id]?.isActive == true) return
            val job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                delay(waitMillis.coerceAtLeast(1L))
                // Read current session state; never keep or re-save a stale Conversation snapshot.
                persistStreamingThrottled(session, intervalMillis = 0L)
            }
            pendingStreamPersists[session.id] = job
            job.invokeOnCompletion { pendingStreamPersists.remove(session.id, job) }
            job.start()
        }
    }

    /** App 退到后台或任务被划掉时，把还在内存里的会话落库 */
    suspend fun flushActiveSessions() {
        sessionManager.snapshot().forEach { session ->
            if (!session.isInitialized || !session.existsInDatabase) return@forEach
            try {
                session.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Flush active session ${session.id} failed", e)
            }
        }
    }

    private fun logRetry(attempt: RetryAttempt) {
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${attempt.delay} (${attempt.retry}/${attempt.maxRetries})",
            attempt.cause,
        )
    }

    private fun retryStatus(attempt: RetryAttempt): String {
        val reason = when (attempt.cause) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(
            R.string.chat_generation_network_retrying,
            context.getString(reason),
            attempt.retry,
            attempt.maxRetries,
        )
    }

    private suspend fun finishInterruptedPendingTools(session: ConversationSession) {
        session.tryUpdate { it.cancelInterruptedTools() }
        session.flush()
    }

    // ---- 生成标题 ----

    /**
     * 发送用户消息后立刻起临时标题：只用用户原话，不等 AI 回答。
     *
     * 在后台跑，失败、没配快速模型、模型很慢都不影响聊天本身。
     * 停止生成后请求也照常做完写回：它在另一个任务里，不跟着这一轮被取消。
     *
     * 只发了图片/文件的用户消息没有文字快照，照样触发：生成时退回用最近的完整对话。
     */
    private fun launchEarlyTitle(conversationId: Uuid, trigger: TitleTrigger) {
        val settings = settingsStore.settingsFlow.value
        if (!settings.enableAutoTitle) return
        // 没有可用的快速模型就不必发请求，标题留给结束时的兜底
        if (metaGenerator.titleModel(settings) == null) return
        sessionManager.launchWithSession(conversationId) {
            requestTitle(conversationId, trigger, force = false)
        }
    }

    /** 一轮结束时兜底：还没有标题就补；有临时标题且内容往前走了，就精修一次 */
    private fun launchEndOfTurnTitle(conversationId: Uuid) {
        val settings = settingsStore.settingsFlow.value
        if (!settings.enableAutoTitle) return
        if (metaGenerator.titleModel(settings) == null) return
        sessionManager.launchWithSession(conversationId) {
            requestTitle(conversationId, immediate = null, force = false)
        }
    }

    /**
     * 自动/手动生成标题。
     *
     * @param immediate 用户消息刚发出去时的临时标题锚点；为空表示一轮结束后的兜底与精修
     * @param force 用户在界面上手动重新生成：忽略自动开关和手改标记，这一版就是用户要的
     */
    suspend fun generateTitle(
        conversationId: Uuid,
        force: Boolean = false,
    ): Unit = withContext(Dispatchers.IO) {
        requestTitle(conversationId, immediate = null, force = force)
    }

    private suspend fun requestTitle(
        conversationId: Uuid,
        immediate: TitleTrigger?,
        force: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        withLoadedSession<Unit>(conversationId) { session ->
            // 同一个会话一次只做一个标题请求：早标题和结束时标题撞上时，后到的那个先让路
            val ran = session.singleFlightTitle<Unit> {
                generateTitleLocked(session, conversationId, immediate, force)
            }
            if (ran != null) return@withLoadedSession
            // 让路的那一次不能就此算了：早标题比一轮结束晚到、或者上一条消息的标题还没跑完时，
            // 「回答已经出来、该精修一次」就只能在这里补上。等前面那个写完再看最新内容判断，
            // 该不该做由 generateTitleLocked 里的策略决定（同一段内容只精修一次，不会每轮重起标题）。
            session.queuedTitle(TITLE_FOLLOWUP_WAIT_MS) {
                generateTitleLocked(session, conversationId, immediate, force)
            }
        }
    }

    private suspend fun generateTitleLocked(
        session: ConversationSession,
        conversationId: Uuid,
        immediate: TitleTrigger?,
        force: Boolean,
    ) {
        val settings = settingsStore.awaitLoaded()
        // 关掉自动生成后只跳过自动调用，手动重新生成照常执行
        if (!force && !settings.enableAutoTitle) return
        val modelAndProvider = metaGenerator.titleModel(settings)
        if (modelAndProvider == null) {
            // 缺快速模型是用户设置问题：手动点的要看到错误，不能把异常抛到调用方外面
            if (force) addError(
                error = IllegalStateException(context.getString(R.string.error_fast_model_not_found)),
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
            return
        }

        val conversation = session.state.value
        val current = conversation.titleState
        // 用户手改过的标题：自动调用永远不覆盖；手动重新生成不看这个
        if (!force && !current.isAuto) return

        val messages = conversation.currentMessages
        val fingerprint = titleFingerprint(messages)
        val hasContent = messages.any { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
        val newContext = immediate == null && conversation.title.isNotBlank() &&
            isMateriallyNewContext(messages, current.anchor)
        val decision = conversation.titleDecision(
            fingerprint = fingerprint,
            newContext = newContext,
            force = force,
            hasContent = hasContent,
        )
        if (!decision.request) return

        // 写回时要核对的分支：早标题锚在刚发出的那条用户消息上；精修锚在那条用户消息和它下面这条回答上。
        // 期间用户改写了消息、切了分支或重新生成了回答，就不写（那已经是另一段对话了）
        val branch = if (immediate != null) {
            TitleBranchAnchor(immediate.messageId, immediate.userText)
        } else {
            messages.titleBranchAnchor(
                answerMessageId = if (decision.refine) messages.lastOrNull()?.id else null,
            )
        }
        // 只有用户原话才进锚点，助手的长回答不参与，所以标题不会每轮都跟着回答变
        val anchor = immediate ?: TitleTrigger(
            messageId = branch?.userMessageId ?: Uuid.random(),
            userText = branch?.userText ?: current.anchor,
        )
        // 早标题只给用户原话；精修和手动重新生成用最近几条真实对话。
        // 用户只发了图片/文件（没有文字）时也没有快照，同样退回完整对话，别把空字符串喂给快速模型
        val snapshot = if (decision.fullContext) null else anchor.userText.takeIf { it.isNotBlank() }
        val guard = TitleWriteGuard(
            title = conversation.title,
            source = current.source,
            revision = current.revision,
            branch = branch,
            force = force,
        )

        val incarnation = session.incarnation
        runCatching {
            val title = metaGenerator.generateTitle(
                settings = settings,
                conversation = conversation,
                model = modelAndProvider.first,
                provider = modelAndProvider.second,
                userTextSnapshot = snapshot,
            ).trim()
            if (title.isBlank()) return@runCatching
            // 只写标题这一列：这时可能已经在生成下一轮，不能把还没生成完的内容一起落库。
            // 会话被删除/整条换掉，或者用户在这期间手改了标题、切了分支，都不写入
            val written = session.updateMetadata(
                transform = { latest ->
                    when {
                        // 手动重新生成：手改过的标题也能换，来源保持不变
                        force -> latest.withForcedTitle(title, fingerprint)
                        // 精修时如果期间用户已经手改或这段内容已精修过，refinedTitle 返回 null：不写
                        decision.refine -> latest.refinedTitle(title, fingerprint)
                        else -> latest.withEarlyTitle(title, anchor)
                    }
                },
                persist = { conversationRepo.updateTitle(conversationId, title) },
                expectedIncarnation = incarnation,
                predicate = guard::allows,
            )
            if (!written) {
                // 会话被删/被整条换掉，或者期间用户手改了标题、切了分支：这一版标题作废
                Log.i(TAG, "title for $conversationId discarded: conversation changed while generating")
            }
        }.onFailure {
            if (it is CancellationException) throw it
            it.printStackTrace()
            // 自动标题失败不打断聊天，也不弹错误；只有用户手动点的才提示
            if (force) addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    private suspend fun generateSuggestion(conversationId: Uuid): Unit = withContext(Dispatchers.IO) {
        sessionManager.withSession<Unit>(conversationId) { session ->
            runCatching {
                val settings = settingsStore.awaitLoaded()
                if (!settings.enableSuggestion) return@runCatching

                // 上一轮的建议在这一轮开始时已经清掉了
                ensureInitialized(session)
                val incarnation = session.incarnation
                val suggestions = metaGenerator.generateSuggestions(settings, session.state.value)
                    ?: return@runCatching

                // 只写建议这一列，理由同标题
                session.updateMetadata(
                    transform = { it.copy(chatSuggestions = suggestions) },
                    persist = { conversationRepo.updateChatSuggestions(conversationId, suggestions) },
                    expectedIncarnation = incarnation,
                )
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    /**
     * 自动压缩：上下文占用达到模型窗口的阈值时，插入一条摘要检查点。
     *
     * 摘要不删除原始消息，模型后续只看到检查点；失败时静默继续，不阻塞发送。
     */
    private suspend fun maybeAutoCompress(
        session: ConversationSession,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ) {
        val snapshot = session.state.value
        // Resolve from the selected chat model, never from the summarizer model.
        val chatModel = settings.getChatModelOf(snapshot) ?: return
        val config = settings.autoCompress.forModel(chatModel)
        val plan = autoCompressionPlan(snapshot, config, estimateWhenUsageMissing =
            chatModel.findProvider(settings.providers) is me.rerere.ai.provider.ProviderSetting.WebAccount) ?: return
        // 阈值按对话模型自己的窗口算；摘要仍可交给全局指定的压缩模型生成。
        if (!plan.exceedsThreshold(chatModel.contextLength, config.thresholdPercent)) return
        val compressionModel = config.modelId?.let { settings.findModelById(it) }
        val turn = requestFactory.createContext(settings, snapshot, compressionModel?.takeUnless { it.id == chatModel.id })
        session.processingStatus.value = context.getString(R.string.r10_compaction_auto_status)
        try {
            val summary = requestFactory.generateCompressionSummary(
                turn = turn,
                messages = plan.messages,
                compressPrompt = buildCompressPrompt("", Locale.getDefault().displayName),
            )
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            session.tryUpdate { latest ->
                // Edits while summarizing invalidate this snapshot; never insert a stale checkpoint.
                if (latest.messageNodes != snapshot.messageNodes) null
                else latest.insertContextCheckpoint(plan.afterNodeId, summary)
            }?.let { session.flush() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "auto compress failed; continuing with original history", e)
        } finally {
            session.processingStatus.value = null
        }
    }

    // ---- 压缩对话历史 ----

    /**
     * 把上一个检查点之后的消息压缩成摘要检查点。
     *
     * [keepRecentMessages] 为 0 时压缩全部消息；大于 0 时最近的这些消息不参与压缩、留在检查点之后，
     * 对话已经超出模型上下文时用来缩小压缩请求。
     * 压缩请求本身是纯文本摘要输入（见 CompactionRequestMessages），历史里的图片不会原样上传。
     *
     * 这是挂起版本：失败通过 [Result] 返回，Web/其它通道的语义不变。
     * 聊天页的后台版本见 [startBackgroundCompression]，两者共用同一份快照与提交校验。
     */
    suspend fun compressConversation(
        conversationId: Uuid,
        additionalPrompt: String,
        keepRecentMessages: Int = 0,
    ): Result<Unit> = runCatching {
        withLoadedSession<Unit>(conversationId) { session ->
            // 生成中的回复还没写完，压缩不到它，检查点也不能插到它前面
            check(!session.isGenerating) { context.getString(R.string.chat_page_compress_blocked_generating) }

            val conversation = session.state.value
            val snapshot = compressionSnapshotOf(conversation, keepRecentMessages, session.incarnation)
            val summary = generateCompressionSummaryOnce(
                conversation = conversation,
                snapshot = snapshot,
                additionalPrompt = additionalPrompt,
            )

            // 原消息原样保留，摘要插在压缩范围的最后一条消息之后。摘要生成期间对话可能已变化，
            // 因此按节点定位插入到最新状态；范围内被改过、边界已有检查点就丢弃，不写旧结果。
            session.tryUpdate { latest ->
                when (val commit = latest.compressionCommit(snapshot, session.incarnation)) {
                    is CompressionCommit.Ready -> {
                        // 期间新开始的一轮生成正写进压缩范围：摘要不含它的最终内容，丢弃
                        // （replyMessageId 在一轮结束后仍保留上一条回复，只有生成中才算数）
                        val streaming = session.replyMessageId.value.takeIf { session.isGenerating }
                        if (snapshot.coversStreamingMessage(streaming)) {
                            throw IllegalStateException(context.getString(R.string.chat_page_compress_conversation_changed))
                        }
                        latest.insertContextCheckpoint(
                            afterNodeId = commit.afterNodeId,
                            summary = summary,
                        )?.copy(chatSuggestions = emptyList())
                            ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_conversation_changed))
                    }

                    CompressionCommit.Changed, CompressionCommit.AlreadyCompressed ->
                        throw IllegalStateException(context.getString(R.string.chat_page_compress_conversation_changed))
                }
            }
            session.flush()
        }
    }

    // ---- 后台压缩 ----

    // 同一个会话同一时间只跑一个手动压缩，重复点击返回同一个任务
    private val compressionLock = Any()
    private val compressionJobs = mutableMapOf<Uuid, Job>()

    private val _compressionStates = MutableStateFlow<Map<Uuid, CompressionState>>(emptyMap())

    /** 每个会话最近一次手动压缩的状态（运行中/成功/失败/丢弃/取消），供聊天页显示 */
    val compressionStates: StateFlow<Map<Uuid, CompressionState>> = _compressionStates.asStateFlow()

    /**
     * 手动压缩转后台：非挂起启动，任务跑在 [AppScope] 里，聊天页/对话/助手切换都不会取消它。
     *
     * - 该会话已有压缩在跑时直接返回同一个 [Job]，不会再发一次模型请求。
     * - 失败由这里报错（[addError]）并发通知，调用方不需要（也不应该）再接 `.onFailure`。
     * - 提交前核对开始时的边界快照：范围内被改过、会话被删过就丢弃旧摘要，绝不覆盖新内容；
     *   范围之后追加的消息原样保留。结果通过 [compressionStates] 和 [AppEvent] 通知出去。
     */
    fun startBackgroundCompression(
        conversationId: Uuid,
        additionalPrompt: String,
        keepRecentMessages: Int = 0,
    ): Job = synchronized(compressionLock) {
        if (sessionManager.get(conversationId)?.let { it.isChangingOwner || it.isAutoFiling } == true) {
            addError(IllegalStateException(context.getString(R.string.r21_assistant_busy)), conversationId)
            return@synchronized Job().apply { complete() }
        }
        val running = compressionJobs[conversationId]?.takeIf { it.isActive }
        if (running != null) return@synchronized running

        val taskId = Uuid.random()
        pruneFinishedCompressionStates()
        _compressionStates.update {
            it + (conversationId to CompressionState(
                taskId = taskId,
                outcome = null,
                keepRecentMessages = keepRecentMessages,
                startedAt = System.currentTimeMillis(),
            ))
        }

        val job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runBackgroundCompression(
                conversationId = conversationId,
                taskId = taskId,
                additionalPrompt = additionalPrompt,
                keepRecentMessages = keepRecentMessages,
            )
        }
        compressionJobs[conversationId] = job
        job.invokeOnCompletion {
            synchronized(compressionLock) {
                if (compressionJobs[conversationId] === job) compressionJobs.remove(conversationId)
            }
        }
        job.start()
        job
    }

    /** 取消这个会话的后台压缩；没有在跑的任务时返回 false */
    fun cancelBackgroundCompression(conversationId: Uuid): Boolean {
        val job = synchronized(compressionLock) { compressionJobs[conversationId] } ?: return false
        job.cancel()
        return true
    }

    /** 会话删除/整条替换前调用：停掉压缩任务并丢掉它的状态，压缩结果不会再写进新会话 */
    private fun discardCompression(conversationId: Uuid) {
        synchronized(compressionLock) { compressionJobs.remove(conversationId) }?.cancel()
        _compressionStates.update { it - conversationId }
    }

    // 终态状态只留一小会儿给 UI 显示，不然每个压缩过的会话都会留一份
    private fun pruneFinishedCompressionStates() {
        val now = System.currentTimeMillis()
        _compressionStates.update { states ->
            states.filterValues { state ->
                state.isRunning || (state.finishedAt ?: now) > now - COMPRESSION_STATE_RETENTION_MS
            }
        }
    }

    private fun finishCompressionState(
        conversationId: Uuid,
        taskId: Uuid,
        outcome: CompressionOutcome,
        message: String?,
    ) {
        _compressionStates.update { states ->
            val existing = states[conversationId]
            // 迟到的旧任务不能盖掉新任务的状态
            if (existing != null && existing.taskId != taskId) {
                states
            } else {
                states + (conversationId to CompressionState(
                    taskId = taskId,
                    outcome = outcome,
                    message = message,
                    keepRecentMessages = existing?.keepRecentMessages ?: 0,
                    startedAt = existing?.startedAt ?: System.currentTimeMillis(),
                    finishedAt = System.currentTimeMillis(),
                ))
            }
        }
    }

    private suspend fun runBackgroundCompression(
        conversationId: Uuid,
        taskId: Uuid,
        additionalPrompt: String,
        keepRecentMessages: Int,
    ) {
        // 复用生成用的前台服务：App 切到后台时进程不被回收，压缩请求才能跑完。
        // 它只提供前台服务生命周期，任务本身仍是这个 AppScope 协程；系统/厂商杀进程照样会中断，
        // 中断时什么都没有写（摘要只在最后一次性提交），对话保持原样。
        var foregroundStarted = false
        var title = ""
        var outcome = CompressionOutcome.Failed
        var detail: String? = null
        try {
            withLoadedSession(conversationId) { session ->
                check(!session.isGenerating) { context.getString(R.string.chat_page_compress_blocked_generating) }

                val conversation = session.state.value
                val snapshot = compressionSnapshotOf(conversation, keepRecentMessages, session.incarnation)
                title = compressionTitle(conversation)

                foregroundStarted = ChatGenerationForegroundService.acquire(
                    context = context,
                    generationId = taskId,
                    conversationId = conversationId,
                )
                appEventBus.tryEmit(
                    AppEvent.ChatCompressionStarted(
                        conversationId = conversationId,
                        taskId = taskId,
                        title = title,
                    )
                )

                val statusText = context.getString(R.string.chat_page_compressing)
                session.processingStatus.value = statusText
                val summary = try {
                    generateCompressionSummaryOnce(
                        conversation = conversation,
                        snapshot = snapshot,
                        additionalPrompt = additionalPrompt,
                    )
                } finally {
                    session.processingStatus.compareAndSet(statusText, null)
                }

                // 请求期间内容可能已经变了：这里只做校验后的一次原子插入，绝不整份写回旧快照。
                // 范围内被改动/边界已有检查点 → 丢弃；范围之后追加的消息原样保留。
                currentCoroutineContext().ensureActive()
                val committed = session.tryUpdate { latest ->
                    when (val commit = latest.compressionCommit(snapshot, session.incarnation)) {
                        is CompressionCommit.Ready -> {
                            // 生成中且正写进压缩范围才丢弃；尾部的新一轮不影响摘要
                            val streaming = session.replyMessageId.value.takeIf { session.isGenerating }
                            if (snapshot.coversStreamingMessage(streaming)) {
                                null
                            } else {
                                latest.insertContextCheckpoint(
                                    afterNodeId = commit.afterNodeId,
                                    summary = summary,
                                )?.copy(chatSuggestions = emptyList())
                            }
                        }

                        CompressionCommit.Changed, CompressionCommit.AlreadyCompressed -> null
                    }
                }
                if (committed != null) {
                    session.flush()
                    outcome = CompressionOutcome.Succeeded
                } else {
                    outcome = CompressionOutcome.Discarded
                    detail = context.getString(R.string.chat_page_compress_conversation_changed)
                }
            }
        } catch (e: CancellationException) {
            outcome = CompressionOutcome.Cancelled
            throw e
        } catch (e: Exception) {
            detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
            outcome = CompressionOutcome.Failed
            Log.w(TAG, "background compression $taskId failed", e)
            addError(
                error = e,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_compress_conversation),
            )
        } finally {
            // 取消时也会走到这里；下面都是非挂起调用，不会因为已经取消而抛出来
            if (foregroundStarted) ChatGenerationForegroundService.release(context, taskId)
            finishCompressionState(conversationId, taskId, outcome, detail)
            appEventBus.tryEmit(
                AppEvent.ChatCompressionEnded(
                    conversationId = conversationId,
                    taskId = taskId,
                    title = title,
                    outcome = outcome,
                    detail = detail,
                )
            )
            if (outcome == CompressionOutcome.Discarded) {
                addError(
                    error = IllegalStateException(detail),
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_compress_conversation),
                )
            }
        }
    }

    /** 通知里指认对话用的名字：有标题用标题，没有就用助手名 */
    private fun compressionTitle(conversation: Conversation): String {
        if (conversation.title.isNotBlank()) return conversation.title
        val settings = settingsStore.settingsFlow.value
        return settings.getAssistantOf(conversation).name.ifBlank {
            context.getString(R.string.assistant_page_default_assistant)
        }
    }

    /**
     * 校验可以压缩的范围并取快照。不满足条件时抛带提示文案的异常（与原有失败提示一致）。
     */
    private fun compressionSnapshotOf(
        conversation: Conversation,
        keepRecentMessages: Int,
        incarnation: Int,
    ): CompressionSnapshot {
        val nodes = conversation.messageNodes
        val checkpointIndex = nodes.indexOfLast { it.currentMessage.isContextCheckpoint }
        // 压缩范围是 (checkpointIndex, endIndex)，之后的节点原样保留
        val endIndex = nodes.size - keepRecentMessages.coerceAtLeast(0)
        // 上一个检查点之后没有可压缩的消息
        if (endIndex <= checkpointIndex + 1) {
            throw IllegalStateException(
                context.getString(
                    if (keepRecentMessages > 0) {
                        R.string.chat_page_compress_not_enough_messages
                    } else {
                        R.string.chat_page_compress_nothing_to_compress
                    }
                )
            )
        }
        // 未完成的工具调用没有结果，请求会被服务端拒绝；压到检查点之前也再不会执行。
        // 保留区里的不发给压缩请求，压缩后照常留在检查点之后，不用检查
        check(nodes.subList(checkpointIndex + 1, endIndex).none { node ->
            node.currentMessage.getTools().any { it.isPending || it.canResumeExecution }
        }) { context.getString(R.string.chat_page_compress_handle_pending_tools) }

        return conversation.compressionSnapshot(endIndex, incarnation)
            ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_nothing_to_compress))
    }

    /**
     * 生成摘要。没配置压缩模型时沿用对话模型、同一套请求组装，才能命中提示词缓存；
     * 配置了独立压缩模型时，摘要请求改由它生成。
     */
    private suspend fun generateCompressionSummaryOnce(
        conversation: Conversation,
        snapshot: CompressionSnapshot,
        additionalPrompt: String,
    ): String {
        val compressionSettings = settingsStore.awaitLoaded()
        val compressionModel = compressionSettings.autoCompress.modelId
            ?.let { compressionSettings.findModelById(it) }
        val turn = requestFactory.createContext(compressionSettings, conversation, compressionModel)
        return requestFactory.generateCompressionSummary(
            turn = turn,
            messages = conversation.currentMessages.take(snapshot.endIndex),
            compressPrompt = buildCompressPrompt(
                additionalPrompt = additionalPrompt,
                locale = Locale.getDefault().displayName,
            ),
        )
    }

    // ---- 聊天页配置 ----

    /**
     * 聊天页对助手的修改：会话持有的字段只改当前会话，其余写回助手设置。
     *
     * @param update 接收会话视角下的助手，返回修改后的助手
     */
    suspend fun updateChatAssistant(conversationId: Uuid, update: (Assistant) -> Assistant) {
        withLoadedSession(conversationId) { session ->
            val settings = settingsStore.awaitLoaded()
            val conversation = session.state.value
            val stored = settings.getStoredAssistantOf(conversation)
            val updated = update(settings.getAssistantOf(conversation))
            val assistant = updated.withoutConversationFields(conversation, stored)
            session.updateMetadata(
                transform = { it.withAssistantUpdate(updated, settings) },
                persist = conversationRepo::updateConversationConfig,
            )
            if (assistant != stored) {
                settingsStore.update { latest ->
                    latest.copy(assistants = latest.assistants.map { if (it.id == assistant.id) assistant else it })
                }
            }
        }
    }

    /**
     * 切换搜索方式，传 null 的一项保持不变。
     * 草稿与已保存会话都只修改自己的配置，不改变其他助手共用的模型定义。
     */
    suspend fun updateChatSearch(
        conversationId: Uuid,
        enableWebSearch: Boolean? = null,
        builtInSearch: Boolean? = null,
    ) {
        withLoadedSession(conversationId) { session ->
            val settings = settingsStore.awaitLoaded()
            session.updateMetadata(
                transform = { latest ->
                    val bound = latest.bindConfig(settings)
                    bound.copy(config = bound.config?.let { config ->
                        config.copy(
                            enableWebSearch = enableWebSearch ?: config.enableWebSearch,
                            builtInSearch = builtInSearch ?: config.builtInSearch,
                        )
                    })
                },
                persist = conversationRepo::updateConversationConfig,
            )
        }
    }

    // 工作目录和配置一起按列落库；还没落过库的新会话先留在内存里，发出第一条消息时一起写入
    suspend fun updateWorkspaceCwd(conversationId: Uuid, workspaceCwd: String?) {
        withLoadedSession(conversationId) { session ->
            session.updateMetadata(
                transform = { it.copy(workspaceCwd = workspaceCwd) },
                persist = conversationRepo::updateConversationConfig,
            )
        }
    }

    suspend fun updateConversationSystemPrompt(conversationId: Uuid, systemPrompt: String?) {
        updateColumn(
            conversationId = conversationId,
            transform = { it.copy(customSystemPrompt = systemPrompt) },
            persist = { conversationRepo.updateCustomSystemPrompt(conversationId, systemPrompt) },
        )
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) {
        updateColumn(
            conversationId = conversationId,
            // 用户手改的标题：标记成手改并清掉待精修的临时标题，之后晚到的自动标题不会盖掉它
            transform = { it.withManualTitle(title) },
            persist = { conversationRepo.updateTitle(conversationId, title) },
        )
    }

    /**
     * 用户在聊天页改的单列字段：已落库的会话只写这一列，生成中也不会顺带写入还没生成完的回复；
     * 还没落过库的新会话整份落库，留下记录（没有内容的新会话仍然不写）。
     */
    private suspend fun updateColumn(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
        persist: suspend () -> Unit,
    ) {
        withLoadedSession(conversationId) { session ->
            session.updateMetadata(transform, persist = { persist() })
            if (!session.existsInDatabase) session.flush()
        }
    }

    // ---- 会话元数据 ----

    private suspend fun updateConversationMetadata(
        conversationId: Uuid,
        transform: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        withLoadedSession(conversationId, createIfMissing = false) { session ->
            session.updateMetadata(transform, persist)
        }
    }

    suspend fun toggleConversationPinned(conversationId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            transform = { it.copy(isPinned = !it.isPinned) },
            persist = { conversationRepo.updatePinStatus(conversationId, it.isPinned) },
        )
    }

    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        val settings = settingsStore.awaitLoaded()
        val target = settings.assistants.firstOrNull { it.id == assistantId }
            ?: throw IllegalArgumentException(context.getString(R.string.r21_assistant_missing))
        withLoadedSession(conversationId, createIfMissing = false) { session ->
            if (session.state.value.assistantId == assistantId) return@withLoadedSession
            var oldKeys: Set<String> = emptySet()
            synchronized(compressionLock) {
                synchronized(session) {
                    val childTasks = subagentRunner.list().filter { it.conversationId == conversationId }
                    val tools = backgroundToolManager.list(BackgroundToolOwner.of(conversationId))
                    val shells = backgroundShellManager.tasksOf(conversationId)
                    check(!session.isChangingOwner && !session.isAutoFiling && session.getJob() == null &&
                        !session.awaitingNetworkRecovery && !session.state.value.isAwaitingToolApproval &&
                        session.inbox.state.value.messages.isEmpty() && session.inbox.state.value.inFlight == null &&
                        compressionJobs[conversationId]?.isActive != true &&
                        childTasks.none { !it.isFinished } && tools.none { !it.isFinished } &&
                        shells.none { it.status == BackgroundShellStatus.RUNNING }) {
                        context.getString(R.string.r21_assistant_busy)
                    }
                    check(session.existsInDatabase) { "Only a saved conversation can be moved" }
                    oldKeys = (childTasks.map { "subagent:${it.id}" } +
                        tools.map { "background_tool:${it.id}" } + shells.map { "background_shell:${it.id}" }).toSet()
                    session.isChangingOwner = true
                }
            }
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    // Reject late notices from old tasks even after a restart. Tool results remain readable
                    // in the explicitly migrated history; they cannot silently start or steer a new turn.
                    session.changeOwnership(
                        transform = { it.migrateToAssistant(target, settings) },
                        persist = conversationRepo::updateConversation,
                    )
                    try {
                        session.inbox.migrateOwner(assistantId, oldKeys)
                    } catch (e: InboxPersistenceException) {
                        // DB and in-memory owner have moved; report the queue write, not a failed move.
                        addError(e, conversationId)
                    }
                    clearRecovery(conversationId)
                    pendingStreamPersists.remove(conversationId)?.cancel()
                lastStreamPersistAt.remove(conversationId)
                }
            } finally {
                session.isChangingOwner = false
            }
        }
    }

    private val recoveredLineageAssistants = ConcurrentHashMap.newKeySet<Uuid>()

    fun canAutoFileConversation(conversationId: Uuid): Boolean = synchronized(compressionLock) {
        val session = sessionManager.get(conversationId)
        (session == null || !session.isInUse) && compressionJobs[conversationId]?.isActive != true &&
            subagentRunner.list().none { it.conversationId == conversationId && !it.isFinished } &&
            backgroundToolManager.list(BackgroundToolOwner.of(conversationId)).none { !it.isFinished } &&
            backgroundShellManager.tasksOf(conversationId).none { it.status == BackgroundShellStatus.RUNNING }
    }

    suspend fun applyAutoFileMove(
        conversationId: Uuid, assistantId: Uuid, expectedUpdateAt: Long,
        expectedFolderId: Uuid?, folderId: Uuid?, undo: Boolean = false,
    ): Boolean {
        if (!canAutoFileConversation(conversationId)) return false
        if (settingsStore.awaitLoaded().assistants.none { it.id == assistantId }) return false
        if (!undo && System.currentTimeMillis() - expectedUpdateAt < 24L * 60 * 60 * 1_000) return false
        return sessionManager.withSession(conversationId) { session ->
            session.inbox.restore()
            session.updateOrganizationMetadata(
                transform = { it.copy(folderId = folderId) },
                predicate = { it.assistantId == assistantId && it.updateAt.toEpochMilli() == expectedUpdateAt &&
                    it.folderId == expectedFolderId && !it.isPinned && !it.folderAssignmentLocked },
                persist = { conversationRepo.applyFolderAssignment(conversationId, assistantId,
                    expectedUpdateAt, expectedFolderId, folderId) },
                unloadedPersist = { conversationRepo.applyFolderAssignment(conversationId, assistantId,
                    expectedUpdateAt, expectedFolderId, folderId) },
            )
        }
    }

    /** One bounded, local-only recovery pass per assistant per process. Never rewrites message IDs or text. */
    suspend fun recoverConversationLineages(assistantId: Uuid) {
        if (!recoveredLineageAssistants.add(assistantId)) return
        try {
            val assistant = settingsStore.awaitLoaded().assistants.firstOrNull { it.id == assistantId } ?: return
            val summaries = conversationRepo.observeOrganizationSummaries(assistantId).first()
                .sortedWith(compareBy<Conversation> { it.createAt }.thenBy { it.id.toString() }).take(512)
            val openings = ArrayList<Conversation>(summaries.size)
            for (summary in summaries) {
                currentCoroutineContext().ensureActive()
                if (canAutoFileConversation(summary.id)) conversationRepo.getConversationOpening(summary.id)?.let(openings::add)
            }
            val inferred = ConversationLineageResolver.infer(openings, assistant.presetMessages.mapTo(HashSet()) { it.id })
            val byId = openings.associateBy { it.id }
            for ((id, root) in inferred) {
                val before = byId[id] ?: continue
                if (before.lineageId != null || !canAutoFileConversation(id)) continue
                sessionManager.withSession(id) { session ->
                    session.inbox.restore()
                    session.updateOrganizationMetadata(
                        transform = { it.copy(lineageId = root) },
                        predicate = { it.assistantId == assistantId && it.updateAt == before.updateAt && it.lineageId == null },
                        persist = { conversationRepo.setLineageIfUnchanged(before, root) },
                        unloadedPersist = { conversationRepo.setLineageIfUnchanged(before, root) },
                    )
                }
            }
        } catch (e: Exception) {
            recoveredLineageAssistants.remove(assistantId)
            throw e
        }
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     * 有 session 的会话先改内存，免得之后整份落库时写回旧的文件夹；没有的只改这一列，不必加载整个会话。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessionManager.get(conversationId) == null) {
            conversationRepo.updateConversationFolderId(conversationId, folderId)
            // 写库期间页面可能刚好打开了这个会话，加载时读到的可能是旧值，再同步一次内存
            if (sessionManager.get(conversationId) == null) return
        }
        updateConversationMetadata(
            conversationId = conversationId,
            transform = { it.copy(folderId = folderId, folderAssignmentLocked = true) },
            persist = { conversationRepo.updateConversationFolderId(conversationId, folderId) },
        )
    }

    /**
     * 还没开始的新会话跟随抽屉里选中的文件夹，首次落库时归入；已经落库的会话不受影响，移动走 [moveConversationToFolder]。
     */
    suspend fun updateDraftConversationFolder(conversationId: Uuid, folderId: Uuid?) {
        val folder = folderId?.let { folderRepository.getFolderById(it) }
        withLoadedSession(conversationId) { session ->
            session.updateDraft { latest ->
                // 文件夹属于助手：已被删除或不属于会话的助手时不归入
                val target = folder?.takeIf { it.assistantId == latest.assistantId }?.id
                if (latest.folderId == target) latest else latest.copy(
                    folderId = target, folderAssignmentLocked = target != null || latest.folderAssignmentLocked,
                )
            }
        }
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessionManager.snapshot().any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     * 已加载的会话先改内存再删库，之后落库不会写回已删除的文件夹。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessionManager.snapshot()
            .filter { it.isInitialized && it.state.value.folderId == folderId }
            .forEach { session ->
                // 这一列由下面删文件夹时统一清空，这里只改内存，不让会话因此变成有未落库的修改
                session.updateMetadata(
                    transform = { if (it.folderId == folderId) it.copy(folderId = null) else it },
                    persist = {},
                )
            }
        folderRepository.deleteFolder(folderId)
    }

    // ---- 删除会话 ----

    /**
     * 删除会话：停掉生成后在会话的写锁内删库，内存换成一个还没落过库的新会话。
     * 还停在这个会话上的页面看到的是空会话，之后再发消息会新建记录，不会写回已删除的那条。
     */
    suspend fun deleteConversation(conversationId: Uuid) {
        // 后台压缩先停掉：它的结果不能再写进下面这个空会话（incarnation 也会让迟到的提交落空）
        discardCompression(conversationId)
        val session = sessionManager.getOrCreate(conversationId)
        discardQueuedMessages(session)
        session.replace(newConversation(conversationId), existsInDatabase = false) { previous ->
            conversationRepo.getConversationById(conversationId)?.let { conversationRepo.deleteConversation(it) }
            // 数据库那份不知道只在内存里出现过的附件
            previous?.let { filesManager.deleteChatFiles(it.files) }
        }
        sessionManager.removeIfIdle(session)
        // 后台命令随会话一起终止, 撤销删除也不会恢复; 终止要等宽限期, 不阻塞删除
        appScope.launch { backgroundShellManager.killConversation(conversationId) }
    }

    /** 撤销删除：写回删除前的会话，还停在这个会话上的页面也一并换回 */
    suspend fun restoreConversation(conversation: Conversation) {
        discardCompression(conversation.id)
        val session = sessionManager.getOrCreate(conversation.id)
        session.replace(conversation, existsInDatabase = true) {
            conversationStore.write(previous = null, current = conversation)
        }
        sessionManager.removeIfIdle(session)
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        val persisted = conversationRepo.getConversationsOfAssistant(assistantId).first().map { it.id }
        val loaded = sessionManager.snapshot()
            .filter { it.isInitialized && it.state.value.assistantId == assistantId }
            .map { it.id }
        (persisted + loaded).distinct().forEach { deleteConversation(it) }
    }

    private fun discardQueuedMessages(session: ConversationSession) {
        clearRecovery(session.id)
        session.awaitingNetworkRecovery = false
        session.inbox.pause(InboxPauseReason.USER)
        session.inbox.state.value.inFlight?.let { session.inbox.acknowledge(it.id) }
        session.inbox.state.value.messages.forEach { queued ->
            (session.inbox.remove(queued.id) as? QueuedMessage)?.let(::cleanupQueuedAttachments)
        }
        session.inbox.takeNotices()
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            sessionManager.withSession<Unit>(conversationId) { session ->
                try {
                    ensureInitialized(session)
                    val settings = settingsStore.awaitLoaded()

                    val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n\n") { it.text }
                        .trim()

                    if (messageText.isBlank()) return@withSession

                    // Set loading state for translation
                    val loadingText = context.getString(R.string.translating)
                    session.update { it.withTranslation(message.id, loadingText) }

                    translationHandler.translateText(
                        settings = settings,
                        sourceText = messageText,
                        targetLanguage = targetLanguage
                    ) { translatedText ->
                        // Update translation field in real-time
                        session.update { it.withTranslation(message.id, translatedText) }
                    }.collect { /* Final translation already handled in onStreamUpdate */ }

                    session.flush()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Clear translation field on error
                    if (session.isInitialized) session.update { it.withTranslation(message.id, null) }
                    addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
                }
            }
        }
    }

    suspend fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        mutate(conversationId) { it.withTranslation(messageId, null) }
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        val settings = settingsStore.awaitLoaded()

        mutate(conversationId) { conversation ->
            conversation.editMessage(messageId, parts.replaceUserRegexes(settings.getStoredAssistantOf(conversation)))
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val source = withLoadedSession(conversationId) { it.state.value }
        val existingTitles = conversationRepo.getTitlesOfAssistant(source.assistantId)
        val fork = source.fork(messageId, existingTitles, copyFile = ::copyChatFile)
            ?: throw NotFoundException("Message not found")
        // 新会话直接落库，打开时再从数据库加载
        val forkConversation = conversationStore.prepareWrite()(fork)
        conversationRepo.insertConversation(forkConversation)
        memoryConsolidationScheduler.onConversationForked(forkConversation)
        return forkConversation
    }

    private fun copyChatFile(url: String): String =
        filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()?.toString() ?: url

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        mutate(conversationId) { conversation ->
            val targetNode = conversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NotFoundException("Message node not found")
            if (selectIndex !in targetNode.messages.indices) {
                throw BadRequestException("Invalid selectIndex")
            }
            if (targetNode.selectIndex == selectIndex) return@mutate conversation
            conversation.selectBranch(nodeId, selectIndex)
        }
    }

    // 收藏记录在单独的表里，节点上的标记只用于显示，不需要落库
    suspend fun setNodeFavorite(conversationId: Uuid, nodeId: Uuid, favorite: Boolean) {
        val session = sessionManager.get(conversationId)?.takeIf { it.isInitialized } ?: return
        session.updateMetadata(transform = { it.withNodeFavorite(nodeId, favorite) }, persist = {})
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        mutate(conversationId) { conversation ->
            conversation.deleteMessage(messageId)
                ?: if (failIfMissing) throw NotFoundException("Message not found") else conversation
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessionManager.get(conversationId) ?: return
        val jobs = synchronized(session) {
            clearRecovery(conversationId)
            session.awaitingNetworkRecovery = false
            session.processingStatus.value = null
            session.inbox.pause(InboxPauseReason.USER)
            session.cancelJobs()
        }
        if (jobs.isNotEmpty()) {
            jobs.forEach { it.join() }
            if (session.isInitialized) finishInterruptedPendingTools(session)
        }
        // A timed-out/stopped recovery may have outlived its old idle check without an active job.
        session.scheduleIdleCheck()
    }
}
