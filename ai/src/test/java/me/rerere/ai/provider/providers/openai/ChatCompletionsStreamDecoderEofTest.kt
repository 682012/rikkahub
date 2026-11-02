package me.rerere.ai.provider.providers.openai

import java.io.IOException
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断流（VPN/代理直接把连接 EOF）不能被当成正常完成。
 * 只有 `[DONE]` 或非空 `finish_reason` 才是 Chat Completions 的协议终态。
 */
class ChatCompletionsStreamDecoderEofTest {
    private fun text(content: String, finishReason: String? = null): SseEvent = SseEvent(
        data = """{"id":"r1","model":"m1","choices":[{"index":0,"delta":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}},"finish_reason":${finishReason?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull}}]}""",
    )

    private val usageOnly = SseEvent(
        data = """{"id":"r1","model":"m1","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""",
    )

    @Test
    fun `eof without done or finish reason is reported as truncation`() {
        val decoder = ChatCompletionsStreamDecoder()
        val result = decoder.accept(text("半截回复"))

        assertFalse("有 delta 但没有终态，不算完成", result.completed)
        assertEquals(
            "半截回复",
            result.chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text },
        )

        val error = assertThrows(IOException::class.java) { decoder.onClosed() }
        assertTrue("必须是专门的截断异常", error is StreamTruncatedException)
    }

    @Test
    fun `done frame ends the stream normally`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(text("完整回复"))

        val done = decoder.accept(SseEvent(data = "[DONE]"))
        assertTrue(done.completed)
        assertEquals(1, done.chunks.count { it is StreamChunk.Finish })

        // 终态之后 onClosed 幂等，不再产生 chunk
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `finish reason alone is enough without done frame`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(text("完整回复"))
        val terminal = decoder.accept(text("", finishReason = "stop"))

        // 收到 finish_reason 也不提前 finish，好让后面的 usage 尾帧照常解析
        assertEquals(0, terminal.chunks.count { it is StreamChunk.Finish })

        val closing = decoder.onClosed()
        assertEquals(
            "stop",
            closing.filterIsInstance<StreamChunk.Finish>().single().finishReason,
        )
    }

    @Test
    fun `usage only tail after finish reason is preserved and finished once`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(text("完整回复"))
        decoder.accept(text("", finishReason = "length"))
        val tail = decoder.accept(usageOnly)

        assertEquals(1, tail.chunks.filterIsInstance<StreamChunk.Usage>().size)
        assertEquals(0, tail.chunks.count { it is StreamChunk.Finish })

        val closing = decoder.onClosed()
        assertEquals(1, closing.count { it is StreamChunk.Finish })
        assertEquals("length", closing.filterIsInstance<StreamChunk.Finish>().single().finishReason)
    }

    @Test
    fun `reasoning and tool deltas alone still require a terminal frame`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(
            data = """{"id":"r1","choices":[{"index":0,"delta":{"reasoning_content":"想一想"},"finish_reason":null}]}""",
        ))
        decoder.accept(SseEvent(
            data = """{"id":"r1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"shell","arguments":"{}"}}]},"finish_reason":null}]}""",
        ))

        // 思考/工具增量只是普通内容，不构成终态
        assertThrows(StreamTruncatedException::class.java) { decoder.onClosed() }
    }

    @Test
    fun `explicit null finish reason from relays is not a terminal frame`() {
        val decoder = ChatCompletionsStreamDecoder()
        decoder.accept(SseEvent(
            data = """{"id":"r1","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":null}],"usage":null}""",
        ))

        assertThrows(StreamTruncatedException::class.java) { decoder.onClosed() }
    }
}
