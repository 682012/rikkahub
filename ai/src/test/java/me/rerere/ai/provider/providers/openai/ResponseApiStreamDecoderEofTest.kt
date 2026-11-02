package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses 流的终态是 `response.completed` / `response.incomplete` / `response.failed`
 * （`failed` 走错误路径，仍抛原始错误）。
 */
class ResponseApiStreamDecoderEofTest {
    private fun ev(type: String, data: String) = SseEvent(event = type, data = data)

    private val textStart = ev(
        "response.content_part.added",
        """{"type":"response.content_part.added","item_id":"item_1","output_index":0,"content_index":0,"part":{"type":"output_text","text":""}}""",
    )

    private val textDelta = ev(
        "response.output_text.delta",
        """{"type":"response.output_text.delta","item_id":"item_1","output_index":0,"content_index":0,"delta":"半截"}""",
    )

    private val completed = ev(
        "response.completed",
        """{"type":"response.completed","response":{"id":"resp_1","model":"m","status":"completed","usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}}""",
    )

    private fun feedPartial(decoder: ResponseApiStreamDecoder): List<StreamChunk> =
        decoder.accept(textStart).chunks + decoder.accept(textDelta).chunks

    @Test
    fun `eof before terminal event is reported as truncation`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = feedPartial(decoder)

        assertEquals("半截", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertFalse(decoder.accept(textDelta).completed)
        assertThrows(StreamTruncatedException::class.java) { decoder.onClosed() }
    }

    @Test
    fun `completed event ends the stream normally`() {
        val decoder = ResponseApiStreamDecoder()
        feedPartial(decoder)

        val terminal = decoder.accept(completed)
        assertTrue(terminal.completed)
        assertEquals(1, terminal.chunks.count { it is StreamChunk.Finish })
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `incomplete event without done frame still ends normally`() {
        val decoder = ResponseApiStreamDecoder()
        feedPartial(decoder)

        val terminal = decoder.accept(ev(
            "response.incomplete",
            """{"type":"response.incomplete","response":{"id":"resp_2","model":"m","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}""",
        ))
        assertTrue(terminal.completed)
        // 终态事件已经收尾，onClosed 幂等
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `failed event keeps the provider error and does not report truncation`() {
        val decoder = ResponseApiStreamDecoder()
        feedPartial(decoder)

        val error = assertThrows(Exception::class.java) {
            decoder.accept(ev(
                "response.failed",
                """{"type":"response.failed","response":{"status":"failed","error":{"message":"Upstream generation failed"}}}""",
            ))
        }
        assertEquals("Upstream generation failed", error.message)
        assertTrue("错误路径已经收尾，不再重复报截断", decoder.onClosed().isEmpty())
    }
}
