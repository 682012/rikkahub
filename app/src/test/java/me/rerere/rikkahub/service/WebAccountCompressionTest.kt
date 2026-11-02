package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.AutoCompressSettings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class WebAccountCompressionTest {
    @Test fun `web providers without billing usage still reach the client compression threshold`() {
        val messages = listOf(UIMessage.user("历史".repeat(3000)), UIMessage(
            role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("response"))), UIMessage.user("new"))
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = messages.map { it.toMessageNode() })
        assertNull(autoCompressionPlan(conversation, AutoCompressSettings(enabled = true)))
        val plan = autoCompressionPlan(conversation, AutoCompressSettings(enabled = true), true)!!
        assertTrue(plan.exceedsThreshold(10000, 80))
        assertEquals(2, plan.messages.size)
        assertTrue(plan.messages.all { it.usage == null })
    }
    @Test fun `UTF8 estimate is conservative and never a provider usage record`() {
        val text = UIMessage.user("你好abcd")
        assertEquals(37, estimateWebContextBudget(listOf(text)))
        assertNull(text.usage)
    }
    @Test fun `disabled compression remains disabled even for web accounts`() {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
            UIMessage.user("long".repeat(1000)), UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
            UIMessage.user("new")).map { it.toMessageNode() })
        assertNull(autoCompressionPlan(conversation, AutoCompressSettings(enabled = false), true))
    }
}
