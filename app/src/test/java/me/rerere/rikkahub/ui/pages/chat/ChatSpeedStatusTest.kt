package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.agent.GenerationTiming
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid
import org.junit.Assert.*
import org.junit.Test

class ChatSpeedStatusTest {
    private fun chat(timing: GenerationTiming?): Conversation {
        val message=UIMessage(role=MessageRole.ASSISTANT,parts=emptyList(),generationTiming=timing)
        return Conversation(assistantId=Uuid.random(),messageNodes=listOf(MessageNode(messages=listOf(message),selectIndex=0)))
    }
    @Test fun measuredSpeedUsesStreamNotRequestWait() {
        val c=chat(GenerationTiming(generationMs=2000,requestMs=12000,outputTokens=100,measuredSteps=1,totalSteps=1))
        assertEquals(50.0,conversationSpeed(c)!!,.0001)
    }
    @Test fun shortBurstHasNoInventedRate() { assertNull(conversationSpeed(chat(GenerationTiming(generationMs=100,outputTokens=500,measuredSteps=1,totalSteps=1)))) }
    @Test fun unmeasuredNonStreamHasNoRate() { assertNull(conversationSpeed(chat(GenerationTiming(requestMs=2000,outputTokens=100,totalSteps=1)))) }
    @Test fun oldMessageWithoutTimingHasNoRate() { assertNull(conversationSpeed(chat(null))) }
}
