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
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getChatModelOf
import kotlin.uuid.Uuid

private const val TAG = "TurnReviewer"

/** 独立审查的一次尝试结果；[parsed] 为 null 表示模型没按格式回答，调用方按失败显示。 */
data class ReviewOutcome(
    val parsed: ParsedReview?,
    val modelName: String?,
    val failureReason: String? = null,
)

/**
 * INDEPENDENT 审查：回复写完之后，用快速模型独立读一遍"用户要求 / 助手回答"。
 *
 * 硬边界：
 * - 只发文本，不带工具定义（`disableToolCalls`），**不执行任何工具、不重放任务**；
 * - 结果只是给用户看的意见，绝不覆盖或改写助手原文，也不会自己再开一轮；
 * - 用量单独记 [UsageSource.REVIEW]，界面要说明这是额外费用。
 */
class TurnReviewer(
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
) {
    suspend fun review(
        settings: Settings,
        conversation: Conversation,
        assistantId: Uuid,
        userText: String,
        replyText: String,
        timeoutMillis: Long = InputAssistRules.REVIEW_TIMEOUT_MILLIS,
    ): ReviewOutcome {
        val user = userText.trim()
        val reply = replyText.trim()
        if (user.isEmpty() || reply.isEmpty()) {
            return ReviewOutcome(parsed = null, modelName = null, failureReason = "nothing to review")
        }
        val choice = chooseModel(settings, conversation)
            ?: return ReviewOutcome(parsed = null, modelName = null, failureReason = "no review model")
        if (choice.second is ProviderSetting.WebAccount) {
            return ReviewOutcome(
                parsed = null,
                modelName = null,
                failureReason = "web-account model is not used for independent review",
            )
        }
        val provider = usageRecorder.wrap(
            providerManager.getProviderByType(choice.second),
            UsageTag(UsageSource.REVIEW, assistantId, conversation.id),
        )
        val params = backgroundTextGenerationParams(choice.first, conversation.id, ReasoningLevel.OFF)
            .copy(temperature = 0.1f, disableToolCalls = true)
        val messages = listOf(
            UIMessage.user(InputAssistRules.reviewPrompt(user, reply)),
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
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "independent review failed", e)
            return ReviewOutcome(parsed = null, modelName = choice.first.displayName, failureReason = e.message?.take(160))
        } ?: return ReviewOutcome(parsed = null, modelName = choice.first.displayName, failureReason = "timeout")

        return ReviewOutcome(
            parsed = InputAssistRules.parseReview(raw),
            modelName = choice.first.displayName,
            failureReason = null,
        )
    }

    private fun chooseModel(settings: Settings, conversation: Conversation): Pair<Model, ProviderSetting>? {
        settings.findModelById(settings.fastModelId)?.let { model ->
            model.findProvider(settings.providers)?.let { return model to it }
        }
        val chat = runCatching { settings.getChatModelOf(conversation) }.getOrNull() ?: return null
        val provider = chat.findProvider(settings.providers) ?: return null
        return chat to provider
    }

    private fun requestPolicy(settings: Settings): RetryPolicy =
        settings.retryPolicy().copy(
            maxRetries = minOf(settings.retryPolicy().maxRetries, 1),
            unlimitedRetries = false,
            networkHandover = providerManager.networkHandover,
        )
}
