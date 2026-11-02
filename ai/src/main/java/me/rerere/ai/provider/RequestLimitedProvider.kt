package me.rerere.ai.provider

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage

/**
 * 给 [Provider] 套上每模型并发闸门，只限制真正的 API 请求。
 *
 * 名额只在这一层占：文本和图片请求从发起请求占到流结束，[listModels]、[getQuota] 这类管理接口不占名额。
 * 上层每次请求都会重新经过这里，所以工具执行、重试退避、多步 agent 循环都不在名额内，不会互相卡死。
 */
class RequestLimitedProvider<T : ProviderSetting>(
    private val delegate: Provider<T>,
    private val limiter: ModelRequestLimiter,
) : Provider<T> {
    override suspend fun listModels(providerSetting: T): List<Model> = delegate.listModels(providerSetting)

    override suspend fun getQuota(providerSetting: T): ProviderQuota? = delegate.getQuota(providerSetting)

    override suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = limiter.withPermit(params.model) {
        delegate.generateText(providerSetting, messages, params)
    }

    override suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = limitedFlow(params.model) {
        delegate.streamText(providerSetting, messages, params)
    }

    override suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult = limiter.withPermit(params.model) {
        delegate.generateEmbedding(providerSetting, params)
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = limitedFlow(params.model) {
        delegate.generateImage(providerSetting, params)
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = limitedFlow(params.model) {
        delegate.editImage(providerSetting, params)
    }

    /**
     * 流式请求只在收集期间占名额。
     *
     * 返回的 Flow 是冷流：不收集就不占名额，每次收集各占一次，重复收集不会沿用上一个名额；
     * 上游建立连接抛异常、下游取消收集、正常结束都在 finally 里归还。
     */
    private fun <R> limitedFlow(model: Model, upstream: suspend () -> Flow<R>): Flow<R> = flow {
        val lease = limiter.acquire(model)
        try {
            currentCoroutineContext().ensureActive()
            emitAll(upstream())
        } finally {
            lease.release()
        }
    }
}
