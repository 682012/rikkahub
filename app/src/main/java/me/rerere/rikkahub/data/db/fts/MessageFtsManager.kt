package me.rerere.rikkahub.data.db.fts

import android.util.Log
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
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
    RELEVANCE("message_fts.rank, conversation.update_at DESC"),
    NEWEST_FIRST("conversation.update_at DESC, message_fts.rank"),
    OLDEST_FIRST("conversation.update_at ASC, message_fts.rank"),
}

private const val TAG = "MessageFtsManager"

/**
 * message_fts 的列，顺序与建表语句一致。查询（search）和增量写入（indexConversation）都按名字引用，
 * 少一列就会抛 "no such column"，所以结构检查按列名比对而不是只看分词器。
 */
internal val messageFtsColumns = listOf("text", "node_id", "message_id", "conversation_id", "title", "update_at")

/**
 * 已有的 message_fts 能否直接沿用：分词器必须是 simple，且列要覆盖 [messageFtsColumns]。
 *
 * 只看分词器不够：别的分支/早期原型留下的 message_fts 可能列不同，而 CREATE VIRTUAL TABLE
 * IF NOT EXISTS 不会修正它。这种表在 onOpen 的回填（SELECT ... conversation_id FROM message_fts）
 * 就会抛错，让数据库整个打不开；即便侥幸存下来，查询和索引写入也全都会失败。
 */
internal fun isUsableMessageFtsSchema(sql: String?, columns: Collection<String>): Boolean =
    sql != null &&
        sql.contains("tokenize", ignoreCase = true) &&
        sql.contains("simple", ignoreCase = true) &&
        columns.containsAll(messageFtsColumns)

