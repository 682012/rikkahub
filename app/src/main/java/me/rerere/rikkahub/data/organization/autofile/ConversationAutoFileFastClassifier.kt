package me.rerere.rikkahub.data.organization.autofile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.ai.backgroundTextGenerationParams
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.Uuid

/**
 * 真正发请求的分类器：用设置里的**快速模型**跑一次，不改 titlePrompt，也不进主聊天轮次。
 *
 * 只在这里记录独立 AUTOFILE 用量；不混成聊天、子代理或自动标题。
 */
class FastModelAutoFileClassifier(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val usageRecorder: UsageRecorder,
) : ConversationAutoFileClassifier {

    override suspend fun classify(
        assistantId: Uuid,
        items: List<AutoFileClassifyItem>,
        folders: List<AutoFileFolderRef>,
    ): AutoFileParsedClassification {
        if (items.isEmpty()) return AutoFileParsedClassification()
        currentCoroutineContext().ensureActive()
        val settings = settingsStore.awaitLoaded()
        if (settings.assistants.none { it.id == assistantId }) return AutoFileParsedClassification(error = "助手已被删除")
        val model = settings.findModelById(settings.fastModelId)
            ?: return AutoFileParsedClassification(error = "没有配置快速模型，无法自动归类")
        val provider = model.findProvider(settings.providers)
            ?: return AutoFileParsedClassification(error = "快速模型的提供商不可用，请检查模型设置")
        val prompt = ConversationAutoFilePrompt.build(items, folders)
        val result = runCatching {
            usageRecorder.wrap(
                providerManager.getProviderByType(provider),
                UsageTag(UsageSource.AUTOFILE, assistantId, null),
            ).generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model, assistantId, settings.fastModelReasoningLevel).copy(maxTokens = 4_096),
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return AutoFileParsedClassification(
                error = "分类请求失败：${error.message?.take(120) ?: "未知错误"}",
            )
        }
        return ConversationAutoFileParser.parse(result.message.toText())
    }
}
