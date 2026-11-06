package me.rerere.rikkahub.data.model

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 思考档位范围的约束：只属于一段对话，默认取模型支持的最高档，用户选过的留在原地。
 *
 * 这些断言只覆盖行为边界，不碰 provider 怎么把档位写进请求（那是 ai 模块自己的测试）。
 */
class ConversationReasoningTest {
    // 支持思考和不支持思考的模型各一个：默认档位要按当前模型算，不能一次算死
    private val thinkingModel = Model(
        modelId = "thinking",
        displayName = "Thinking",
        abilities = listOf(ModelAbility.REASONING),
    )
    private val plainModel = Model(modelId = "plain", displayName = "Plain")

    private val assistant = Assistant(chatModelId = thinkingModel.id)

    private fun settings(
        assistant: Assistant = this.assistant,
        models: List<Model> = listOf(thinkingModel, plainModel),
    ) = Settings(
        assistantId = assistant.id,
        assistants = listOf(assistant),
        chatModelId = plainModel.id,
        providers = listOf(ProviderSetting.OpenAI(models = models)),
    )

    private fun conversation(assistant: Assistant = this.assistant) = Conversation(
        assistantId = assistant.id,
        messageNodes = emptyList(),
    )

    // ---- 默认值 ----

    @Test
    fun `a conversation defaults to the highest level the model supports`() {
        val conversation = conversation()

        assertEquals(ReasoningLevel.MAX, settings().reasoningLevelOf(conversation))
        assertEquals(ReasoningLevel.MAX, settings().getAssistantOf(conversation).reasoningLevel)
    }

    @Test
    fun `a model without thinking falls back to a native request`() {
        val plain = assistant.copy(chatModelId = plainModel.id)
        val conversation = conversation(plain)

        // AUTO = 不指定档位；模型本身也没有思考能力，请求保持原生
        assertEquals(ReasoningLevel.AUTO, settings(plain).reasoningLevelOf(conversation))
        assertEquals(ReasoningLevel.AUTO, settings(plain).getAssistantOf(conversation).reasoningLevel)
    }

    @Test
    fun `the default follows the model of the conversation`() {
        val bound = conversation().bindConfig(settings()).copy(
            config = ConversationConfig(chatModelId = thinkingModel.id),
        )
        val assistantView = settings().getAssistantOf(bound)
        val onPlainModel = bound.withAssistantUpdate(assistantView.copy(chatModelId = plainModel.id), settings())

        assertEquals(ReasoningLevel.MAX, settings().reasoningLevelOf(bound))
        // 换到不支持思考的模型，默认档位跟着变，不从会话上钉死旧值
        assertNull(onPlainModel.config?.reasoningLevel)
        assertEquals(ReasoningLevel.AUTO, settings().reasoningLevelOf(onPlainModel))
    }

    // ---- 还没开始的对话 ----

    @Test
    fun `a level picked before the first message stays on that conversation`() {
        val conversation = conversation()
        val picked = settings().getAssistantOf(conversation).copy(reasoningLevel = ReasoningLevel.LOW)

        val started = conversation.withAssistantUpdate(picked, settings())

        assertEquals(ReasoningLevel.LOW, started.config?.reasoningLevel)
        assertEquals(ReasoningLevel.LOW, settings().getAssistantOf(started).reasoningLevel)
        // 助手的默认值没被写出去
        assertEquals(ReasoningLevel.AUTO, settings().assistants.single().reasoningLevel)
        // 别的对话还是自己的默认值
        assertEquals(ReasoningLevel.MAX, settings().getAssistantOf(conversation).reasoningLevel)
    }

    @Test
    fun `the picked level survives the first freeze`() {
        val conversation = conversation()
        val started = conversation.withAssistantUpdate(
            settings().getAssistantOf(conversation).copy(reasoningLevel = ReasoningLevel.MEDIUM),
            settings(),
        )

        // 首次落库时拍的那份快照不会覆盖用户先选好的档位
        val frozen = started.bindConfig(settings())

        assertSame(started, frozen)
        assertEquals(ReasoningLevel.MEDIUM, frozen.config?.reasoningLevel)
        assertEquals(ReasoningLevel.MEDIUM, settings().reasoningLevelOf(frozen))
    }

