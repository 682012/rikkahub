package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.stability.ChatSnapshotCache
import me.rerere.rikkahub.service.stability.ChatSnapshotRetention
import kotlin.uuid.Uuid

/** Owns runtime sessions; loading and message dispatch belong to ChatService, persistence goes through [ConversationStore]. */
class ConversationSessionManager(
    private val scope: CoroutineScope,
    private val store: ConversationStore,
    private val createInitialConversation: (Uuid) -> Conversation,
    private val onGenerationFinished: (ConversationSession, Throwable?) -> Unit,
    // 会话没有页面引用也不在生成，被回收了
    private val onSessionRemoved: (Uuid) -> Unit = {},
    private val createInbox: (Uuid) -> ConversationInbox = { ConversationInbox() },
) {
    private val lock = Any()
    private val sessions = MutableStateFlow<Map<Uuid, ConversationSession>>(emptyMap())

    /**
     * 上次回收时留下的已加载内容，按真实会话 id 隔离。
     *
     * 回收后再打开同一个会话，会话对象（和它的 StateFlow）是新的：如果又从一个空壳占位开始，
     * 页面会先把空列表画出来，等读库完成才恢复——用户看到的就是「聊天记录瞬间清空/闪白」。
     * 用上一份可见内容当占位初值，读库结果随后照样覆盖它（isInitialized 仍为 false，
     * 加载完成前不接受修改），所以不会出现旧内容盖住新内容。
     * 只在已落库且已加载过时保留，删除/清空后不会留着旧内容（见 [ChatSnapshotRetention]）。
     */
    private val retained = ChatSnapshotCache<Uuid, Conversation>()

    fun get(conversationId: Uuid): ConversationSession? = sessions.value[conversationId]

    fun snapshot(): List<ConversationSession> = sessions.value.values.toList()

    fun getOrCreate(conversationId: Uuid): ConversationSession = synchronized(lock) {
        get(conversationId)?.let { return it }
        lateinit var session: ConversationSession
        session = ConversationSession(
            id = conversationId,
            initial = retained.take(conversationId) ?: createInitialConversation(conversationId),
            scope = scope,
            store = store,
            onIdle = { removeIfIdle(session) },
            inbox = createInbox(conversationId),
            onGenerationFinished = { id, cause ->
                if (get(id) === session) onGenerationFinished(session, cause)
            },
        )
        // Publish the actual registry, so collectors never observe a version before insertion.
        sessions.value = sessions.value + (conversationId to session)
        session.scheduleIdleCheck()
        session
    }

    fun acquire(conversationId: Uuid): ConversationSession = synchronized(lock) {
        getOrCreate(conversationId).also { it.acquire() }
    }

    fun release(conversationId: Uuid) {
        get(conversationId)?.release()
    }

    suspend fun <T> withSession(
        conversationId: Uuid,
        block: suspend (ConversationSession) -> T,
    ): T {
        val session = acquire(conversationId)
        try {
            return block(session)
        } finally {
            session.release()
        }
    }

    fun launchWithSession(
        conversationId: Uuid,
        block: suspend (ConversationSession) -> Unit,
    ): Job = scope.launch { withSession(conversationId, block) }

    internal fun removeIfIdle(session: ConversationSession) {
        val removed = synchronized(lock) {
            if (get(session.id) !== session || session.isInUse) return
            // Publish retention and registry removal atomically. A concurrent acquire must see
            // either the live session or its retained content, never an empty gap between them.
            if (ChatSnapshotRetention.shouldRetain(session.isInitialized, session.existsInDatabase)) {
                retained.put(session.id, session.state.value)
            } else {
                retained.remove(session.id)
            }
            sessions.value = sessions.value - session.id
            session
        }
        // Job completion can call back into the registry; never hold its lock during cleanup.
        removed.cleanup()
        onSessionRemoved(removed.id)
    }

    fun cleanup() {
        val removed = synchronized(lock) {
            retained.clear()
            snapshot().also { sessions.value = emptyMap() }
        }
        removed.forEach { it.cleanup() }
    }

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
        getOrCreate(conversationId).state

    /**
     * 会话内容是否已经就绪。false 时页面的空列表只是占位，必须当成加载中，
     * 不能当成「聊天记录被清空了」。
     */
    fun getConversationContentReadyFlow(conversationId: Uuid): StateFlow<Boolean> =
        getOrCreate(conversationId).contentReady

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> =
        getOrCreate(conversationId).processingStatus

    fun getReplyMessageIdFlow(conversationId: Uuid): StateFlow<Uuid?> =
        getOrCreate(conversationId).replyMessageId

    fun getGenerationStartedAtFlow(conversationId: Uuid): StateFlow<Long?> =
        getOrCreate(conversationId).generationStartedAt

    fun getInboxFlow(conversationId: Uuid): StateFlow<InboxState> =
        getOrCreate(conversationId).inbox.state

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        sessions.map { it[conversationId] }.distinctUntilChanged().flatMapLatest { session ->
            session?.generationJob ?: flowOf(null)
        }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = sessions.flatMapLatest { current ->
        if (current.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(current.values.map { session ->
                session.generationJob.map { session.id to it }
            }) { pairs ->
                pairs.filter { it.second != null }.toMap()
            }
        }
    }
}
