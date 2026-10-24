package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Unified fork/official 3.1 usage record.
 *
 * fork.15 used a String id plus node/total columns; official 3.1 added provider/model
 * snapshots and nullable conversation ownership. The final fork schema keeps both shapes
 * so upgrading fork.15 and importing official 3.1 data are both lossless.
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
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("provider_id")
    val providerId: String? = null,
    @ColumnInfo("model_id")
    val modelId: String? = null,
    @ColumnInfo("model_name")
    val modelName: String? = null,
    @ColumnInfo("assistant_id")
    val assistantId: String? = null,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("node_id")
    val nodeId: String? = null,
    @ColumnInfo("prompt_tokens")
    val promptTokens: Int = 0,
    @ColumnInfo("completion_tokens")
    val completionTokens: Int = 0,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Int = 0,
    /** Kept for fork.15 compatibility; queries derive totals from prompt + completion. */
    @ColumnInfo("total_tokens")
    val totalTokens: Int = promptTokens + completionTokens,
)
