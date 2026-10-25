package me.rerere.rikkahub.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeMeta
import me.rerere.rikkahub.data.db.dao.MessageNodeSnapshot
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "ConversationRepository"

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40

        /** 节点分页读取的页大小：整页会进一个 CursorWindow，保持原来的 64 行。 */
        private const val NODE_PAGE_SIZE = 64

        /** 大行回退时每片的 code point 数（64Ki），按 SQLite `length()`/`substr()` 的计数字符。 */
        private const val NODE_CHUNK_CODEPOINTS = 64 * 1024
    }

    suspend fun getConversationAssistantId(id: String): String? = conversationDAO.getAssistantId(id)

    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl))

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun getTitlesOfAssistant(assistantId: Uuid): Set<String> =
        conversationDAO.getTitlesOfAssistant(assistantId.toString()).toSet()

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(
        assistantId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getUnfiledConversationsOfAssistantPaging(
                assistantId = assistantId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(
        folderId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getConversationsOfFolderPaging(
                folderId = folderId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else {
            null
        }
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun countConversationsByAssistant(): Map<Uuid, Int> {
        return conversationDAO.countByAssistant().mapNotNull { row ->
            runCatching { Uuid.parse(row.assistantId) }.getOrNull()?.let { it to row.count }
        }.toMap()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        // 搜索引是派生数据，写失败不能连带会话保存失败
        runCatching { messageFtsManager.indexConversation(conversation) }
            .onFailure { Log.w(TAG, "indexConversation failed for ${conversation.id}", it) }
    }

    suspend fun updateConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            // 不再先删光节点。saveMessageNodes 自己删除已经消失的节点、只写内容或顺序变化的节点，
            // 先删会让那里的旧行快照永远是空的，等于每次落库都把整段会话重写一遍
            // （流式生成期间每 1.5 秒一次）。
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        // 搜索引是派生数据，写失败不能连带会话保存失败
        runCatching { messageFtsManager.indexConversation(conversation) }
            .onFailure { Log.w(TAG, "indexConversation failed for ${conversation.id}", it) }
    }

    suspend fun deleteConversation(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        filesManager.deleteChatFiles(fullConversation.files)
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: Uuid? = null,
    ) = messageFtsManager.search(keyword, sort, assistantId?.let { listOf(it.toString()) })

    /** 只在 [assistantIds] 这些助手的对话里搜索 */
    suspend fun searchMessagesOfAssistants(keyword: String, assistantIds: Set<Uuid>) =
        messageFtsManager.search(keyword, MessageSearchSort.RELEVANCE, assistantIds.map { it.toString() })

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        // 表结构不对时只清数据是没用的，先确保 message_fts 真的能用
        messageFtsManager.ensureSchema()
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    /**
     * 启动自检：会话有内容但索引是空的（典型是刚导入旧备份、或旧版本没建过索引）时重建一次。
     * 已经有索引就不动，避免每次启动都扫全库。
     */
    suspend fun ensureMessageIndex(): Boolean {
        if (countConversations() <= 0) return false
        // 表不存在或分词器不对时，count() 本身也可能失败，先保证结构可用
        val recreated = messageFtsManager.ensureSchema()
        if (!recreated && messageFtsManager.count() > 0) return false
        rebuildAllIndexes()
        return true
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        // 这里只序列化会话 metadata（nodes 固定为 "[]"，消息节点在单独的表里由 saveMessageNodes 落库）。
        // 因此“消息里还有内联 data: 图片”与本函数的输出无关：以前在这里 require 直接抛错，
        // 会让一个塞不进去的老 data URI 把这个会话变成永久无法保存 / 无法删除。
        // 不能把这些数据当损坏丢掉或换成预览 —— 原样保留，后续仍由 FilesManager 尽力外置。
        if (conversation.messageNodes.any { it.messages.any { message -> message.hasBase64Part() } }) {
            Log.w(TAG, "Conversation ${conversation.id} still holds inline base64 media; keeping it as-is")
        }
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]", // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
            config = conversation.config?.let { JsonInstant.encodeToString(it) } ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            config = conversationEntity.config.ifEmpty { null }?.let { JsonInstant.decodeFromString(it) },
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun updatePinStatus(conversationId: Uuid, isPinned: Boolean) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = isPinned,
        )
    }

    suspend fun updateConversationAssistant(conversationId: Uuid, assistantId: Uuid) {
        conversationDAO.updateAssistantId(conversationId.toString(), assistantId.toString())
    }

    /**
     * 只更新会话持有的配置（含注入绑定和工作目录），不重写消息节点。
     */
    suspend fun updateConversationConfig(conversation: Conversation) {
        conversationDAO.updateConfig(
            id = conversation.id.toString(),
            config = conversation.config?.let { JsonInstant.encodeToString(it) } ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
        )
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) {
        conversationDAO.updateTitle(conversationId.toString(), title)
    }

    suspend fun updateCustomSystemPrompt(conversationId: Uuid, customSystemPrompt: String?) {
        conversationDAO.updateCustomSystemPrompt(conversationId.toString(), customSystemPrompt ?: "")
    }

    suspend fun updateChatSuggestions(conversationId: Uuid, chatSuggestions: List<String>) {
        conversationDAO.updateChatSuggestions(
            id = conversationId.toString(),
            chatSuggestions = JsonInstant.encodeToString(chatSuggestions),
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    /** 读取会话尾部消息，供子代理按会话 id 查阅其他聊天。 */
    suspend fun readConversationTail(
        conversationId: String,
        limit: Int = 20,
    ): List<Pair<String, String>> {
        val nodes = database.withTransaction {
            loadTailNodesResilient(
                limit = limit,
                chunkCodepoints = NODE_CHUNK_CODEPOINTS,
                readTail = { messageNodeDAO.getLastNodes(conversationId, it) },
                readTailMeta = { messageNodeDAO.getLastNodeMeta(conversationId, it) },
                readSlice = messageNodeDAO::getMessagesSlice,
            )
        }
        val result = mutableListOf<Pair<String, String>>()
        for (node in nodes) {
            val messages = runCatching {
                JsonInstant.decodeFromString<List<UIMessage>>(node.messages)
            }.getOrNull() ?: continue
            val selected = messages.getOrNull(node.selectIndex) ?: messages.lastOrNull() ?: continue
            val text = selected.toText().trim()
            if (text.isNotEmpty()) result += selected.role.name.lowercase() to text
        }
        return result
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        // Read pages in one transaction; decoding and file promotion must not hold SQLite locks.
        // 小会话走分页快路径；某页有大行（装不进 CursorWindow）时整体回退到 metadata + substr 分片，
        // 绝不跳页 —— 少读的节点会在下一次保存时被当成“已删除”。
        val entities = database.withTransaction {
            loadAllNodesResilient(
                pageSize = NODE_PAGE_SIZE,
                chunkCodepoints = NODE_CHUNK_CODEPOINTS,
                source = nodePageSource(conversationId),
            )
        }
        return withContext(Dispatchers.IO) {
            entities.map { entity ->
                val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    .externalizeLargeParts(filesManager)
                val nodeId = Uuid.parse(entity.id)
                MessageNode(nodeId, messages, entity.selectIndex, favoriteNodeIds.contains(nodeId))
            }
        }
    }

    /** 把 DAO 的三个底层查询接成 [NodePageSource]，供分页/分片两种读取共用。 */
    private fun nodePageSource(conversationId: String): NodePageSource = object : NodePageSource {
        override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> =
            messageNodeDAO.getNodesOfConversationPaged(conversationId, limit, offset)

        override suspend fun readMeta(): List<MessageNodeMeta> =
            messageNodeDAO.getNodeMeta(conversationId)

        override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int): String? =
            messageNodeDAO.getMessagesSlice(nodeId, start, length)
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        // 流式生成期间每 1.5 秒会落库一次，长会话全量删写太贵：只删掉不再存在的节点，
        // 只写顺序或内容真正变化的节点。比较的是同一套 JSON 编码，内容没变就跳过。
        //
        // getSnapshots 一次读全部行，同样可能撞上 CursorWindow：溢出时整体走分片回退，
        // 保证“内容没变 / 节点被删”的判断绝不出自残缺数据（残缺快照会导致误重写或误删）。
        val existing = loadSnapshotsResilient(
            readSnapshots = { messageNodeDAO.getSnapshots(conversationId) },
            fallback = { snapshotOfAllNodes(conversationId) },
        )
        val writes = messageNodeWrites(
            conversationId = conversationId,
            nodes = nodes,
            existing = existing,
        )
        if (writes.removedIds.isNotEmpty()) messageNodeDAO.deleteByIds(writes.removedIds)
        if (writes.upserts.isNotEmpty()) messageNodeDAO.insertAll(writes.upserts)
    }

    /** 分片读取整表节点后转成快照，与 [MessageNodeDAO.getSnapshots] 的字段顺序/内容一一对应。 */
    private suspend fun snapshotOfAllNodes(conversationId: String): List<MessageNodeSnapshot> =
        loadNodesChunked(
            source = nodePageSource(conversationId),
            chunkCodepoints = NODE_CHUNK_CODEPOINTS,
        ).map { node ->
            MessageNodeSnapshot(
                id = node.id,
                nodeIndex = node.nodeIndex,
                messages = node.messages,
                selectIndex = node.selectIndex,
            )
        }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)

/** 一次节点落库真正要做的改动：删掉消失的节点，写入新增或内容/顺序变化的节点。 */
internal data class MessageNodeWrites(
    val removedIds: List<String>,
    val upserts: List<MessageNodeEntity>,
)

/**
 * 比较库里已有的节点快照与这次要落库的节点，算出需要写库的部分。
 *
 * 内容按 JSON 文本比较（两边用同一套编码），没变就跳过：流式生成期间每次落库都会走到这里，
 * 长会话不能每次重写全部节点。抽成纯函数是为了能脱离 Room 测试。
 */
internal fun messageNodeWrites(
    conversationId: String,
    nodes: List<MessageNode>,
    existing: List<MessageNodeSnapshot>,
): MessageNodeWrites {
    val entities = nodes.mapIndexed { index, node ->
        MessageNodeEntity(
            id = node.id.toString(),
            conversationId = conversationId,
            nodeIndex = index,
            messages = JsonInstant.encodeToString(node.messages),
            selectIndex = node.selectIndex,
        )
    }
    val previousById = existing.associateBy { it.id }
    val currentIds = entities.mapTo(HashSet()) { it.id }
    val removedIds = previousById.keys - currentIds
    val upserts = entities.filter { entity ->
        val previous = previousById[entity.id]
        previous == null || previous.nodeIndex != entity.nodeIndex ||
            previous.selectIndex != entity.selectIndex || previous.messages != entity.messages
    }
    return MessageNodeWrites(removedIds = removedIds.toList(), upserts = upserts)
}

/** Upgrade legacy fork.14 cache references without replacing canonical text by a preview. */
private suspend fun List<UIMessage>.externalizeLargeParts(
    filesManager: FilesManager,
): List<UIMessage> = withContext(Dispatchers.IO) {
    suspend fun normalize(part: UIMessagePart): UIMessagePart = when (part) {
        is UIMessagePart.Text -> part.copy(text = filesManager.restoreLegacyText(part.text))
        is UIMessagePart.Tool -> part.copy(
            input = filesManager.restoreLegacyText(part.input),
            output = part.output.map { normalize(it) },
        )
        is UIMessagePart.Image -> part.copy(url = filesManager.persistHistoricalMedia(part.url))
        is UIMessagePart.Video -> part.copy(url = filesManager.persistHistoricalMedia(part.url))
        is UIMessagePart.Audio -> part.copy(url = filesManager.persistHistoricalMedia(part.url))
        is UIMessagePart.Document -> part.copy(url = filesManager.persistHistoricalMedia(part.url, part.mime))
        else -> part
    }
    map { message -> message.copy(parts = message.parts.map { normalize(it) }) }
}
