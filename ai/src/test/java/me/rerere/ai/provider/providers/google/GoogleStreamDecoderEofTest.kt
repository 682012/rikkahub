package me.rerere.ai.provider.providers.google

import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemini generateContent 流的终态是 candidate.finishReason，或 promptFeedback 的 blocking 判定。
 * Interactions API 的终态是 `interaction.completed`。
 */
class GoogleStreamDecoderEofTest {
    private val partial = SseEvent(
        data = """{"candidates":[{"content":{"role":"model","parts":[{"text":"半截"}]},"index":0}]}""",
    )

    private val terminal = SseEvent(
        data = """{"candidates":[{"content":{"role":"model","parts":[{"text":""}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,"totalTokenCount":5}}""",
    )

    @Test
    fun `gemini eof without finish reason is reported as truncation`() {
        val decoder = GoogleStreamDecoder(responseId = "r1", model = "gemini-x")
        decoder.accept(partial)

        assertThrows(StreamTruncatedException::class.java) { decoder.onClosed() }
    }

    @Test
    fun `gemini finish reason ends the stream normally`() {
        val decoder = GoogleStreamDecoder(responseId = "r1", model = "gemini-x")
        decoder.accept(partial)

        val last = decoder.accept(terminal)
        assertEquals(1, last.chunks.filterIsInstance<StreamChunk.Usage>().size)
        assertEquals(0, last.chunks.count { it is StreamChunk.Finish })

        val closing = decoder.onClosed()
        assertEquals("STOP", closing.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    @Test
    fun `gemini usage only tail after finish reason is preserved`() {
        val decoder = GoogleStreamDecoder(responseId = "r1", model = "gemini-x")
        decoder.accept(terminal)

        val tail = decoder.accept(
            SseEvent(data = """{"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,"totalTokenCount":5}}"""),
        )
        assertEquals(1, tail.chunks.filterIsInstance<StreamChunk.Usage>().size)
        assertEquals(1, decoder.onClosed().count { it is StreamChunk.Finish })
    }

    @Test
    fun `gemini prompt feedback block is a terminal state not truncation`() {
        val decoder = GoogleStreamDecoder(responseId = "r1", model = "gemini-x")

        assertThrows(IllegalStateException::class.java) {
            decoder.accept(SseEvent(data = """{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        }
    }

    @Test
    fun `interactions eof without interaction completed is reported as truncation`() {
        val decoder = InteractionsStreamDecoder()

        decoder.accept(SseEvent(
            event = "step.start",
            data = """{"event_type":"step.start","index":0,"step":{"type":"text"}}""",
        ))
        decoder.accept(SseEvent(
            event = "step.delta",
            data = """{"event_type":"step.delta","index":0,"delta":{"type":"text","text":"半截"}}""",
        ))

        assertThrows(StreamTruncatedException::class.java) { decoder.onClosed() }
    }

    @Test
    fun `interactions completed event ends the stream normally`() {
        val decoder = InteractionsStreamDecoder()
        decoder.accept(SseEvent(
            event = "step.start",
            data = """{"event_type":"step.start","index":0,"step":{"type":"text"}}""",
        ))
        val done = decoder.accept(SseEvent(
            event = "interaction.completed",
            data = """{"event_type":"interaction.completed","interaction":{"id":"v1","status":"completed","model":"gemini-x"}}""",
        ))

        assertTrue(done.completed)
        assertTrue(decoder.onClosed().isEmpty())
    }
}
