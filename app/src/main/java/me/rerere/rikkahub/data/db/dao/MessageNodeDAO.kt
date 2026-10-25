package me.rerere.rikkahub.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    // 使用与 messages 相同的 JSON 编码，保守保留所有分支中出现的 URL。
    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean

    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index DESC LIMIT :limit"
    )
    suspend fun getLastNodes(conversationId: String, limit: Int): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 增量落库用：只读 id / 顺序 / 内容，避免把整张表的所有列都搬进内存
    @Query("SELECT id, node_index, messages, select_index FROM message_node WHERE conversation_id = :conversationId")
    suspend fun getSnapshots(conversationId: String): List<MessageNodeSnapshot>

    // 大行回退用：只读 id / 顺序 / select_index 与 SQLite 里的 code point 长度，不碰 messages。
    // length() 按字符（code point）计数，substr() 的下标与之同一套语义，二者必须配套使用。
    @Query(
        "SELECT id, conversation_id, node_index, select_index, length(messages) AS messages_length " +
            "FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC"
    )
    suspend fun getNodeMeta(conversationId: String): List<MessageNodeMeta>

    @Query(
        "SELECT id, conversation_id, node_index, select_index, length(messages) AS messages_length " +
            "FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index DESC LIMIT :limit"
    )
    suspend fun getLastNodeMeta(conversationId: String, limit: Int): List<MessageNodeMeta>

    // 大行回退用：按 code point 切片取回 messages 的一部分。start 从 1 开始，与 SQLite substr 一致。
    @Query("SELECT substr(messages, :start, :length) FROM message_node WHERE id = :nodeId")
    suspend fun getMessagesSlice(nodeId: String, start: Int, length: Int): String?

    @Query("DELETE FROM message_node WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    // 消息节点数（重新生成的分支不另算）
    @Query("SELECT COUNT(*) FROM message_node")
    suspend fun countAll(): Int
}

/** 不含 messages 的节点元信息，供大行（CursorWindow 溢出）时的分片回退使用，不会把大行读进游标窗口。 */
data class MessageNodeMeta(
    val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "node_index") val nodeIndex: Int,
    @ColumnInfo(name = "select_index") val selectIndex: Int,
    /** SQLite length(messages)：Unicode code point 数量，不是 Kotlin 的 UTF-16 长度。 */
    @ColumnInfo(name = "messages_length") val messagesLength: Int,
)

data class MessageNodeSnapshot(
    val id: String,
    @ColumnInfo(name = "node_index") val nodeIndex: Int,
    val messages: String,
    @ColumnInfo(name = "select_index") val selectIndex: Int,
)
