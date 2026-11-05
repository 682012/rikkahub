package me.rerere.rikkahub.data.ai

import android.content.Context
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.titleSnapshot
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale

/** 用快速模型生成会话的标题和聊天建议，只负责请求模型，写回会话由调用方处理 */
class ConversationMetaGenerator(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
) {
    /** 起标题要用的快速模型和它的提供商；没配置好时返回 null，调用方静默跳过，不能打断聊天 */
    fun titleModel(settings: Settings): Pair<Model, ProviderSetting>? {
        val model = settings.findModelById(settings.fastModelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        return model to provider
    }

    /** 快速模型或它的提供商找不到时抛出异常，提示用户检查设置 */
    suspend fun generateTitle(settings: Settings, conversation: Conversation): String {
        val model = settings.findModelById(settings.fastModelId)
            ?: throw IllegalStateException(context.getString(R.string.error_fast_model_not_found))
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException(context.getString(R.string.error_fast_model_provider_not_found))
        return generateTitle(settings, conversation, model, provider)
    }

    /**
     * 用指定的快速模型起标题。[userTextSnapshot] 不为空时优先用它作为起点（用户刚发出去的原话），
     * 助手的长回答不参与，标题只围绕用户真正要的东西。
     */
    suspend fun generateTitle(
        settings: Settings,
        conversation: Conversation,
        model: Model,
        provider: ProviderSetting,
        userTextSnapshot: String? = null,
    ): String = generate(
        settings,
        model,
        provider,
        conversation,
        settings.titlePrompt,
        recentMessages = 4,
        UsageSource.TITLE,
        userTextSnapshot = userTextSnapshot,
    ).trim()

    /** 没有配置快速模型时返回 null */
    suspend fun generateSuggestions(settings: Settings, conversation: Conversation): List<String>? {
        val model = settings.findModelById(settings.fastModelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        return generate(
            settings,
            model,
            provider,
            conversation,
            settings.suggestionPrompt,
            recentMessages = 8,
            UsageSource.SUGGESTION,
        )
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(10)
    }

    private suspend fun generate(
        settings: Settings,
        model: Model,
        provider: ProviderSetting,
        conversation: Conversation,
        prompt: String,
        recentMessages: Int,
        source: UsageSource,
        userTextSnapshot: String? = null,
    ): String {
        // 早标题只有用户原话，没有回答可参考；精修时回答已经在了，用最近几条真实对话
        val content = userTextSnapshot?.takeIf { it.isNotBlank() }
            ?.let { snapshot -> "[USER]: ${snapshot.titleSnapshot(limit = 1_500)}" }
            ?: conversation.currentMessages
                .takeLast(recentMessages).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }
        val result = usageRecorder.wrap(
            providerManager.getProviderByType(provider),
            UsageTag(source, conversation.assistantId, conversation.id),
        ).generateText(
            providerSetting = provider,
            messages = listOf(
                UIMessage.user(
                    prompt.applyPlaceholders(
                        "locale" to Locale.getDefault().displayName,
                        "content" to content,
                    )
                ),
            ),
            params = backgroundTextGenerationParams(model, conversation.id, settings.fastModelReasoningLevel),
        )
        return result.message.toText()
    }
}
