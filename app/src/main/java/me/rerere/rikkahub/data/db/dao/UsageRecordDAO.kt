package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity

/**
 * Session-level view used by the fork chat usage sheet.
 *
 * 口径陷阱（r22 核实）：`conversation_id` 不能当"来源"用。
 * - 子代理（SubagentRunner）继承父会话 id；
 * - 标题/摘要/快速模型（ConversationMetaGenerator、generateCompressionSummary）也用父会话 id；
 * - 只有 usage_record.source 能区分 CHAT / SUBAGENT / TITLE / COMPRESSION / ...
 * 所以任何"本对话"统计都必须先按 source 分组或过滤，只 where conversation_id 一定混算。
 * 另外 `cached_tokens` 在 r21 schema35 里没有"是否上报"标志，0 既可能是真 0 命中也可能是上游没报，
 * 命中率必须在纯模型里排除未上报的模型（见 ui/pages/chat/CachedContextMetrics.kt），不能在 SQL 里直接相除。
 */
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

    /**
     * 按 (source, provider, model) 汇总一次对话的用量，同时把缓存命中的**覆盖度**拆出来：
     * 只有上游明确报过缓存的行（`cached_reported = 1`，旧数据里 `cached_tokens > 0` 也算）才进命中率分母。
     * 分母的 prompt 与分子的 cached 取自同一批行，绝不拿整个模型的 prompt 去配一个"曾经报过"的标记。
     */
    @Query("""
        SELECT UPPER(source) AS source, COALESCE(provider_id, '') AS providerId,
               model_id AS modelId, MAX(model_name) AS modelName,
               COUNT(*) AS requests, SUM(prompt_tokens) AS promptTokens,
               SUM(completion_tokens) AS completionTokens, SUM(cached_tokens) AS cachedTokens,
               COALESCE(SUM(cache_creation_tokens), 0) AS cacheCreationTokens,
               SUM(CASE WHEN (COALESCE(cached_reported, 0) = 1 OR cached_tokens > 0) THEN prompt_tokens ELSE 0 END) AS knownPromptTokens,
               SUM(CASE WHEN (COALESCE(cached_reported, 0) = 1 OR cached_tokens > 0) THEN cached_tokens ELSE 0 END) AS knownCachedTokens,
               SUM(CASE WHEN (COALESCE(cached_reported, 0) = 1 OR cached_tokens > 0) THEN 1 ELSE 0 END) AS knownRequests,
               SUM(CASE WHEN NOT (COALESCE(cached_reported, 0) = 1 OR cached_tokens > 0) THEN prompt_tokens ELSE 0 END) AS unknownPromptTokens,
               SUM(CASE WHEN NOT (COALESCE(cached_reported, 0) = 1 OR cached_tokens > 0) THEN 1 ELSE 0 END) AS unknownRequests,
               SUM(CASE WHEN cached_tokens < 0 OR prompt_tokens < 0 OR cached_tokens > prompt_tokens THEN 1 ELSE 0 END) AS invalidCacheReports
        FROM usage_record WHERE conversation_id = :conversationId
        GROUP BY UPPER(source), COALESCE(provider_id, ''), COALESCE(NULLIF(model_id, ''), model_name, '')
        ORDER BY SUM(prompt_tokens + completion_tokens) DESC
    """)
    fun observeConversationGroups(conversationId: String): Flow<List<ConversationUsageGroup>>

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

    @Query("SELECT COUNT(*) FROM usage_record")
    fun observeRecordCount(): Flow<Long>

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

    /**
     * 全局统计的缓存覆盖度，按 (provider, model) 分开：分子分母只来自同一组，
     * 不会把没报缓存的模型输入稀释进比例，也不会把 A 模型的命中配 B 模型的输入。
     */
    @Query(
        "SELECT COALESCE(provider_id, '') AS providerId, COALESCE(NULLIF(model_id, ''), model_name, '') AS modelKey, " +
            "SUM(CASE WHEN (COALESCE(cached_reported, 0) = 1 OR cached_tokens > 0) THEN prompt_tokens ELSE 0 END) AS knownPromptTokens, " +
            "SUM(CASE WHEN (COALESCE(cached_reported, 0) = 1 OR cached_tokens > 0) THEN cached_tokens ELSE 0 END) AS knownCachedTokens, " +
            "SUM(CASE WHEN (COALESCE(cached_reported, 0) = 1 OR cached_tokens > 0) THEN 1 ELSE 0 END) AS knownRequests, " +
            "SUM(CASE WHEN NOT (COALESCE(cached_reported, 0) = 1 OR cached_tokens > 0) THEN prompt_tokens ELSE 0 END) AS unknownPromptTokens, " +
            "SUM(CASE WHEN NOT (COALESCE(cached_reported, 0) = 1 OR cached_tokens > 0) THEN 1 ELSE 0 END) AS unknownRequests, " +
            "SUM(CASE WHEN cached_tokens < 0 OR prompt_tokens < 0 OR cached_tokens > prompt_tokens THEN 1 ELSE 0 END) AS invalidCacheReports, " +
            "COALESCE(SUM(cache_creation_tokens), 0) AS cacheCreationTokens " +
            "FROM usage_record WHERE created_at >= :since AND (:source IS NULL OR UPPER(source) = :source) " +
            "GROUP BY COALESCE(provider_id, ''), COALESCE(NULLIF(model_id, ''), model_name, '')"
    )
    suspend fun getCacheCoverage(since: Long, source: String?): List<CacheCoverage>

    @Query(
        "SELECT UPPER(source) AS source, SUM(prompt_tokens) AS promptTokens, " +
            "SUM(completion_tokens) AS completionTokens, SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since " +
            "GROUP BY UPPER(source) ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsBySource(since: Long): List<SourceUsage>
}