internal const val CREATE_MESSAGE_FTS_SQL = """
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

/**
 * 建立消息索引用到的表，每次打开数据库时调用。
 *
 * message_fts 是 FTS5 虚表，除 rowid 外的列都无法用索引定位，按 conversation_id 删除会扫描全部消息；
 * 所以另用普通表 message_fts_rowid 记录每行索引属于哪个会话，按会话增删都先经它查出 rowid。
 * title、update_at 两列已不再写入，搜索时从 conversationentity 读取，保留只是为了已有索引不用重建。
 */
internal fun createMessageFtsTables(db: SupportSQLiteDatabase) {
    db.execSQL(CREATE_MESSAGE_FTS_SQL.trimIndent())
    if (!messageFtsColumns.all { it in existingFtsColumns(db) }) {
        // 遗留结构撑不起当前查询：索引是派生数据，重建而不是让每次开库都抛错
        Log.w(TAG, "message_fts has an incompatible column set, recreating")
        db.execSQL("DROP TABLE IF EXISTS message_fts")
        db.execSQL("DROP TABLE IF EXISTS message_fts_rowid")
        db.execSQL(CREATE_MESSAGE_FTS_SQL.trimIndent())
    }
    val hasRowidTable = db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'message_fts_rowid'")
        .use { it.moveToFirst() }
    if (hasRowidTable) return
    db.beginTransaction()
    try {
        db.execSQL("CREATE TABLE message_fts_rowid(fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT NOT NULL)")
        db.execSQL("CREATE INDEX index_message_fts_rowid_conversation_id ON message_fts_rowid(conversation_id)")
        // 旧版本建好的索引只需补上对应关系，读一遍 message_fts 的内容表，不用重新分词
        db.execSQL(
            "INSERT INTO message_fts_rowid(fts_rowid, conversation_id) SELECT rowid, conversation_id FROM message_fts"
        )
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

/** 列名（PRAGMA table_info），缺表或不是虚表时为空。 */
private fun existingFtsColumns(db: SupportSQLiteDatabase): List<String> =
    db.query("PRAGMA table_info(`message_fts`)").use { cursor ->
        buildList { while (cursor.moveToNext()) cursor.getString(1)?.let(::add) }
    }

private class IndexedMessage(val rowid: Long, val nodeId: String, val text: String)

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    /**
     * 确认 message_fts 是可用结构（simple 分词器），并保证 message_fts_rowid 存在。
     *
     * 从旧分支或旧备份导入的库可能带着不同分词器的 message_fts；CREATE VIRTUAL TABLE
     * IF NOT EXISTS 不会修正这种残留，查询就会全部落空。这里检测到不匹配时直接重建，
     * 返回 true 表示旧索引已经失效，调用方需要重新灌索引。
     *
     * 重建会把 message_fts_rowid 一起丢掉再重建，否则会留下指向已删除索引行的孤儿映射。
     * 结构正常时也顺手调一次 [createMessageFtsTables]：纯 IF NOT EXISTS + 一次 sqlite_master 查询，
     * 但能补上从旧版本升上来、只有 message_fts 没有 rowid 映射表的库。
     */
    suspend fun ensureSchema(): Boolean = withContext(Dispatchers.IO) {
        val sql = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'message_fts'"
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        val usable = isUsableMessageFtsSchema(sql, existingFtsColumns(db))
        if (usable) {
            // 缺 rowid 映射表时 deleteAll/indexConversation 会直接抛错，这里补齐
            createMessageFtsTables(db)
            return@withContext false
        }
        Log.w(TAG, "message_fts missing or incompatible tokenizer, recreating")
        db.execSQL("DROP TABLE IF EXISTS message_fts")
        db.execSQL("DROP TABLE IF EXISTS message_fts_rowid")
        createMessageFtsTables(db)
        true
    }

    suspend fun recreateSchema() = withContext(Dispatchers.IO) {
        db.execSQL("DROP TABLE IF EXISTS message_fts")
        db.execSQL("DROP TABLE IF EXISTS message_fts_rowid")
        createMessageFtsTables(db)
    }

    suspend fun count(): Long = withContext(Dispatchers.IO) {
        db.query("SELECT COUNT(*) FROM message_fts").use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    /**
     * 增量更新会话的索引：只重写新增或内容变了的消息，删掉已不存在的消息。
     * 每次保存会话都会调用，长会话里大部分消息没变，不必整份删掉重新分词。
     */
    suspend fun indexConversation(conversation: Conversation) = database.withTransaction {
        val conversationId = conversation.id.toString()
        val indexed = HashMap<String, IndexedMessage>()
        val stale = mutableListOf<Long>()
        db.query(
            """
            SELECT f.rowid, f.node_id, f.message_id, f.text
            FROM message_fts_rowid AS r JOIN message_fts AS f ON f.rowid = r.fts_rowid
            WHERE r.conversation_id = ?
            """.trimIndent(),
            arrayOf(conversationId)
        ).use {
            while (it.moveToNext()) {
                val row = IndexedMessage(it.getLong(0), it.getString(1), it.getString(3))
                if (indexed.putIfAbsent(it.getString(2), row) != null) stale.add(row.rowid)
            }
        }
        conversation.messageNodes.forEach { node ->
            val nodeId = node.id.toString()
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                val existing = indexed.remove(message.id.toString())
                if (existing != null && existing.nodeId == nodeId && existing.text == text) return@forEach
                existing?.let { stale.add(it.rowid) }
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id) VALUES (?, ?, ?, ?)",
                        arrayOf(text, nodeId, message.id.toString(), conversationId)
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO message_fts_rowid(fts_rowid, conversation_id) VALUES (last_insert_rowid(), ?)",
                        arrayOf(conversationId)
                    )
                }
            }
        }
        indexed.values.forEach { stale.add(it.rowid) }
        stale.forEach { rowid ->
            db.execSQL("DELETE FROM message_fts WHERE rowid = ?", arrayOf(rowid))
            db.execSQL("DELETE FROM message_fts_rowid WHERE fts_rowid = ?", arrayOf(rowid))
        }
    }

    suspend fun deleteConversation(conversationId: String) = database.withTransaction {
        db.execSQL(
            "DELETE FROM message_fts WHERE rowid IN (SELECT fts_rowid FROM message_fts_rowid WHERE conversation_id = ?)",
            arrayOf(conversationId)
        )
        db.execSQL("DELETE FROM message_fts_rowid WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = database.withTransaction {
        db.execSQL("DELETE FROM message_fts")
        db.execSQL("DELETE FROM message_fts_rowid")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        // 只搜这些助手的对话，null 表示不限
        assistantIds: Collection<String>? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        if (keyword.isBlank()) return@withContext results
        if (assistantIds != null && assistantIds.isEmpty()) return@withContext results
        // 拼查询这一步也可能失败（FTS 语法字符、simple_query 报错）。搜索失败不该让页面崩溃，
        // 记录后返回空结果，用户改一下关键词就行。
        val matchQuery = runCatching { buildFtsMatchQuery(keyword, ::simpleQuery) }
            .onFailure { Log.w(TAG, "build fts query failed: $keyword", it) }
            .getOrNull() ?: return@withContext results
        val assistantFilter = if (assistantIds != null) {
            "AND conversation.assistant_id IN (${assistantIds.joinToString { "?" }})"
        } else {
            ""
        }
        // 标题和更新时间以会话表为准，改标题、发消息都不用改写索引
        val cursor = runCatching {
            db.query(
                """
                SELECT message_fts.node_id, message_fts.message_id, message_fts.conversation_id,
                       conversation.title, conversation.update_at,
                       simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
                FROM message_fts
                JOIN conversationentity AS conversation ON conversation.id = message_fts.conversation_id
                WHERE message_fts.text MATCH ?
                $assistantFilter
                ORDER BY ${sort.orderBy}
                LIMIT 50
                """.trimIndent(),
                arrayOf(matchQuery, *assistantIds.orEmpty().toTypedArray())
            )
        }.onFailure {
            Log.w(TAG, "search failed: $keyword", it)
        }.getOrNull() ?: return@withContext results
        Log.i(TAG, "search: $keyword")
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

    // simple 扩展生成的查询，带拼音展开和前缀匹配
    private fun simpleQuery(text: String): String =
        db.query(
            "SELECT simple_query(?)",
            arrayOf(text)
        ).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
}

private val QUERY_RUN = Regex("[A-Za-z0-9]+|[^\\sA-Za-z0-9]+")

// 中文片段各字之间最多允许夹几个额外的字（如标点、“的”），再大就容易把散落各处的字也算作命中
private const val CJK_NEAR_SLACK = 2

/**
 * 把搜索词拼成 FTS5 查询，各段之间用 AND 连接：
 * 英文和数字交给 [asciiQuery]（simple_query，支持拼音和前缀匹配）；
 * 索引按单字切分中文，所以中文等其他字符的连续片段拆成单字，用 NEAR 要求它们挨得足够近，
 * 效果接近按词匹配，又不需要 jieba 词典。
 * 没有可搜索的内容时返回 null。
 */
internal fun buildFtsMatchQuery(keyword: String, asciiQuery: (String) -> String): String? {
    val terms = QUERY_RUN.findAll(keyword).mapNotNull { match ->
        val text = match.value
        val term = if (text.first().isAsciiLetterOrDigit()) asciiQuery(text) else nearQuery(text)
        term.takeIf { it.isNotBlank() }
    }.toList()
    return terms.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
}

private fun nearQuery(text: String): String {
    val chars = text.codePoints().toArray().map { "\"${String(Character.toChars(it)).replace("\"", "\"\"")}\"" }
    if (chars.size == 1) return chars.single()
    return "NEAR(${chars.joinToString(" ")}, ${chars.size - 2 + CJK_NEAR_SLACK})"
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
