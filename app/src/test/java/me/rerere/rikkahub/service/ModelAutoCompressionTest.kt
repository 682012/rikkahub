package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.AutoCompressSettings
import me.rerere.rikkahub.data.datastore.forModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ModelAutoCompressionTest {
    private val global = AutoCompressSettings(true, 80, Uuid.random())
    private val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        UIMessage.user("old").toMessageNode(),
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(),
            lastUsage = TokenUsage(promptTokens = 8_000, completionTokens = 0)).toMessageNode(),
        UIMessage.user("new").toMessageNode(),
    ))

    @Test fun `legacy model follows all global settings including summarizer`() {
        assertEquals(global, global.forModel(Model()))
        assertEquals(global.copy(enabled = false), global.copy(enabled = false).forModel(Model()))
    }

    @Test fun `model switch overrides enabled and disabled global independently`() {
        assertNull(autoCompressionPlan(conversation, global.forModel(Model(autoCompressEnabled = false))))
        assertNotNull(autoCompressionPlan(conversation,
            global.copy(enabled = false).forModel(Model(autoCompressEnabled = true))))
    }

    @Test fun `threshold is independent of switch override`() {
        val config = global.forModel(Model(autoCompressThresholdPercent = 60))
        assertTrue(config.enabled)
        assertEquals(60, config.thresholdPercent)
        assertEquals(global.modelId, config.modelId)
        assertFalse(global.copy(enabled = false).forModel(Model(autoCompressThresholdPercent = 60)).enabled)
    }

    @Test fun `each models window and threshold decide independently at exact boundary`() {
        val a = Model(contextLength = 10_000, autoCompressThresholdPercent = 80)
        val b = Model(contextLength = 20_000, autoCompressThresholdPercent = 80)
        val c = Model(contextLength = 20_000, autoCompressThresholdPercent = 40)
        fun triggers(model: Model): Boolean {
            val config = global.forModel(model)
            return autoCompressionPlan(conversation, config)?.exceedsThreshold(
                model.contextLength, config.thresholdPercent) ?: false
        }
        assertTrue(triggers(a))
        assertFalse(triggers(a.copy(contextLength = 10_001)))
        assertFalse(triggers(b))
        assertTrue(triggers(c))
    }

    @Test fun `resetting overrides restores current globals without changing original model`() {
        val model = Model(autoCompressEnabled = false, autoCompressThresholdPercent = 50, maxConcurrentRequests = 3)
        val reset = model.copy(autoCompressEnabled = null, autoCompressThresholdPercent = null)
        assertEquals(global.copy(thresholdPercent = 90), global.copy(thresholdPercent = 90).forModel(reset))
        assertEquals(false, model.autoCompressEnabled)
        assertEquals(3, reset.maxConcurrentRequests)
    }

    @Test fun `malformed imported thresholds are clamped without changing persistent data`() {
        val model = Model(autoCompressThresholdPercent = Int.MIN_VALUE)
        assertEquals(10, global.forModel(model).thresholdPercent)
        assertEquals(Int.MIN_VALUE, model.autoCompressThresholdPercent)
        assertEquals(100, global.forModel(model.copy(autoCompressThresholdPercent = Int.MAX_VALUE)).thresholdPercent)
        assertEquals(10, global.copy(thresholdPercent = -1).forModel(Model()).thresholdPercent)
    }
}
