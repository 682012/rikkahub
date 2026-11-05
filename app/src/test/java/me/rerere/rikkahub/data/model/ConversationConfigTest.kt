package me.rerere.rikkahub.data.model

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationConfigTest {
    private val modelA = Model(modelId = "model-a", displayName = "Model A", tools = setOf(BuiltInTools.Search))
    private val modelB = Model(modelId = "model-b", displayName = "Model B")
    private val mcpServer = Uuid.random()
    private val workspace = Uuid.random()
    private val injection = Uuid.random()
    private val lorebook = Uuid.random()

    private val assistant = Assistant(
        chatModelId = modelA.id,
        reasoningLevel = ReasoningLevel.HIGH,
        enableWebSearch = true,
        mcpServers = setOf(mcpServer),
        workspaceId = workspace,
        enabledSkills = setOf("skill"),
        modeInjectionIds = setOf(injection),
        lorebookIds = setOf(lorebook),
    )

    private fun settings(assistant: Assistant = this.assistant) = Settings(
        assistantId = assistant.id,
        assistants = listOf(assistant),
        chatModelId = modelB.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA, modelB))),
        mcpServers = listOf(McpServerConfig.StreamableHTTPServer(id = mcpServer)),
        modeInjections = listOf(PromptInjection.ModeInjection(id = injection)),
        lorebooks = listOf(Lorebook(id = lorebook)),
    )

    private fun conversation(assistant: Assistant = this.assistant) = Conversation(
        assistantId = assistant.id,
        messageNodes = emptyList(),
    )

    @Test
    fun `unstarted conversation follows the assistant`() {
        val conversation = conversation()

        assertSame(assistant, settings().getAssistantOf(conversation))
        assertEquals(modelA, settings().getChatModelOf(conversation))
    }

    @Test
    fun `binding snapshots the assistant configuration`() {
        val bound = conversation().bindConfig(settings())

        assertEquals(
            ConversationConfig(
                chatModelId = modelA.id,
                reasoningLevel = ReasoningLevel.HIGH,
                enableWebSearch = true,
                builtInSearch = true,
                mcpServers = setOf(mcpServer),
                workspaceId = workspace,
                enabledSkills = setOf("skill"),
            ),
            bound.config,
        )
        assertEquals(setOf(injection), bound.modeInjectionIds)
        assertEquals(setOf(lorebook), bound.lorebookIds)
        assertSame(bound, bound.bindConfig(settings(assistant.copy(reasoningLevel = ReasoningLevel.OFF))))
    }

    @Test
    fun `binding resolves the global default model`() {
        val assistant = assistant.copy(chatModelId = null)

        val bound = conversation(assistant).bindConfig(settings(assistant))

        assertEquals(modelB.id, bound.config?.chatModelId)
        assertEquals(false, bound.config?.builtInSearch)
    }

    @Test
    fun `bound conversation ignores later assistant changes`() {
        val bound = conversation().bindConfig(settings())
        val changed = assistant.copy(
            chatModelId = modelB.id,
            reasoningLevel = ReasoningLevel.OFF,
            enableWebSearch = false,
            mcpServers = emptySet(),
            workspaceId = null,
            enabledSkills = emptySet(),
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            systemPrompt = "changed",
        )

        val resolved = settings(changed).getAssistantOf(bound)

        assertEquals(assistant.copy(systemPrompt = "changed"), resolved)
        assertEquals(modelA, settings(changed).getChatModelOf(bound))
    }

    @Test
    fun `bound built-in search overrides the model switch`() {
        val bound = conversation().bindConfig(settings())
        val disabled = bound.copy(config = bound.config?.copy(builtInSearch = false))
        val withoutModelSearch = settings().copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA.copy(tools = emptySet()), modelB))),
        )

        assertFalse(BuiltInTools.Search in settings().getChatModelOf(disabled)!!.tools)
        assertTrue(BuiltInTools.Search in withoutModelSearch.getChatModelOf(bound)!!.tools)
    }

    @Test
    fun `bound conversation falls back to the assistant model when its model is removed`() {
        val bound = conversation().bindConfig(settings())
        val withoutModelA = settings(assistant.copy(chatModelId = null)).copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelB))),
        )

        assertEquals(modelB.id, withoutModelA.getChatModelOf(bound)?.id)
        // 会话上固定的内置搜索开关属于被删掉的模型，不能套到退回的模型上
        assertEquals(modelB, withoutModelA.getChatModelOf(bound))
    }

    @Test
    fun `binding again pins the assistant model once the pinned model is removed`() {
        val bound = conversation().bindConfig(settings())
        val withoutModelA = settings(assistant.copy(chatModelId = null)).copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelB))),
        )

        val rebound = bound.bindConfig(withoutModelA)

        assertEquals(bound.config?.copy(chatModelId = modelB.id, builtInSearch = false), rebound.config)
        assertEquals(bound.modeInjectionIds, rebound.modeInjectionIds)
        assertSame(rebound, rebound.bindConfig(withoutModelA))
    }

    @Test
    fun `bound conversation drops references to removed items`() {
        val bound = conversation().bindConfig(settings())
        val emptied = settings().copy(mcpServers = emptyList(), modeInjections = emptyList(), lorebooks = emptyList())

        val resolved = emptied.getAssistantOf(bound)

        assertEquals(emptySet<Uuid>(), resolved.mcpServers)
        assertEquals(emptySet<Uuid>(), resolved.modeInjectionIds)
        assertEquals(emptySet<Uuid>(), resolved.lorebookIds)
    }

    @Test
    fun `chat page update goes to the assistant before the conversation starts`() {
        val conversation = conversation()
        val updated = assistant.copy(reasoningLevel = ReasoningLevel.LOW)

        assertSame(conversation, conversation.withAssistantUpdate(updated, settings()))
        assertSame(updated, updated.withoutConversationFields(conversation, assistant))
    }

    @Test
    fun `chat page update stays on the started conversation`() {
        val bound = conversation().bindConfig(settings())
        val newInjection = Uuid.random()
        val updated = settings().getAssistantOf(bound).copy(
            reasoningLevel = ReasoningLevel.LOW,
            modeInjectionIds = setOf(newInjection),
            quickMessageIds = setOf(Uuid.random()),
        )

        val conversation = bound.withAssistantUpdate(updated, settings())
        val stored = updated.withoutConversationFields(bound, assistant)

        assertEquals(ReasoningLevel.LOW, conversation.config?.reasoningLevel)
        assertEquals(setOf(newInjection), conversation.modeInjectionIds)
        // 不随会话固定的字段仍然写回助手
        assertEquals(assistant.copy(quickMessageIds = updated.quickMessageIds), stored)
    }

    @Test
    fun `switching model follows the new model's built-in search`() {
        val bound = conversation().bindConfig(settings())
        val updated = settings().getAssistantOf(bound).copy(chatModelId = modelB.id)

        val conversation = bound.withAssistantUpdate(updated, settings())

        assertEquals(modelB.id, conversation.config?.chatModelId)
        assertEquals(false, conversation.config?.builtInSearch)
    }

    @Test
    fun `switching workspace resets the working directory`() {
        val bound = conversation().bindConfig(settings()).copy(workspaceCwd = "/workspace/project")
        val assistantView = settings().getAssistantOf(bound)

        val sameWorkspace = bound.withAssistantUpdate(assistantView.copy(reasoningLevel = ReasoningLevel.LOW), settings())
        val otherWorkspace = bound.withAssistantUpdate(assistantView.copy(workspaceId = Uuid.random()), settings())

        assertEquals("/workspace/project", sameWorkspace.workspaceCwd)
        assertNull(otherWorkspace.workspaceCwd)
    }

    @Test
    fun `conversation bound before per-conversation config keeps its own injections`() {
        val conversationInjection = Uuid.random()
        val conversation = conversation().copy(modeInjectionIds = setOf(conversationInjection))

        val bound = conversation.bindConfig(settings())

        // 会话自己绑定过的保留，没绑定过的继承助手
        assertEquals(setOf(conversationInjection), bound.modeInjectionIds)
        assertEquals(setOf(lorebook), bound.lorebookIds)
    }

    // ---- 助手被删除后的隔离 ----

    private val otherAssistant = Assistant(
        name = "Other",
        systemPrompt = "other persona",
        enableMemory = true,
        enableRecentChatsReference = true,
    )

    private fun settingsWithout() = settings(otherAssistant)

    @Test
    fun `a deleted assistant is never replaced by the current assistant`() {
        val bound = conversation().bindConfig(settings())

        val resolved = settingsWithout().getAssistantOf(bound)

        // 同一个 ID 的安全占位：不会变成当前助手的名字、人设或记忆
        assertEquals(assistant.id, resolved.id)
        assertEquals("", resolved.name)
        assertEquals("", resolved.systemPrompt)
        assertFalse(resolved.enableMemory)
        assertFalse(resolved.autoConsolidateMemory)
        assertFalse(resolved.enableRecentChatsReference)
        assertTrue(resolved.localTools.isEmpty())
        assertEquals(emptySet<Uuid>(), resolved.mcpServers)
        assertNull(resolved.workspaceId)
        assertEquals(emptySet<String>(), resolved.enabledSkills)
        assertEquals(emptySet<Uuid>(), resolved.modeInjectionIds)
        assertEquals(emptySet<Uuid>(), resolved.lorebookIds)
        assertFalse(resolved.enableWebSearch)
        assertNotEquals(otherAssistant.id, resolved.id)
    }

    @Test
    fun `a deleted assistant does not resurrect the pinned conversation config`() {
        val injection = Uuid.random()
        val skill = "pinned-skill"
        val started = conversation().bindConfig(settings())
        val bound = started.copy(
            config = started.config?.copy(enabledSkills = setOf(skill)),
            modeInjectionIds = setOf(injection),
        )

        val resolved = settingsWithout().getAssistantOf(bound)

        // 会话上固定的工具、工作区、技能、注入都属于已经不存在的助手
        assertEquals(emptySet<Uuid>(), resolved.mcpServers)
        assertNull(resolved.workspaceId)
        assertEquals(emptySet<String>(), resolved.enabledSkills)
        assertEquals(emptySet<Uuid>(), resolved.modeInjectionIds)
        assertEquals(emptySet<Uuid>(), resolved.lorebookIds)
        assertTrue(resolved.localTools.isEmpty())
    }

    @Test
    fun `a deleted assistant yields no chat model`() {
        val bound = conversation().bindConfig(settings())

        assertNull(settingsWithout().getChatModelOf(bound))
        assertNull(settingsWithout().getChatModelOf(conversation()))
    }

    @Test
    fun `the stored assistant of a deleted assistant keeps the original id`() {
        val stored = settingsWithout().getStoredAssistantOf(conversation())

        assertEquals(assistant.id, stored.id)
        assertFalse(stored.enableMemory)
        assertFalse(stored.autoConsolidateMemory)
        assertTrue(stored.regexes.isEmpty())
        assertTrue(stored.presetMessages.isEmpty())
        assertEquals(emptySet<Uuid>(), stored.quickMessageIds)
    }

    @Test
    fun `a deleted assistant does not get a placeholder config pinned`() {
        val conversation = conversation()

        // 不把空占位快照固定到会话上，等用户显式迁移
        assertSame(conversation, conversation.bindConfig(settingsWithout()))
    }

    // ---- 用户主动迁移 ----

    private val target = Assistant(
        id = Uuid.random(),
        name = "Target",
        chatModelId = modelB.id,
        reasoningLevel = ReasoningLevel.LOW,
        enableWebSearch = true,
    )

    private fun targetSettings(target: Assistant = this.target) =
        settings(assistant).copy(assistants = listOf(assistant, target))

    private val userMessage = UIMessage.user("hello")
    private val replyMessage = UIMessage.assistant("hi")

    @Test
    fun `migration keeps the messages and title and rebinds everything else`() {
        val messages = listOf(userMessage.toMessageNode(), replyMessage.toMessageNode())
        val conversation = conversation().bindConfig(settings()).copy(
            title = "Old title",
            messageNodes = messages,
            chatSuggestions = listOf("suggest"),
            customSystemPrompt = "old rules",
            workspaceCwd = "/workspace/old",
            folderId = Uuid.random(),
        )

        val migrated = conversation.migrateToAssistant(target, targetSettings())

        assertEquals(conversation.id, migrated.id)
        assertEquals("Old title", migrated.title)
        assertEquals(messages, migrated.messageNodes)
        assertEquals(target.id, migrated.assistantId)
        assertEquals(
            ConversationConfig(
                chatModelId = modelB.id,
                reasoningLevel = ReasoningLevel.LOW,
                enableWebSearch = true,
                builtInSearch = false,
            ),
            migrated.config,
        )
        assertEquals(target.modeInjectionIds, migrated.modeInjectionIds)
        assertEquals(target.lorebookIds, migrated.lorebookIds)
        // 旧助手留下的东西不跟过来
        assertNull(migrated.workspaceCwd)
        assertNull(migrated.folderId)
        assertNull(migrated.customSystemPrompt)
        assertEquals(emptyList<String>(), migrated.chatSuggestions)
    }

    @Test
    fun `migration does not modify the source conversation`() {
        val conversation = conversation().bindConfig(settings()).copy(workspaceCwd = "/workspace/old")

        val migrated = conversation.migrateToAssistant(target, targetSettings())

        assertEquals(assistant.id, conversation.assistantId)
        assertEquals("/workspace/old", conversation.workspaceCwd)
        assertNotSame(conversation, migrated)
    }

    @Test
    fun `migrated conversation resolves to the target assistant and model`() {
        val migrated = conversation().bindConfig(settings()).migrateToAssistant(target, targetSettings())

        assertEquals(target, targetSettings().getAssistantOf(migrated))
        assertEquals(modelB, targetSettings().getChatModelOf(migrated))
        // 迁移后已经按目标助手固定过，普通绑定不再改动它
        assertSame(migrated, migrated.bindConfig(targetSettings()))
    }

    @Test
    fun `migration follows the target model's built-in search`() {
        val target = target.copy(chatModelId = modelA.id)

        val migrated = conversation().bindConfig(settings()).migrateToAssistant(target, targetSettings(target))

        assertEquals(modelA.id, migrated.config?.chatModelId)
        assertEquals(true, migrated.config?.builtInSearch)
        assertTrue(BuiltInTools.Search in targetSettings(target).getChatModelOf(migrated)!!.tools)
    }

    @Test
    fun `migration without an explicit model pins the global default`() {
        val target = target.copy(chatModelId = null)

        val migrated = conversation().bindConfig(settings()).migrateToAssistant(target, targetSettings(target))

        assertEquals(modelB.id, migrated.config?.chatModelId)
        assertEquals(false, migrated.config?.builtInSearch)
        assertEquals(modelB, targetSettings(target).getChatModelOf(migrated))
    }

    @Test
    fun `migration drops the old assistant's compacted summaries only`() {
        val summary = UIMessage.user("summary written under the old rules").copy(isContextCheckpoint = true)
        val firstNode = userMessage.toMessageNode()
        val onlySummary = summary.toMessageNode()
        val summarySelected = MessageNode(messages = listOf(summary, replyMessage), selectIndex = 0)
        val replySelected = MessageNode(messages = listOf(summary, replyMessage), selectIndex = 1)
        val conversation = conversation().copy(
            messageNodes = listOf(firstNode, onlySummary, summarySelected, replySelected),
        )

        val migrated = conversation.migrateToAssistant(target, targetSettings())

        // 只带摘要的节点整条去掉；混合节点只去摘要，节点 id 和另一条消息都留着
        assertEquals(3, migrated.messageNodes.size)
        assertEquals(firstNode, migrated.messageNodes[0])
        val summarySelectedAfter = migrated.messageNodes[1]
        assertEquals(summarySelected.id, summarySelectedAfter.id)
        assertEquals(listOf(replyMessage), summarySelectedAfter.messages)
        assertEquals(0, summarySelectedAfter.selectIndex)
        val replySelectedAfter = migrated.messageNodes[2]
        assertEquals(replySelected.id, replySelectedAfter.id)
        assertEquals(listOf(replyMessage), replySelectedAfter.messages)
        assertEquals(0, replySelectedAfter.selectIndex)
        // 迁移不改原会话
        assertEquals(4, conversation.messageNodes.size)
        assertTrue(conversation.messageNodes[1].messages.single().isContextCheckpoint)
    }
}
