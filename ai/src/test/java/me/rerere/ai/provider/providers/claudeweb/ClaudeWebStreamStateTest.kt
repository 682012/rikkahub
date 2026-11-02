package me.rerere.ai.provider.providers.claudeweb

import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.stream.StreamTruncatedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** SSE 事件 → 文本/终态：JSON 解析是真的，事件由真实的 [ClaudeWebSseReader] 解码。 */
class ClaudeWebStreamStateTest {

    private fun feed(state: ClaudeWebStreamState, body: String) {
        val reader = ClaudeWebSseReader(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)).reader(Charsets.UTF_8))
        while (true) {
            val event = reader.nextEvent() ?: break
            state.accept(event)
        }
    }

    @Test
    fun `text deltas are collected with the message id`() {
        val state = ClaudeWebStreamState()
        feed(state, ClaudeWebFixtures.textStream("Hel", "lo"))

        state.finish()
        assertEquals("Hello", state.text)
        assertEquals("msg_sanitized", state.messageId)
        assertTrue(state.terminal)
    }

    @Test
    fun `message_stop alone is a normal terminal`() {
        val state = ClaudeWebStreamState()
        feed(
            state,
            ClaudeWebFixtures.event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""") +
                ClaudeWebFixtures.event("message_stop", """{"type":"message_stop"}"""),
        )

        state.finish()
        assertEquals("ok", state.text)
    }

    @Test
    fun `stop_reason from message_delta can terminate the stream`() {
        val state = ClaudeWebStreamState()
        feed(
            state,
            ClaudeWebFixtures.event("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}}""") +
                ClaudeWebFixtures.event("message_delta", """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""),
        )

        state.finish()
        assertEquals("done", state.text)
        assertEquals("max_tokens", state.stopReason)
    }

    @Test
    fun `stream without a terminal event is truncated, not successful`() {
        val state = ClaudeWebStreamState()
        feed(state, ClaudeWebFixtures.textStream("半截回答", terminal = false))

        try {
            state.finish()
            fail("missing terminal must not be treated as a complete answer")
        } catch (e: StreamTruncatedException) {
            assertTrue(e is IOException)
        }
    }

    @Test
    fun `legacy completion snapshot needs a non-empty stop_reason`() {
        val complete = ClaudeWebStreamState()
        feed(
            complete,
            ClaudeWebFixtures.event("completion", """{"completion":"旧格式回答","stop_reason":"stop_sequence"}"""),
        )
        complete.finish()
        assertEquals("旧格式回答", complete.text)

        val open = ClaudeWebStreamState()
        feed(open, ClaudeWebFixtures.event("completion", """{"completion":"旧格式没结束","stop_reason":null}"""))
        try {
            open.finish()
            fail("legacy completion without stop_reason must be truncated")
        } catch (e: StreamTruncatedException) {
            assertTrue(e is IOException)
        }
    }

    @Test
    fun `error event surfaces only the error type, never the server body`() {
        val state = ClaudeWebStreamState()
        try {
            feed(
                state,
                ClaudeWebFixtures.event(
                    "error",
                    """{"type":"error","error":{"type":"overloaded_error","message":"SECRET_SERVER_TEXT"}}""",
                ),
            )
            fail("error event must throw")
        } catch (e: Exception) {
            assertFalse(e.message.orEmpty().contains("SECRET_SERVER_TEXT"))
            assertTrue(e is WebProtocolException)
        }
    }

    @Test
    fun `server tool blocks are ignored instead of becoming local tool calls`() {
        val state = ClaudeWebStreamState()
        feed(
            state,
            ClaudeWebFixtures.event(
                "content_block_start",
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"web_search"}}""",
            ) +
                ClaudeWebFixtures.event(
                    "content_block_delta",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"x\"}"}}""",
                ) +
                ClaudeWebFixtures.event(
                    "content_block_start",
                    """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
                ) +
                ClaudeWebFixtures.event(
                    "content_block_delta",
                    """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"只回答文本"}}""",
                ) +
                ClaudeWebFixtures.event("message_stop", """{"type":"message_stop"}"""),
        )

        state.finish()
        assertEquals("只回答文本", state.text)
    }

    @Test
    fun `initial text in content_block_start is kept`() {
        val state = ClaudeWebStreamState()
        feed(
            state,
            ClaudeWebFixtures.event(
                "content_block_start",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Hel"}}""",
            ) +
                ClaudeWebFixtures.event(
                    "content_block_delta",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}""",
                ) +
                ClaudeWebFixtures.event("message_stop", """{"type":"message_stop"}"""),
        )

        state.finish()
        assertEquals("Hello", state.text)
    }

    @Test
    fun `answer text over the client limit is rejected`() {
        val state = ClaudeWebStreamState(maxTextChars = 8)
        try {
            feed(
                state,
                ClaudeWebFixtures.event(
                    "content_block_delta",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"123456789"}}""",
                ),
            )
            fail("oversized answer must be rejected")
        } catch (e: WebProtocolException) {
            assertTrue(e is IOException)
        }
    }
}
