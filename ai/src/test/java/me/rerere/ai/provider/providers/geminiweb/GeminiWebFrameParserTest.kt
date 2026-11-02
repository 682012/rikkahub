package me.rerere.ai.provider.providers.geminiweb

import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiWebFrameParserTest {

    @Test
    fun `extracts cumulative text ids rcid and terminal marker from a wrb fr frame`() {
        val frame = GeminiWebFrameParser.parse(
            GeminiWebFixtures.frame("hello", conversationId = "c_1", responseId = "rc_1", rcid = "rcid_1")
        )
        assertEquals("hello", frame.text)
        assertEquals("c_1", frame.conversationId)
        assertEquals("rc_1", frame.responseId)
        assertEquals("rcid_1", frame.rcid)
        assertTrue(frame.complete)
        assertFalse(frame.historyStored)
    }

    @Test
    fun `keeps escaped quotes and newlines from the payload`() {
        val text = "line1\nline2 \"quoted\" \\ backslash"
        val frame = GeminiWebFrameParser.parse(GeminiWebFixtures.frame(text))
        assertEquals(text, frame.text)
    }

    @Test
    fun `frame without candidate text yields no text`() {
        val heartbeat = """[["di",7],["af.httprm",7,"123"]]"""
        assertNull(GeminiWebFrameParser.parse(heartbeat).text)
    }

    @Test
    fun `history stored string alone is not a completed answer`() {
        val frame = GeminiWebFrameParser.parse(
            GeminiWebFixtures.frame("partial", complete = false, historyStored = true)
        )
        assertTrue(frame.historyStored)
        assertFalse(frame.complete)
    }

    @Test
    fun `completion comes only from candidate 8 0 equal 2`() {
        val running = GeminiWebFrameParser.parse(GeminiWebFixtures.frame("running", complete = false))
        val done = GeminiWebFrameParser.parse(GeminiWebFixtures.frame("done", complete = true))
        assertFalse(running.complete)
        assertTrue(done.complete)
    }

    @Test
    fun `error tuple is a session failure, never a silent empty answer`() {
        try {
            GeminiWebFrameParser.parse("""[["er",null,""]]""")
            fail("expected WebSessionException")
        } catch (e: WebSessionException) {
            assertTrue(e.message!!.contains("重新登录"))
        }
    }

    @Test
    fun `fatal nested error code aborts the answer`() {
        try {
            GeminiWebFrameParser.parse(GeminiWebFixtures.errorFrame(1100))
            fail("expected WebProtocolException")
        } catch (e: WebSessionException) {
            assertTrue(e.message!!.contains("1100"))
        }
    }

    @Test
    fun `non json payload is a protocol failure`() {
        try {
            GeminiWebFrameParser.parse("<html>nope</html>")
            fail("expected WebProtocolException")
        } catch (e: WebProtocolException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `monotonic snapshots turn into deltas that rebuild the final answer`() {
        val accumulator = GeminiWebTextAccumulator()
        assertEquals("Hel", accumulator.update("Hel"))
        assertEquals("lo", accumulator.update("Hello"))
        assertEquals(" 世界", accumulator.update("Hello 世界"))
        assertEquals("Hello 世界", accumulator.text)
        assertFalse(accumulator.complete)
    }

    @Test
    fun `revised snapshot keeps the latest text instead of concatenating prefixes`() {
        val accumulator = GeminiWebTextAccumulator()
        accumulator.update("Hello world")
        accumulator.update(GeminiWebFrame(text = "Hi there", complete = true))
        assertEquals("Hi there", accumulator.text)
        assertTrue(accumulator.complete)
    }

    @Test
    fun `different rcid never mixes text and the first candidate wins`() {
        val accumulator = GeminiWebTextAccumulator()
        accumulator.update(GeminiWebFrame(text = "first answer", rcid = "rcid_a", complete = true))
        assertEquals("", accumulator.update(GeminiWebFrame(text = "second answer", rcid = "rcid_b")))
        assertEquals("first answer", accumulator.text)
        assertEquals("rcid_a", accumulator.rcid)
    }
}
