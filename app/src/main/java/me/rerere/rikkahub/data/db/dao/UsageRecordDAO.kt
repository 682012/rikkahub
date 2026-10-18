package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity

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
               COALESCE(SUM(total_tokens), 0) AS totalTokens,
               COUNT(*) AS requests
        FROM usage_record
        WHERE conversation_id = :conversationId AND (:includeSubagents OR source = 'chat')
        """
    )
    suspend fun totals(
        conversationId: String,
        includeSubagents: Boolean = true,
    ): UsageTotals

    @Query(
        """
        SELECT model_id AS modelId,
               SUM(prompt_tokens) AS promptTokens,
               SUM(completion_tokens) AS completionTokens,
               SUM(cached_tokens) AS cachedTokens,
               SUM(total_tokens) AS totalTokens,
               COUNT(*) AS requests
        FROM usage_record
        WHERE conversation_id = :conversationId AND (:includeSubagents OR source = 'chat')
        GROUP BY model_id
        ORDER BY totalTokens DESC
        """
    )
    suspend fun byModel(
        conversationId: String,
        includeSubagents: Boolean = true,
    ): List<UsageByModel>

    @Query("DELETE FROM usage_record WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM usage_record WHERE created_at < :before")
    suspend fun deleteOlderThan(before: Long)
}
