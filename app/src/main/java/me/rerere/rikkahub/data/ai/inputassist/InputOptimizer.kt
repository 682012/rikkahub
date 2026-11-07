package me.rerere.rikkahub.data.ai.inputassist

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.withRetry
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.ai.backgroundTextGenerationParams
import me.rerere.rikkahub.data.ai.retryPolicy
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.runtime
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getChatModelOf
import kotlin.uuid.Uuid

private const val TAG = "InputOptimizer"

/**
 * 把用户这一条真实文本改写成更明确的请求；只走一步文本请求，不碰附件、不碰历史、不碰工具。
 *
 * 口径：
 * - 模型：会话里单独选了就用它；没选则跟随本会话的聊天模型（m3 起不再回退全局快速模型）。
 * - 用量：[UsageSource.INPUT_OPTIMIZATION] 单独记账，不和主对话混在一起。
 * - 失败/超时/守卫拒绝：一律返回原话可用的结果，调用方继续用用户原话发送。
 * - 取消：用户停止生成时取消照常向上抛，不吞；取消只停止等待，已经发出的请求照样计费。
 * - 每一次尝试的结论（可替换的改写 / 原话够清楚 / 失败）都按 会话+messageId+原文hash+助手 落缓存：
 *   重连、重新生成、恢复重试都不会把同一句话再收费一次，也不会把上次的失败说成这次的成功。
 */
