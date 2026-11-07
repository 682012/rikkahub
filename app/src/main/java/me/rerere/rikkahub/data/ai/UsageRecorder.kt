package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import java.util.UUID
import kotlin.uuid.Uuid

private const val TAG = "UsageRecorder"

/** 用量从哪类请求来，按名称存进 usage_record.source */
enum class UsageSource {
    CHAT,
    COMPRESSION,
    TITLE,
    SUGGESTION,
    TRANSLATION,
    MEMORY,
    OCR,
    SUBAGENT,
    /** 悬浮窗小助手自己的台词请求；和主聊天分开计费。 */
    COMPANION,
    /** 用户启用的话题归库分类，单列辅助用量。 */
    AUTOFILE,
    /** 对话内开启「用户消息优化」时那次改写请求；只在开关打开时发生。 */
    INPUT_OPTIMIZATION,
    /** 对话内开启「独立审查」时那次审查请求；和主回复分开计费。 */
    REVIEW,

    /** 用户开启「每轮总结经验」时，一轮任务结束后那次后台总结请求；只在开关打开时发生。 */
    LEARNING,
}

/** 记录用量时附带的归属信息 */
data class UsageTag(
    val source: UsageSource,
    val assistantId: Uuid? = null,
    val conversationId: Uuid? = null,
)

/**
 * 把每次模型请求的 usage 写进 usage_record。
 *
 * 发起请求的地方用 [wrap] 包一层 provider，每次 generateText / streamText 结束时记一行；
 * 重试的每次尝试都是独立的请求，各记各的。写库在 [scope] 里异步进行，失败只打日志，不影响请求本身。
 *
 * 记账口径（和 UI 统计共用，改这里要同步 [me.rerere.rikkahub.ui.pages.chat.computeCacheHitRate]）：
 * - **每次本地调用一行**，id 用随机 UUID。多步工具调用的每一步、每次重试都是独立计费请求，
 *   各记各的，绝不合并。
 * - 不做任何"上游 responseId 相同就当重复"的启发式去重：上游完全可能对多次调用返回同一个 id
 *   （兼容层、缓存重放、本地 mock），而那些调用**确实各自计费**，吞掉一条就等于少记真钱。
 *   幂等只能来自调用方自己的 requestId（同一次本地请求重复落库），不能跨请求合并。
 * - usage 全 0 的请求不写行，避免把"没报告"写成"0 用量"；
 * - 缓存上报覆盖度照样落库：`cachedReported` 为 null（上游没报缓存字段）和 true（报了，哪怕是 0）
 *   是两种事实，写库时必须分开，否则统计端只能伪造 0%。
 */
class UsageRecorder(
    private val dao: UsageRecordDAO,
    private val scope: CoroutineScope,
) {
    fun <T : ProviderSetting> wrap(provider: Provider<T>, tag: UsageTag): Provider<T> =
        UsageRecordingProvider(provider, tag)

    private fun record(
        tag: UsageTag,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        usage: TokenUsage,
    ) {
        if (usage.promptTokens <= 0 && usage.completionTokens <= 0) return
        val record = UsageRecordEntity(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            source = tag.source.name,
            providerId = providerSetting.id.toString(),
            modelId = params.model.id.toString(),
            modelName = params.model.modelId,
            assistantId = tag.assistantId?.toString(),
            conversationId = tag.conversationId?.toString(),
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            cachedTokens = usage.cachedTokens,
            // null = 上游没报缓存字段（未知），true = 报了，哪怕是 0
            cachedReported = usage.cachedReported ?: true.takeIf { usage.cachedTokens > 0 },
            cacheCreationTokens = usage.cacheCreationTokens,
        )
        // Room 的 suspend 写入自己会切到后台线程；id 每次调用都随机，不做跨请求合并
        scope.launch {
            runCatching { dao.insert(record) }
                .onFailure { Log.w(TAG, "Failed to record usage", it) }
        }
    }

    private inner class UsageRecordingProvider<T : ProviderSetting>(
        private val delegate: Provider<T>,
        private val tag: UsageTag,
    ) : Provider<T> by delegate {
        override suspend fun generateText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = delegate.generateText(providerSetting, messages, params).also { result ->
            result.usage?.let { record(tag, providerSetting, params, it) }
        }

        override suspend fun streamText(
            providerSetting: T,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            val upstream = delegate.streamText(providerSetting, messages, params)
            return flow {
                // 同一次请求里的 usage 是逐步更新的快照，按 merge 合并；中途取消或出错时已经产生的用量也要记下
                var usage: TokenUsage? = null
                try {
                    upstream.collect { chunk ->
                        if (chunk is StreamChunk.Usage) usage = if (chunk.completeSnapshot) chunk.usage else usage.merge(chunk.usage)
                        emit(chunk)
                    }
                } finally {
                    usage?.let { record(tag, providerSetting, params, it) }
                }
            }
        }
    }
}
