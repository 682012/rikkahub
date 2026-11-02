package me.rerere.ai.provider.providers.claude

import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class ClaudeUsageSnapshotIntegrationTest {
    @Test fun `partial usage preserves reads but an explicit corrected zero is really displayed as zero`() {
        val decoder = ClaudeStreamDecoder()
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("test"))
        fun accept(body: String) {
            decoder.accept(SseEvent(event = "message_delta", data = body)).chunks.forEach {
                messages = handler.handle(messages, it)
            }
        }
        accept("""{"usage":{"input_tokens":100,"cache_read_input_tokens":900,"cache_creation_input_tokens":200,"output_tokens":1}}""")
        accept("""{"usage":{"input_tokens":100,"output_tokens":50}}""")
        assertEquals(1200, messages.last().usage!!.promptTokens)
        assertEquals(900, messages.last().usage!!.cachedTokens)
        accept("""{"usage":{"cache_read_input_tokens":0,"output_tokens":60}}""")
        assertEquals(300, messages.last().usage!!.promptTokens)
        assertEquals(0, messages.last().usage!!.cachedTokens)
        assertEquals(60, messages.last().usage!!.completionTokens)
    }
}
