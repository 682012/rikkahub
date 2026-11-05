package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import kotlin.uuid.Uuid

/**
 * 会话开始后固定在会话上的配置。
 *
 * 新会话没有这份配置，聊天页里的切换直接改助手；会话落库时从助手拍一份快照，
 * 之后的切换只影响当前会话，别处的改动不会打乱它的上下文和缓存。
 * 模式注入和 Lorebook 同样随会话固定，沿用 [Conversation.modeInjectionIds] 与 [Conversation.lorebookIds]。
 */
@Serializable
data class ConversationConfig(
    val chatModelId: Uuid? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val enableWebSearch: Boolean = false,
    val builtInSearch: Boolean = false, // 模型内置搜索，覆盖模型自身的开关
    val mcpServers: Set<Uuid> = emptySet(),
    val workspaceId: Uuid? = null,
    val enabledSkills: Set<String> = emptySet(),
)

// 内置搜索是模型的开关，不在助手上，由调用方另行填入
private fun Assistant.toConversationConfig() = ConversationConfig(
    chatModelId = chatModelId,
    reasoningLevel = reasoningLevel,
    enableWebSearch = enableWebSearch,
    mcpServers = mcpServers,
    workspaceId = workspaceId,
    enabledSkills = enabledSkills,
)

private fun Assistant.withConversationConfig(config: ConversationConfig) = copy(
    chatModelId = config.chatModelId,
    reasoningLevel = config.reasoningLevel,
    enableWebSearch = config.enableWebSearch,
    mcpServers = config.mcpServers,
    workspaceId = config.workspaceId,
    enabledSkills = config.enabledSkills,
)

/** 会话视角下的助手：会话开始后，会话持有的字段覆盖助手自身的值。 */
fun Assistant.withConversation(conversation: Conversation): Assistant {
    val config = conversation.config ?: return this
    return withConversationConfig(config).copy(
        modeInjectionIds = conversation.modeInjectionIds,
        lorebookIds = conversation.lorebookIds,
    )
}

/** 聊天页改动助手后留在会话上的部分，[updated] 是会话视角下改动后的助手。 */
fun Conversation.withAssistantUpdate(updated: Assistant, settings: Settings): Conversation {
    val previous = settings.getAssistantOf(this)
    val config = config
    val conversation = if (config == null) {
        this
    } else {
        copy(
            config = updated.toConversationConfig().copy(
                builtInSearch = if (updated.chatModelId == config.chatModelId) {
                    config.builtInSearch
                } else {
                    // 内置搜索是模型自身的能力，换模型后跟随新模型的开关
                    settings.findModelById(updated.chatModelId)?.tools?.contains(BuiltInTools.Search) == true
                }
            ),
            modeInjectionIds = updated.modeInjectionIds,
            lorebookIds = updated.lorebookIds,
        )
    }
    // 工作目录属于原来的工作区，换工作区后失效
    return if (updated.workspaceId != previous.workspaceId) conversation.copy(workspaceCwd = null) else conversation
}

/** 聊天页改动助手后写回助手设置的部分：会话持有的字段保持 [stored] 的原值。 */
fun Assistant.withoutConversationFields(conversation: Conversation, stored: Assistant): Assistant =
    if (conversation.config == null) {
        this
    } else {
        withConversationConfig(stored.toConversationConfig()).copy(
            modeInjectionIds = stored.modeInjectionIds,
            lorebookIds = stored.lorebookIds,
        )
    }

/**
 * 助手已被删除时的安全占位：沿用会话原来的助手 ID，但不带任何记忆、工具、工作区、技能和注入。
 *
 * 以前这里退回当前助手，等于把别的助手的人格和记忆串进这段对话；现在这段对话宁可什么都不做，
 * 等用户显式迁移（[migrateToAssistant]）。
 */
internal fun Conversation.orphanAssistant(): Assistant = Assistant(
    id = assistantId,
    chatModelId = null,
    name = "",
    systemPrompt = "",
    enableMemory = false,
    useGlobalMemory = false,
    autoConsolidateMemory = false,
    enableRecentChatsReference = false,
    presetMessages = emptyList(),
    quickMessageIds = emptySet(),
    regexes = emptyList(),
    customHeaders = emptyList(),
    customBodies = emptyList(),
    mcpServers = emptySet(),
    localTools = emptyList(),
    enableWebSearch = false,
    workspaceId = null,
    modeInjectionIds = emptySet(),
    lorebookIds = emptySet(),
    enabledSkills = emptySet(),
    enableTimeReminder = false,
    allowConversationSystemPrompt = false,
)

/** 会话所属助手自身的设置，不含会话上固定的配置；助手已被删除时返回同 ID 的安全占位。 */
fun Settings.getStoredAssistantOf(conversation: Conversation): Assistant =
    getAssistantById(conversation.assistantId) ?: conversation.orphanAssistant()

