package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
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
 *
 * 思考档位是唯一例外：它只属于会话，既不在助手上，也不随快照从助手带过来，
 * 见 [reasoningLevelOf]。旧数据里已经固定过值的会话照旧读出来（[reasoningLevel] 非空）。
 */
@Serializable
data class ConversationConfig(
    val chatModelId: Uuid? = null,
    /**
     * 这段对话里显式选过的思考档位；null 表示没在这里选过，用 [reasoningLevelOf] 的默认值。
     *
     * 用 null 而不是某个档位当默认值，是为了区分「用户选了自动」和「用户没选过」：
     * 后者要跟着 [defaultReasoningLevelOf] 走（模型支持思考就拉满），前者是用户自己压低的。
     */
    val reasoningLevel: ReasoningLevel? = null,
    val enableWebSearch: Boolean = false,
    val builtInSearch: Boolean = false, // 模型内置搜索，覆盖模型自身的开关
    val mcpServers: Set<Uuid> = emptySet(),
    val workspaceId: Uuid? = null,
    val enabledSkills: Set<String> = emptySet(),
    val runtime: ConversationRuntimeOptions = ConversationRuntimeOptions(),
)

/** Extra runtime controls belong to this conversation, never another assistant's defaults. */
val Conversation.runtime: ConversationRuntimeOptions
    get() = config?.runtime ?: ConversationRuntimeOptions()

/**
 * 会话没在这里选过思考档位时的默认值：模型支持思考就拉满到最高档，不支持就发原生请求。
 *
 * 原生请求用 [ReasoningLevel.AUTO] 表达：各 provider 只在模型声明了 [ModelAbility.REASONING] 时
 * 才写思考参数，AUTO 又表示「不指定档位、交给模型自己」，两处合起来就是不改变模型的原生行为。
 */
internal fun defaultReasoningLevelOf(model: Model?): ReasoningLevel =
    if (model != null && ModelAbility.REASONING in model.abilities) ReasoningLevel.MAX
    else ReasoningLevel.AUTO

/**
 * 这段对话当前生效的思考档位：本会话选过的优先，否则按当前模型取默认最高档。
 *
 * 取模型时用 [getChatModelOf]，和真正发请求时解析的是同一个模型：会话换了模型、
 * 或者固定的模型被删掉退回助手模型，默认档位都跟着变，不是一次算死的。
 */
fun Settings.reasoningLevelOf(conversation: Conversation): ReasoningLevel =
    conversation.config?.reasoningLevel ?: defaultReasoningLevelOf(getChatModelOf(conversation))

// 内置搜索是模型的开关，不在助手上，由调用方另行填入；
// 思考档位也不在这里——它只属于会话，不从助手快照过来，也不写回助手
private fun Assistant.toConversationConfig() = ConversationConfig(
    chatModelId = chatModelId,
    enableWebSearch = enableWebSearch,
    mcpServers = mcpServers,
    workspaceId = workspaceId,
    enabledSkills = enabledSkills,
)

/** 会话固定的字段放回助手上；思考档位不在其中，聊天页的选择不会顺着这里回到助手的默认值。 */
private fun Assistant.withConversationConfig(config: ConversationConfig) = copy(
    chatModelId = config.chatModelId,
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

/**
 * 聊天页改动助手后留在会话上的部分，[updated] 是会话视角下改动后的助手。
 *
 * 思考档位只落在会话上：
 * - 还没固定配置的新会话，只有当用户这次真的换了档位（[updated] 的档位和当前生效值不同）时，
 *   才就地拍一份快照固定下来，其余改动照旧写回助手（[withoutConversationFields]）；
 * - 已经固定过的会话，只把「显式换了档位」写进自己的配置，换模型、换搜索这些改动不会把
 *   本会话已有的档位重置，也不会把它写回助手。
 */
fun Conversation.withAssistantUpdate(updated: Assistant, settings: Settings): Conversation {
    val previous = settings.getAssistantOf(this)
    val config = config
    // 会话视角里的档位就是当前生效值（见 getAssistantOf），不同才说明这次是用户在改档位
    val selectedReasoning = updated.reasoningLevel.takeIf { it != previous.reasoningLevel }
    val conversation = if (config == null) {
        if (selectedReasoning == null) {
            this
        } else {
            // 还没开始的对话里选了档位：固定一份只属于这段会话的配置，首次落库时一起写入
            val bound = bindConfig(settings)
            bound.copy(
                config = bound.config?.copy(reasoningLevel = selectedReasoning),
                modeInjectionIds = updated.modeInjectionIds,
                lorebookIds = updated.lorebookIds,
            )
        }
    } else {
        copy(
            config = updated.toConversationConfig().copy(
                reasoningLevel = selectedReasoning ?: config.reasoningLevel,
                runtime = config.runtime,
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

/**
 * 聊天页改动助手后写回助手设置的部分：会话持有的字段保持 [stored] 的原值。
 *
 * 思考档位只属于会话，不管这份会话有没有固定过配置，都不跟着聊天页的改动写回助手——
 * 否则在一段还没开始的新对话里调一次档位，就等于改了这个助手的全局默认。
 */
fun Assistant.withoutConversationFields(conversation: Conversation, stored: Assistant): Assistant =
    if (conversation.config == null) {
        copy(reasoningLevel = stored.reasoningLevel)
    } else {
        withConversationConfig(stored.toConversationConfig()).copy(
            reasoningLevel = stored.reasoningLevel,
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
    // 思考档位只属于会话：聊天页、生成请求看到的都是这里解析出的生效值
    val assistant = stored.withConversation(conversation).copy(
        reasoningLevel = reasoningLevelOf(conversation),
    )
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
 * 思考档位不从助手拍过来（见 [reasoningLevelOf]）：这里新建的配置保持 [ConversationConfig.reasoningLevel]
 * 为空，等于「这段对话没选过」，于是按当时的模型走默认最高档；用户在这之前选过的档位已经写进
 * 会话自己的配置（[withAssistantUpdate]），这里不会覆盖它。
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
        lineageId = id,
        folderAssignmentLocked = true,
        familySize = 1,
        config = target.toConversationConfig().copy(
            chatModelId = model?.id ?: target.chatModelId,
            builtInSearch = model != null && BuiltInTools.Search in model.tools,
            // 思考档位属于这段对话，不跟目标助手的默认值走：选过的原样留着，
            // 没选过的仍然是「没选过」，不能借迁移把一个本地选择变成别的范围
            reasoningLevel = config?.reasoningLevel,
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
