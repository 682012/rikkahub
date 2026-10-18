package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单次请求的用量流水。
 *
 * 不放在 message_node 的 JSON 里，是因为按会话/模型聚合时解析整段消息代价太高。
 * source 区分主对话与子代理，便于在统计里选择是否计入。
 */
@Entity(
    tableName = "usage_record",
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["created_at"]),
        Index(value = ["model_id"]),
    ],
)
data class UsageRecordEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("node_id")
    val nodeId: String? = null,
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("source")
    val source: String = "chat",
    @ColumnInfo("prompt_tokens")
    val promptTokens: Int = 0,
    @ColumnInfo("completion_tokens")
    val completionTokens: Int = 0,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Int = 0,
    @ColumnInfo("total_tokens")
    val totalTokens: Int = 0,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
