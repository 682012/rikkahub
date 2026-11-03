package me.rerere.rikkahub.data.ai

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.companion.CompanionDelta
import me.rerere.rikkahub.data.companion.CompanionFailure
import me.rerere.rikkahub.data.companion.CompanionFollowDecision
import me.rerere.rikkahub.data.companion.CompanionFollowPolicy
import me.rerere.rikkahub.data.companion.CompanionLimits
import me.rerere.rikkahub.data.companion.CompanionModelDescription
import me.rerere.rikkahub.data.companion.CompanionModelOrigin
import me.rerere.rikkahub.data.companion.CompanionModelSnapshot
import me.rerere.rikkahub.data.companion.CompanionModelUnavailableException
import me.rerere.rikkahub.data.companion.CompanionPromptRole
import me.rerere.rikkahub.data.companion.CompanionReplySource
import me.rerere.rikkahub.data.companion.CompanionRequest
import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.getChatModelOf
import me.rerere.rikkahub.service.ChatService

/**
 * 小助手请求的真实实现：复用现有的模型解析、提供商和并发闸门，不新增后端。
 *
 * - 模型：`CompanionModelMode.OFF/FOLLOW_CONVERSATION/FIXED` 三选一。
 *   - `FIXED`：指定的模型被删掉就报 [CompanionFailure.ModelMissing]，绝不回退到别的模型。
 *   - `FOLLOW_CONVERSATION`：**有会话 ID 就必须用会话的模型**。会话读不到、或它的模型算不出来，
 *     一律明确失败（[CompanionModelDescription.conversationMissing]），不会静默换成全局默认模型。
 *     只有"压根没有会话"（悬浮窗在桌面上的预览态）才用全局默认，并且标成
 *     [CompanionModelOrigin.GLOBAL_DEFAULT]，界面上写清"这是全局默认，不是会话的模型"。
 * - 一致性：[describeModel] 给出的 [CompanionModelSnapshot] 会被控制器带进
 *   [CompanionRequest.expectedModel]；[generate] 重新解析后比对，不一致就拒绝发请求，
 *   保证"界面显示的模型"和"账单里记的模型"是同一个。
 * - 账单：每次请求都经 [UsageRecorder] 记成 `COMPANION`，和主聊天分开统计，也不写回任何会话。
 * - 并发：走 [ProviderManager] 里已有的全局限流实现，不另开线程池。
 */
