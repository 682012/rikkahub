package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity

/** Session-level view used by the fork chat usage sheet. */
data class UsageTotals(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val totalTokens: Long = 0,
    val requests: Long = 0,
)

data class UsageByModel(
    val modelId: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val totalTokens: Long,
    val requests: Long,
)

@Dao
interface UsageRecordDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: UsageRecordEntity)

    @Query("SELECT * FROM usage_record WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun listByConversation(conversationId: String): List<UsageRecordEntity>

    @Query(
        """
        SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
               COALESCE(SUM(completion_tokens), 0) AS completionTokens,
               COALESCE(SUM(cached_tokens), 0) AS cachedTokens,
               COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens,
               COUNT(*) AS requests
        FROM usage_record
        WHERE conversation_id = :conversationId AND (:includeSubagents OR LOWER(source) = 'chat')
        """
    )
    suspend fun totals(conversationId: String, includeSubagents: Boolean = true): UsageTotals

    @Query(
        """
        SELECT COALESCE(model_id, model_name, '') AS modelId,
               SUM(prompt_tokens) AS promptTokens,
               SUM(completion_tokens) AS completionTokens,
               SUM(cached_tokens) AS cachedTokens,
               SUM(prompt_tokens + completion_tokens) AS totalTokens,
               COUNT(*) AS requests
        FROM usage_record
        WHERE conversation_id = :conversationId AND (:includeSubagents OR LOWER(source) = 'chat')
        GROUP BY COALESCE(model_id, model_name, '')
        ORDER BY totalTokens DESC
        """
    )
    suspend fun byModel(conversationId: String, includeSubagents: Boolean = true): List<UsageByModel>

    @Query("DELETE FROM usage_record WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM usage_record WHERE created_at < :before")
    suspend fun deleteOlderThan(before: Long)

    /** Official 3.1 global statistics. Extra fork-only columns are intentionally not part of this DTO. */
    @Query(
        """
        SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
               COALESCE(SUM(completion_tokens), 0) AS completionTokens,
               COALESCE(SUM(cached_tokens), 0) AS cachedTokens,
               COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens,
               COUNT(*) AS requests
        FROM usage_record WHERE created_at >= :since
        """
    )
    suspend fun getTotals(since: Long = 0): UsageTotals

    @Query(
        "SELECT strftime(:format, created_at / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since GROUP BY bucket ORDER BY bucket"
    )
    suspend fun getTotalsByBucket(format: String, since: Long): List<UsageBucket>

    @Query(
        "SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count " +
            "FROM usage_record WHERE UPPER(source) = :source AND created_at >= :since GROUP BY day"
    )
    suspend fun getRequestCountPerDay(source: String, since: Long): List<DayCount>

    @Query(
        "SELECT COALESCE(NULLIF(model_id, ''), model_name, '') AS modelId, MAX(model_name) AS modelName, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY COALESCE(NULLIF(model_id, ''), model_name, '') " +
            "ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsByModel(since: Long): List<ModelUsage>

    @Query(
        "SELECT UPPER(source) AS source, SUM(prompt_tokens) AS promptTokens, " +
            "SUM(completion_tokens) AS completionTokens, SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY UPPER(source) ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsBySource(since: Long): List<SourceUsage>
}

data class DayCount(val day: String, val count: Int)

data class UsageBucket(
    val bucket: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

data class ModelUsage(
    val modelId: String?,
    val modelName: String?,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

data class SourceUsage(
    val source: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)