data class ConversationUsageGroup(
    val source: String,
    val modelId: String?,
    val modelName: String?,
    val requests: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    /** 与同组 modelId 配对的 provider id，命中率算子在 (source, provider, model) 内配对。 */
    val providerId: String = "",
    /** 新写入缓存的输入（Claude cache_creation），已包含在 [promptTokens] 里，不计入 [cachedTokens]。 */
    val cacheCreationTokens: Long = 0,
    /** 明确上报过缓存的行贡献的输入 token（含上报 0 命中的行）——命中率分母。 */
    val knownPromptTokens: Long = 0,
    /** 明确上报过缓存的行贡献的命中 token——命中率分子。 */
    val knownCachedTokens: Long = 0,
    val knownRequests: Long = 0,
    /** 没报缓存字段的行：不参与比例，单独计数并在 UI 里说明被排除多少。 */
    val unknownPromptTokens: Long = 0,
    val unknownRequests: Long = 0,
    /**
     * 组内上游自相矛盾的行数（cached < 0 / prompt < 0 / cached > prompt）。
     * 逐行计数，不能让一条坏行被同组的大输入在 SUM 里盖掉；>0 时整组不给命中率。
     * 默认 0：不带这个 projection 的旧调用点（含测试构造）继续编译。
     */
    val invalidCacheReports: Long = 0,
)

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

/**
 * 一个 (provider, model) 的缓存覆盖度。
 *
 * - [knownPromptTokens] / [knownCachedTokens]：明确上报过缓存的行，分子分母成对出现（0 命中也是真实值）。
 * - [unknownPromptTokens] / [unknownRequests]：没报缓存字段的行，只能单独展示，不进比例。
 * - [cacheCreationTokens]：已含在 known/unknown 的 prompt 里，单独给出来只为说明"这不是命中"。
 */
data class CacheCoverage(
    val providerId: String,
    val modelKey: String,
    val knownPromptTokens: Long,
    val knownCachedTokens: Long,
    val knownRequests: Long,
    val unknownPromptTokens: Long,
    val unknownRequests: Long,
    val cacheCreationTokens: Long = 0,
    /** 组内上游自相矛盾（负数 / 缓存大于输入）的行数，>0 时整组不给比例，默认 0。 */
    val invalidCacheReports: Long = 0,
)
