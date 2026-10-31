package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCompletionsRoleCompatibilityTest {
    @Test
    fun `standard roles remain case insensitive and unknown roles are ignored`() {
        assertEquals(MessageRole.ASSISTANT, MessageRole.fromValueOrNull("assistant"))
        assertEquals(MessageRole.USER, MessageRole.fromValueOrNull("UsEr"))
        assertNull(MessageRole.fromValueOrNull(null))
        assertNull(MessageRole.fromValueOrNull(""))
        assertNull(MessageRole.fromValueOrNull("nonstandard"))
    }

    @Test
    fun `empty and nonstandard stream roles do not discard content`() {
        val decoder = ChatCompletionsStreamDecoder()
        val chunks = listOf(
            """{"id":"r1","choices":[{"index":0,"delta":{"role":"","content":"Hi"}}]}""",
            """{"id":"r1","choices":[{"index":0,"delta":{"role":"nonstandard","content":" there"}}]}""",
        ).flatMap { decoder.accept(SseEvent(data = it)).chunks }
        assertEquals("Hi there", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
    }
}
