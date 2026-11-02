package me.rerere.ai.provider.providers.claude

import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude Messages 流的协议终态是 `message_stop` 或明确的非空 `stop_reason`。
 * 只收到 content_block_* 就 EOF 属于断流，必须报错而不是收尾成成功。
 */
class ClaudeStreamDecoderEofTest {
    private fun ev(type: String, data: String) = SseEvent(event = type, data = data)

    private fun textBlockEvents(): List<SseEvent> = listOf(
        ev("message_start", """{"type":"message_start","message":{"id":"msg_1","model":"claude-x","usage":{"input_tokens":5,"output_tokens":0}}}"""),
        ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
        ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"半截"}}"""),
        ev("content_block_stop", """{"type":"content_block_stop","index":0}"""),
    )

    @Test
    fun `eof before message stop is reported as truncation`() {
        val decoder = ClaudeStreamDecoder()
        val chunks = textBlockEvents().flatMap { decoder.accept(it).chunks }

        assertEquals("半截", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertTrue(chunks.none { it is StreamChunk.Finish })

        assertThrows(StreamTruncatedException::class.java) { decoder.onClosed() }
    }

    @Test
    fun `message stop ends the stream normally`() {
        val decoder = ClaudeStreamDecoder()
        textBlockEvents().forEach { decoder.accept(it) }

        val stop = decoder.accept(ev("message_stop", """{"type":"message_stop"}"""))
        assertTrue(stop.completed)
        assertEquals(1, stop.chunks.count { it is StreamChunk.Finish })
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `explicit stop reason without message stop still ends normally`() {
        val decoder = ClaudeStreamDecoder()
        textBlockEvents().forEach { decoder.accept(it) }
        val delta = decoder.accept(
            ev(
                "message_delta",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":12}}""",
            ),
        )

        // stop_reason 是终态，但不提前 finish，usage 帧照常解析
        assertEquals(0, delta.chunks.count { it is StreamChunk.Finish })
        assertEquals(1, delta.chunks.filterIsInstance<StreamChunk.Usage>().size)

        val closing = decoder.onClosed()
        assertEquals(
            "end_turn",
            closing.filterIsInstance<StreamChunk.Finish>().single().finishReason,
        )
    }

    @Test
    fun `thinking and tool deltas alone are not terminal`() {
        val decoder = ClaudeStreamDecoder()
        listOf(
            ev("message_start", """{"type":"message_start","message":{"id":"msg_2","model":"claude-x"}}"""),
            ev(
                "content_block_start",
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            ),
            ev(
                "content_block_delta",
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"推理"}}""",
            ),
            ev(
                "content_block_start",
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"shell","input":{}}}""",
            ),
            ev(
                "content_block_delta",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"cmd\":"}}""",
            ),
        ).forEach { decoder.accept(it) }

        assertThrows(StreamTruncatedException::class.java) { decoder.onClosed() }
    }
}
