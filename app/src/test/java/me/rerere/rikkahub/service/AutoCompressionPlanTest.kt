package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.AutoCompressSettings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AutoCompressionPlanTest {
    private val reply = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(),
        usage = TokenUsage(promptTokens = 8_500, completionTokens = 100), contextTokens = 8_600)
    private val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        UIMessage.user("old").toMessageNode(), reply.toMessageNode(), UIMessage.user("new question").toMessageNode()))

    @Test fun `summarizes previous history and keeps new user input outside checkpoint`() {
        val plan = autoCompressionPlan(conversation, AutoCompressSettings(enabled = true))
        assertNotNull(plan)
        plan!!
        assertEquals(2, plan.messages.size)
        assertEquals("old", plan.messages.first().toText())
        assertTrue(plan.exceedsThreshold(10_000, 80))
        assertFalse(plan.exceedsThreshold(20_000, 80))
    }

    @Test fun `never compresses while continuing an assistant turn`() {
        val continuing = conversation.copy(messageNodes = conversation.messageNodes.dropLast(1))
        assertNull(autoCompressionPlan(continuing, AutoCompressSettings(enabled = true)))
    }

    @Test fun `disabled means no work`() {
        assertNull(autoCompressionPlan(conversation, AutoCompressSettings(enabled = false)))
    }
}
