package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

private const val CREATE_FTS_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
        text,
        node_id UNINDEXED,
        message_id UNINDEXED,
        conversation_id UNINDEXED,
        title UNINDEXED,
        update_at UNINDEXED,
        tokenize = 'simple'
    )
"""

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    /**
     * 确认 message_fts 是可用结构（simple 分词器）。
     *
     * 从旧分支或旧备份导入的库可能带着不同分词器的 message_fts；CREATE VIRTUAL TABLE
     * IF NOT EXISTS 不会修正这种残留，查询就会全部落空。这里检测到不匹配时直接重建，
     * 返回 true 表示旧索引已经失效，调用方需要重新灌索引。
     */
    suspend fun ensureSchema(): Boolean = withContext(Dispatchers.IO) {
        val sql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'message_fts'"
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        val usable = sql != null &&
            sql.contains("tokenize", ignoreCase = true) &&
            sql.contains("simple", ignoreCase = true)
        if (usable) return@withContext false
        Log.w(TAG, "message_fts missing or incompatible tokenizer, recreating")
        db.execSQL("DROP TABLE IF EXISTS message_fts")
        db.execSQL(CREATE_FTS_SQL)
        true
    }

    suspend fun recreateSchema() = withContext(Dispatchers.IO) {
        db.execSQL("DROP TABLE IF EXISTS message_fts")
        db.execSQL(CREATE_FTS_SQL)
    }

    suspend fun count(): Long = withContext(Dispatchers.IO) {
        db.query("SELECT COUNT(*) FROM message_fts").use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        // 当前应该进入索引的消息：messageId -> (nodeId, text, updateAt)
        val current = LinkedHashMap<String, IndexedMessage>()
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    current[message.id.toString()] = IndexedMessage(
                        nodeId = node.id.toString(),
                        text = text,
                    )
                }
            }
        }
        val updateAt = conversation.updateAt.toEpochMilli().toString()
        // 同一个事务内完成差分：流式生成期间会话会被反复落库，不能留下“删干净但没写回”的空索引，
        // 也不能每次把整段会话重新分词（长会话会明显拖慢消息保存）。
        db.beginTransaction()
        try {
            val existing = HashMap<String, String>()
            db.query(
                "SELECT message_id, text FROM message_fts WHERE conversation_id = ?",
                arrayOf(conversationId),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    existing[cursor.getString(0)] = cursor.getString(1).orEmpty()
                }
            }
            for (messageId in existing.keys - current.keys) {
                db.execSQL(
                    "DELETE FROM message_fts WHERE conversation_id = ? AND message_id = ?",
                    arrayOf(conversationId, messageId),
                )
            }
            current.forEach { (messageId, message) ->
                if (existing[messageId] == message.text) return@forEach
                db.execSQL(
                    "DELETE FROM message_fts WHERE conversation_id = ? AND message_id = ?",
                    arrayOf(conversationId, messageId),
                )
                db.execSQL(
                    "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(
                        message.text,
                        message.nodeId,
                        messageId,
                        conversationId,
                        conversation.title,
                        updateAt,
                    ),
                )
            }
            // 标题或会话更新时间变化时，已有行的冗余字段也要刷新，否则搜索结果的标题和排序会停在旧值
            db.execSQL(
                "UPDATE message_fts SET title = ?, update_at = ? WHERE conversation_id = ?",
                arrayOf(conversation.title, updateAt, conversationId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun updateTitle(conversationId: String, title: String) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE message_fts SET title = ? WHERE conversation_id = ?", arrayOf(title, conversationId))
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        // 只搜这些助手的对话，null 表示不限
        assistantIds: Collection<String>? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val queryText = keyword.trim()
        if (queryText.isEmpty()) return@withContext results
        if (assistantIds != null && assistantIds.isEmpty()) return@withContext results
        val assistantFilter = if (assistantIds != null) {
            """
            AND EXISTS (
                SELECT 1 FROM conversationentity AS conversation
                WHERE conversation.id = message_fts.conversation_id
                  AND conversation.assistant_id IN (${assistantIds.joinToString { "?" }})
            )
            """.trimIndent()
        } else {
            ""
        }
        // jieba_query 遇到引号、星号这类 FTS 语法字符可能直接抛错。搜索失败不该让页面崩溃，
        // 记录后返回空结果，用户改一下关键词就行。
        val cursor = runCatching {
            db.query(
                """
                SELECT node_id, message_id, conversation_id, title, update_at,
                       simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
                FROM message_fts
                WHERE text MATCH jieba_query(?)
                $assistantFilter
                ORDER BY ${sort.orderBy}
                LIMIT 50
                """.trimIndent(),
                arrayOf(queryText, *assistantIds.orEmpty().toTypedArray())
            )
        }.onFailure {
            Log.w(TAG, "search failed: $queryText", it)
        }.getOrNull() ?: return@withContext results
        Log.i(TAG, "search: $queryText")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

private data class IndexedMessage(
    val nodeId: String,
    val text: String,
)

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