    @Test
    fun `a change that is not the reasoning picker does not pin anything`() {
        val conversation = conversation()
        val assistantView = settings().getAssistantOf(conversation)
        val updated = assistantView.copy(enableWebSearch = true)

        // 档位没变（仍是生效的默认值）：不加会话配置，改动照旧写回助手
        assertSame(conversation, conversation.withAssistantUpdate(updated, settings()))
        assertEquals(
            updated.copy(reasoningLevel = assistant.reasoningLevel),
            updated.withoutConversationFields(conversation, settings().assistants.single()),
        )
    }

    // ---- 已经开始的对话 ----

    private fun boundWith(level: ReasoningLevel?) =
        conversation().bindConfig(settings()).let { it.copy(config = it.config?.copy(reasoningLevel = level)) }

    @Test
    fun `other settings do not reset the level picked in this conversation`() {
        val bound = boundWith(ReasoningLevel.LOW)
        val assistantView = settings().getAssistantOf(bound)

        val afterSearch = bound.withAssistantUpdate(assistantView.copy(enableWebSearch = true), settings())
        val afterModel = bound.withAssistantUpdate(assistantView.copy(chatModelId = plainModel.id), settings())

        assertEquals(ReasoningLevel.LOW, afterSearch.config?.reasoningLevel)
        assertEquals(ReasoningLevel.LOW, afterModel.config?.reasoningLevel)
    }

    @Test
    fun `other settings are not written back with the local level`() {
        val bound = boundWith(ReasoningLevel.LOW)
        val stored = settings().getStoredAssistantOf(bound)
        val updated = settings().getAssistantOf(bound).copy(
            enableWebSearch = true,
            quickMessageIds = setOf(Uuid.random()),
        )

        val written = updated.withoutConversationFields(bound, stored)

        // 搜索这类不随会话固定的字段照旧写回助手，档位保持在助手上原来的值
        assertEquals(stored.enableWebSearch, written.enableWebSearch)
        assertEquals(updated.quickMessageIds, written.quickMessageIds)
        assertEquals(ReasoningLevel.AUTO, written.reasoningLevel)
    }

    @Test
    fun `a level picked on a started conversation replaces only its own value`() {
        val bound = conversation().bindConfig(settings())
        val picked = settings().getAssistantOf(bound).copy(reasoningLevel = ReasoningLevel.XHIGH)

        val updated = bound.withAssistantUpdate(picked, settings())

        assertEquals(ReasoningLevel.XHIGH, updated.config?.reasoningLevel)
        assertEquals(ReasoningLevel.AUTO, settings().assistants.single().reasoningLevel)
    }

    @Test
    fun `an explicit level from older data is kept`() {
        // 旧数据里固定过档位的会话读出来是非空值，即使模型支持思考也不改成最高档
        val bound = boundWith(ReasoningLevel.OFF)

        assertEquals(ReasoningLevel.OFF, settings().reasoningLevelOf(bound))
        assertEquals(ReasoningLevel.OFF, settings().getAssistantOf(bound).reasoningLevel)
    }

    // ---- 迁移 ----

    @Test
    fun `migration keeps the conversation's own level`() {
        val target = Assistant(name = "Target", chatModelId = plainModel.id, reasoningLevel = ReasoningLevel.LOW)
        val migratedSettings = settings().copy(assistants = listOf(assistant, target))
        val bound = boundWith(ReasoningLevel.HIGH)

        val migrated = bound.migrateToAssistant(target, migratedSettings)

        // 迁移换助手、换模型，但这段对话自己的选择不跟着目标助手的默认值走
        assertEquals(ReasoningLevel.HIGH, migrated.config?.reasoningLevel)
        assertEquals(ReasoningLevel.HIGH, migratedSettings.reasoningLevelOf(migrated))
    }

    @Test
    fun `migration does not turn a default into a pinned level`() {
        val target = Assistant(name = "Target", chatModelId = plainModel.id, reasoningLevel = ReasoningLevel.HIGH)
        val migratedSettings = settings().copy(assistants = listOf(assistant, target))

        val migrated = conversation().bindConfig(settings()).migrateToAssistant(target, migratedSettings)

        // 没选过的仍然是「没选过」：迁移不给这段对话钉上一个档位
        assertNull(migrated.config?.reasoningLevel)
        assertEquals(ReasoningLevel.AUTO, migratedSettings.reasoningLevelOf(migrated))
    }
}