/** 会话所属的助手（会话视角）；助手不在了就只剩占位，不再套用会话上的旧配置。 */
fun Settings.getAssistantOf(conversation: Conversation): Assistant {
    val stored = getAssistantById(conversation.assistantId) ?: return conversation.orphanAssistant()
    val assistant = stored.withConversation(conversation)
    if (conversation.config == null) return assistant
    // 助手自身的失效引用在读取设置时已清理，会话上固定的这份在这里滤掉
    return assistant.copy(
        mcpServers = assistant.mcpServers.filterTo(mutableSetOf()) { id -> mcpServers.any { it.id == id } },
        modeInjectionIds = assistant.modeInjectionIds
            .filterTo(mutableSetOf()) { id -> modeInjections.any { it.id == id } },
        lorebookIds = assistant.lorebookIds.filterTo(mutableSetOf()) { id -> lorebooks.any { it.id == id } },
    )
}

/**
 * 会话使用的聊天模型；会话开始后内置搜索以会话上固定的开关为准。
 *
 * 助手已被删除时返回 null：会话上固定的模型和工具属于那个已经不存在的助手，不能拿来接着生成，
 * 由生成入口给出明确错误，而不是抛到界面里。
 */
fun Settings.getChatModelOf(conversation: Conversation): Model? {
    val assistant = getAssistantById(conversation.assistantId) ?: return null
    val assistantModel = findModelById(assistant.chatModelId ?: chatModelId)
    val config = conversation.config ?: return assistantModel
    // 固定的模型被删除后退回助手当前的模型，内置搜索也跟随该模型自身的开关
    val model = findModelById(config.chatModelId) ?: return assistantModel
    return model.copy(
        tools = if (config.builtInSearch) model.tools + BuiltInTools.Search else model.tools - BuiltInTools.Search
    )
}

/**
 * 把助手当前的配置固定到会话上。已经固定过的会话原样返回，
 * 只有固定的模型被删除时才改为固定助手当前的模型，之后不再跟着助手变。
 *
 * 助手已被删除的会话不固定任何东西：占位快照会把这段对话钉死在一个空配置上，
 * 用户显式迁移时再统一重拍（[migrateToAssistant]）。
 */
fun Conversation.bindConfig(settings: Settings): Conversation {
    if (settings.getAssistantById(assistantId) == null) return this
    val model = settings.getChatModelOf(this)
    val builtInSearch = model != null && BuiltInTools.Search in model.tools
    val config = config
    if (config != null) {
        if (model == null || model.id == config.chatModelId) return this
        return copy(config = config.copy(chatModelId = model.id, builtInSearch = builtInSearch))
    }
    val assistant = settings.getAssistantOf(this)
    return copy(
        config = assistant.toConversationConfig().copy(chatModelId = model?.id, builtInSearch = builtInSearch),
        // 旧版本允许会话单独绑定注入，这类会话保留自己的绑定
        modeInjectionIds = modeInjectionIds.ifEmpty { assistant.modeInjectionIds },
        lorebookIds = lorebookIds.ifEmpty { assistant.lorebookIds },
    )
}

/**
 * 用户主动把这段对话迁移到 [target] 助手。
 *
 * 只搬对话本身：消息、分支和标题都保留；配置快照（含内置搜索）、模式注入和 Lorebook 重绑到
 * 目标助手的当前值。旧助手留下的东西不能跟过来——工作目录属于旧工作区，文件夹是旧助手的分组，
 * 会话级 system prompt、聊天建议和压缩摘要都带着旧助手当时的规则。
 *
 * 纯函数，不落库也不改设置。普通 [bindConfig] 不走这里，只有用户显式迁移才调用。
 */
fun Conversation.migrateToAssistant(target: Assistant, settings: Settings): Conversation {
    // 目标助手没有单独指定模型时用全局默认模型，和 bindConfig 的解析一致
    val model = settings.findModelById(target.chatModelId ?: settings.chatModelId)
    return copy(
        assistantId = target.id,
        config = target.toConversationConfig().copy(
            chatModelId = model?.id ?: target.chatModelId,
            builtInSearch = model != null && BuiltInTools.Search in model.tools,
        ),
        modeInjectionIds = target.modeInjectionIds,
        lorebookIds = target.lorebookIds,
        workspaceCwd = null,
        folderId = null,
        customSystemPrompt = null,
        chatSuggestions = emptyList(),
        // 压缩检查点是旧助手写的历史摘要，本身就带着它当时的规则，迁移后不再代表新助手的上下文
        messageNodes = messageNodes.withoutCheckpointSummaries(),
    )
}

/**
 * 去掉压缩检查点（模型写的摘要，不是对话里真正说过的话）。原消息在压缩时是原样保留的，
 * 所以这里只丢摘要不会丢历史；节点里还有别的消息时只去掉摘要本身，别的分支照旧。
 */
private fun List<MessageNode>.withoutCheckpointSummaries(): List<MessageNode> = mapNotNull { node ->
    if (node.messages.none { it.isContextCheckpoint }) return@mapNotNull node
    val kept = node.messages.filterNot { it.isContextCheckpoint }
    if (kept.isEmpty()) return@mapNotNull null
    val selected = node.selectIndex.coerceIn(0, node.messages.lastIndex)
    val keptBefore = node.messages.take(selected).count { !it.isContextCheckpoint }
    // 选中的是被丢掉的摘要时，往回到最近一条留下的消息
    val selectIndex = if (node.messages[selected].isContextCheckpoint) (keptBefore - 1).coerceAtLeast(0) else keptBefore
    node.copy(messages = kept, selectIndex = selectIndex.coerceIn(0, kept.lastIndex))
}