class InputOptimizer(
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val cache: InputOptimizationCache,
) {
    suspend fun optimize(
        settings: Settings,
        conversation: Conversation,
        messageId: Uuid,
        rawText: String,
        assistantId: Uuid,
        timeoutMillis: Long = InputAssistRules.DEFAULT_TIMEOUT_MILLIS,
        /** 会话选项里"优化我的原话"单独选的模型；null = 跟随本会话聊天模型。 */
        explicitModelId: Uuid? = null,
    ): InputOptimizationOutcome {
        val original = rawText.trim()
        val hash = InputAssistRules.inputHash(AssistRolePrompts.cacheInput(original,
            conversation.runtime.optimizerSystemPrompt, explicitModelId?.toString()))
        // 先查缓存：重连/重新生成/重开 App 都不该为同一句话再付一次钱，
        // 上一次的 UNCHANGED / FAILED 结论也算数（它们各自的状态不会被说成成功）
        cache.get(conversation.id, messageId, hash, assistantId)?.let { hit -> return cachedOutcome(hit) }
        if (!InputAssistRules.optimizeEligible(original)) {
            // 没有请求，也就没有可记的费用；这里不落缓存
            return InputOptimizationOutcome(InputOptimizationStatus.NOT_ELIGIBLE)
        }
        val choice = chooseModel(settings, conversation, explicitModelId)
            ?: return InputOptimizationOutcome(
                InputOptimizationStatus.FAILED,
                failureReason = "no fast model and no chat model",
            )
        if (choice.second is ProviderSetting.WebAccount) {
            // 网页账号的请求需要带着会话在跑，后台代发既容易失效也说不清费用归属
            return InputOptimizationOutcome(
                InputOptimizationStatus.NOT_ELIGIBLE,
                failureReason = "web-account model is not used for input optimization",
            )
        }
        val masked = InputAssistRules.maskCodeBlocks(original)
        val provider = usageRecorder.wrap(
            providerManager.getProviderByType(choice.second),
            UsageTag(UsageSource.INPUT_OPTIMIZATION, assistantId, conversation.id),
        )
        val params = backgroundTextGenerationParams(choice.first, conversation.id, ReasoningLevel.OFF)
            .copy(temperature = 0.2f)
        val messages = listOf(
            UIMessage.system(AssistRolePrompts.compose(InputAssistRules.optimizationSystemPrompt(),
                conversation.runtime.optimizerSystemPrompt)),
            UIMessage.user(InputAssistRules.optimizationUserPrompt(masked)),
        )
        val raw = try {
            withTimeoutOrNull(timeoutMillis) {
                withRetry(policy = requestPolicy(settings)) {
                    provider.generateText(
                        providerSetting = choice.second,
                        messages = messages,
                        params = params,
                    ).message.toText()
                }
            }
        } catch (e: CancellationException) {
            // 取消不是失败结论：不落缓存（否则一次"停止"会让这话以后都不再优化），原样上抛
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "input optimization failed; keeping the user's own words", e)
            val reason = e.message?.take(160) ?: "request failed"
            rememberFailure(conversation, messageId, assistantId, hash, reason, choice.first, choice.second)
            return InputOptimizationOutcome(
                InputOptimizationStatus.FAILED,
                modelName = choice.first.displayName,
                failureReason = reason,
            )
        } ?: run {
            rememberFailure(
                conversation, messageId, assistantId, hash, "timeout", choice.first, choice.second,
            )
            return InputOptimizationOutcome(
                InputOptimizationStatus.FAILED,
                modelName = choice.first.displayName,
                failureReason = "timeout",
            )
        }

        val cleaned = InputAssistRules.sanitizeOptimized(raw, original, masked)
            ?: run {
                rememberFailure(
                    conversation, messageId, assistantId, hash,
                    "rewrite rejected by guard", choice.first, choice.second,
                )
                return InputOptimizationOutcome(
                    InputOptimizationStatus.FAILED,
                    modelName = choice.first.displayName,
                    failureReason = "rewrite rejected by guard",
                )
            }
        if (cleaned == original) {
            // 模型认为原话已经够清楚：这一轮发原话，并且记住"问过了" —— 别让重连再问一次
            remember(
                conversation = conversation,
                messageId = messageId,
                assistantId = assistantId,
                hash = hash,
                kind = OptimizationCacheKind.UNCHANGED,
                text = null,
                model = choice.first,
                provider = choice.second,
                failureReason = null,
            )
            return InputOptimizationOutcome(
                InputOptimizationStatus.UNCHANGED,
                modelName = choice.first.displayName,
            )
        }
        remember(
            conversation = conversation,
            messageId = messageId,
            assistantId = assistantId,
            hash = hash,
            kind = OptimizationCacheKind.REWRITE,
            text = cleaned,
            model = choice.first,
            provider = choice.second,
            failureReason = null,
        )
        return InputOptimizationOutcome(
            status = InputOptimizationStatus.OPTIMIZED,
            optimizedText = cleaned,
            modelName = choice.first.displayName,
        )
    }

    /**
     * 缓存命中转成结果。
     *
     * 失败/无改动仍然是 FAILED/UNCHANGED，只是带上 [InputOptimizationOutcome.cached]：
     * 不许因为"这条是缓存"就把它算成成功，也不许让界面以为这次又发了一次请求。
     */
    private fun cachedOutcome(hit: InputOptimizationCache.Entry): InputOptimizationOutcome =
        when (hit.status) {
            OptimizationCacheKind.REWRITE -> {
                val text = hit.optimizedText.orEmpty()
                if (text.isBlank()) {
                    InputOptimizationOutcome(
                        InputOptimizationStatus.FAILED,
                        failureReason = "empty cached rewrite",
                        cached = true,
                    )
                } else {
                    InputOptimizationOutcome(
                        status = InputOptimizationStatus.CACHED,
                        optimizedText = text,
                        modelName = hit.modelName,
                        cached = true,
                    )
                }
            }

            OptimizationCacheKind.UNCHANGED -> InputOptimizationOutcome(
                status = InputOptimizationStatus.UNCHANGED,
                modelName = hit.modelName,
                cached = true,
            )

            OptimizationCacheKind.FAILED -> InputOptimizationOutcome(
                status = InputOptimizationStatus.FAILED,
                modelName = hit.modelName,
                failureReason = hit.failureReason ?: "previous attempt failed recently",
                cached = true,
            )
        }

    private fun rememberFailure(
        conversation: Conversation,
        messageId: Uuid,
        assistantId: Uuid,
        hash: String,
        reason: String,
        model: Model,
        provider: ProviderSetting,
    ) {
        remember(
            conversation = conversation,
            messageId = messageId,
            assistantId = assistantId,
            hash = hash,
            kind = OptimizationCacheKind.FAILED,
            text = null,
            model = model,
            provider = provider,
            failureReason = reason,
        )
    }

    /** 缓存写失败只是少一次省钱的机会，绝不影响这一轮发送。 */
    private fun remember(
        conversation: Conversation,
        messageId: Uuid,
        assistantId: Uuid,
        hash: String,
        kind: OptimizationCacheKind,
        text: String?,
        model: Model?,
        provider: ProviderSetting?,
        failureReason: String?,
    ) {
        runCatching {
            cache.put(
                InputOptimizationCache.Entry(
                    conversationId = conversation.id.toString(),
                    messageId = messageId.toString(),
                    assistantId = assistantId.toString(),
                    originalHash = hash,
                    optimizedText = text,
                    status = kind,
                    modelName = model?.displayName,
                    modelId = model?.modelId,
                    providerId = provider?.id?.toString(),
                    failureReason = failureReason?.take(200),
                    createdAtMillis = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "could not cache the optimization attempt", it) }
    }

    /**
     * 模型：会话选项里单独选的优化模型 → 本会话聊天模型 → 全局聊天模型。
     *
     * 用户明确给「优化原话」选了模型就用它，不被全局设置顶掉；
     * 没选时**默认跟随本会话模型**，不再默默退回全局快速模型（m3 口径：审查/优化各自独立，不共用快速模型）。
     * 显式选的模型已经不存在时也整体失败，不偷偷换成别的模型去跑——那会变成"选了个模型却用另一条"。
     */
    private fun chooseModel(
        settings: Settings,
        conversation: Conversation,
        explicitModelId: Uuid?,
    ): Pair<Model, ProviderSetting>? {
        if (explicitModelId != null) {
            val explicit = settings.findModelById(explicitModelId) ?: return null
            val provider = explicit.findProvider(settings.providers) ?: return null
            return explicit to provider
        }
        val chat = runCatching { settings.getChatModelOf(conversation) }.getOrNull() ?: return null
        val provider = chat.findProvider(settings.providers) ?: return null
        return chat to provider
    }

    /**
     * 这一条辅助请求的重试策略：沿用用户设置的重试范围与开关，但最多再试一次、
     * 不继承"无限重试"——它只是锦上添花的一步，不能拖住用户的消息。
     */
    private fun requestPolicy(settings: Settings): RetryPolicy =
        settings.retryPolicy().copy(
            maxRetries = minOf(settings.retryPolicy().maxRetries, 1),
            unlimitedRetries = false,
            networkHandover = providerManager.networkHandover,
        )
}