class CompanionReplyGenerator(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val lazyChatService: Lazy<ChatService>,
) : CompanionReplySource {
    // 只有真正要看会话模型时才创建 ChatService
    private val chatService by lazyChatService

    override suspend fun describeModel(conversationId: Uuid?): CompanionModelDescription =
        resolve(conversationId).description

    override suspend fun generate(
        conversationId: Uuid?,
        request: CompanionRequest,
        onDelta: (CompanionDelta) -> Unit,
    ): String {
        val resolution = resolve(conversationId)
        val model = resolution.model
            ?: throw CompanionModelUnavailableException(
                resolution.description.failure ?: CompanionFailure.ModelMissing,
            )
        val provider = resolution.provider
            ?: throw CompanionModelUnavailableException(
                resolution.description.failure
                    ?: CompanionFailure.ProviderMissing(labelOf(model, null)),
            )
        val expected = request.expectedModel
        if (expected != null && expected != resolution.snapshot) {
            // 描述和实际请求必须是同一个模型：两次读取之间设置变了（换/删模型、换会话），
            // 这次请求就作废，绝不拿另一个模型把旧请求发出去
            throw CompanionModelUnavailableException(CompanionFailure.ModelMissing)
        }
        val messages = buildList {
            add(UIMessage.system(request.systemPrompt))
            request.history.forEach { message ->
                add(
                    when (message.role) {
                        CompanionPromptRole.USER -> UIMessage.user(message.text)
                        CompanionPromptRole.ASSISTANT -> UIMessage.assistant(message.text)
                    },
                )
            }
            add(UIMessage.user(request.userPrompt))
        }
        val params = backgroundTextGenerationParams(model, request.sessionId, ReasoningLevel.OFF)
            .copy(maxTokens = CompanionLimits.MAX_REPLY_TOKENS)
        val providerImpl = usageRecorder.wrap(
            providerManager.getProviderByType(provider),
            UsageTag(
                source = UsageSource.COMPANION,
                assistantId = resolution.assistantId,
                conversationId = resolution.conversationId,
            ),
        )
        val text = StringBuilder()
        val reasoning = StringBuilder()
        providerImpl.streamText(provider, messages, params).collect { chunk ->
            when (chunk) {
                is StreamChunk.TextDelta -> {
                    text.append(chunk.text)
                    onDelta(CompanionDelta(text = text.toString(), reasoning = reasoning.toString()))
                }

                is StreamChunk.ReasoningDelta -> {
                    reasoning.append(chunk.text)
                    onDelta(CompanionDelta(text = text.toString(), reasoning = reasoning.toString()))
                }

                else -> Unit
            }
        }
        return text.toString()
    }

    private suspend fun resolve(conversationId: Uuid?): Resolution {
        val settings = settingsStore.settingsFlow.first()
        val companion = settings.displaySetting.floatingCompanion
        val mode = companion.aiModelMode
        if (mode == CompanionModelMode.OFF) {
            return Resolution(
                model = null,
                provider = null,
                description = CompanionModelDescription(
                    source = CompanionModelMode.OFF,
                    failure = CompanionFailure.AiDisabled,
                ),
                assistantId = null,
                conversationId = conversationId,
            )
        }

        // 跟随会话：只有真的要看会话时才创建 ChatService，读不到就是读不到
        val conversation = if (mode == CompanionModelMode.FOLLOW_CONVERSATION && conversationId != null) {
            readConversation(conversationId)
        } else {
            null
        }
        val conversationModel = conversation?.let { current ->
            try {
                settings.getChatModelOf(current)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                null
            }
        }
        val globalDefaultModel = settings.findModelById(
            settings.getCurrentAssistant().chatModelId ?: settings.chatModelId,
        )
        val fixedModel = if (mode == CompanionModelMode.FIXED) {
            settings.findModelById(companion.aiModelId)
        } else {
            null
        }

        var chosen: Model? = null
        var origin = CompanionModelOrigin.FIXED
        var conversationMissing = false
        when (mode) {
            CompanionModelMode.FOLLOW_CONVERSATION -> {
                val decision = CompanionFollowPolicy.decide(
                    conversationId = conversationId,
                    conversationFound = conversation != null,
                    conversationModelFound = conversationModel != null,
                    globalDefaultFound = globalDefaultModel != null,
                )
                when (decision) {
                    // 会话自己的模型
                    CompanionFollowDecision.CONVERSATION_MODEL -> {
                        chosen = conversationModel
                        origin = CompanionModelOrigin.CONVERSATION
                    }
                    // 完全没有会话（预览态）：用全局默认，但必须显式标出来
                    CompanionFollowDecision.GLOBAL_DEFAULT_PREVIEW -> {
                        chosen = globalDefaultModel
                        origin = CompanionModelOrigin.GLOBAL_DEFAULT
                    }
                    // 有会话 ID 却读不到：明确失败，绝不换模型
                    CompanionFollowDecision.CONVERSATION_MISSING -> {
                        origin = CompanionModelOrigin.CONVERSATION
                        conversationMissing = true
                    }
                    CompanionFollowDecision.MODEL_MISSING -> {
                        origin = CompanionModelOrigin.GLOBAL_DEFAULT
                    }
                }
            }
            // 指定模型找不到就是找不到，不回退到全局聊天模型：用户明确选过这一个
            CompanionModelMode.FIXED -> {
                chosen = fixedModel
                origin = CompanionModelOrigin.FIXED
            }
            CompanionModelMode.OFF -> Unit
        }

        if (chosen == null) {
            return Resolution(
                model = null,
                provider = null,
                description = CompanionModelDescription(
                    source = mode,
                    failure = CompanionFailure.ModelMissing,
                    origin = origin,
                    conversationMissing = conversationMissing,
                ),
                assistantId = conversation?.assistantId,
                conversationId = conversationId,
            )
        }

        val model = chosen
        val provider = model.findProvider(settings.providers)
        val label = labelOf(model, provider, origin)
        return Resolution(
            model = model,
            provider = provider,
            description = CompanionModelDescription(
                // 提供商没了也要能显示"是哪个模型"，见 ProviderMissing 的文案
                label = label,
                source = mode,
                failure = if (provider == null) CompanionFailure.ProviderMissing(label) else null,
                origin = origin,
                snapshot = provider?.let {
                    CompanionModelSnapshot(
                        modelId = model.id,
                        modelKey = model.modelId,
                        providerId = it.id,
                        label = label,
                    )
                },
            ),
            assistantId = conversation?.assistantId,
            conversationId = conversationId,
        )
    }

    private suspend fun readConversation(conversationId: Uuid): Conversation? = try {
        chatService.getConversationFlow(conversationId).first()
    } catch (cancel: CancellationException) {
        // 超时/取消必须往上抛，否则会被当成"会话读不到"而静默失败
        throw cancel
    } catch (_: Throwable) {
        null
    }

    /**
     * 显示用的模型标签。
     *
     * 预览态（没有会话、只能用全局默认）会明确写上"global default"：状态卡上必须一眼看出
     * 这不是会话自己的模型，否则就等于暗换了一个（可能是最贵的）模型。
     */
    private fun labelOf(model: Model, provider: ProviderSetting?, origin: CompanionModelOrigin? = null): String {
        val base = buildString {
            append(model.displayName.ifBlank { model.modelId })
            provider?.name?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        return if (origin == CompanionModelOrigin.GLOBAL_DEFAULT) "$base · global default" else base
    }

    private data class Resolution(
        val model: Model?,
        val provider: ProviderSetting?,
        val description: CompanionModelDescription,
        val assistantId: Uuid?,
        val conversationId: Uuid?,
    )
}
