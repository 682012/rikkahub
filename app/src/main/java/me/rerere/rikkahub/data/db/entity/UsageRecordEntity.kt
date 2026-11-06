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
    /**
     * 缓存命中字段是否由上游明确上报（r22 schema36）。
     * 1/true = 上报过（[cachedTokens] 即使是 0 也是真实 0），NULL = 未知（旧数据或该响应没有缓存字段）。
     * 命中率的分子分母只认这一位，绝不拿 NULL 当 0%。
     */
    @ColumnInfo("cached_reported")
    val cachedReported: Boolean? = null,
    /** 新写入缓存的输入 token（Claude cache_creation），属于输入但不是命中；NULL = 未知/无此概念。 */
    @ColumnInfo("cache_creation_tokens")
    val cacheCreationTokens: Int? = null,
    /** Kept for fork.15 compatibility; queries derive totals from prompt + completion. */
    @ColumnInfo("total_tokens")
    val totalTokens: Int = promptTokens + completionTokens,
)
