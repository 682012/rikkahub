package me.rerere.ai.provider.providers.claudeweb

import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.stream.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.Reader

/** 真实字节流 → 真实解码：这里不 mock 任何解析逻辑。 */
class ClaudeWebSseReaderTest {

    private class OneBytePerReadStream(private val bytes: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (index >= bytes.size) return -1
            b[off] = bytes[index++]
            return 1
        }
    }

    private fun reader(body: String, trickle: Boolean = false): Reader {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val stream = if (trickle) OneBytePerReadStream(bytes) else ByteArrayInputStream(bytes)
        return stream.reader(Charsets.UTF_8)
    }

    private fun ClaudeWebSseReader.drain(): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        while (true) {
            val event = nextEvent() ?: break
            events += event
        }
        return events
    }

    @Test
    fun `utf8 text survives one byte per read framing`() {
        val body = ClaudeWebFixtures.textStream("你好", "🌍 世界")
        val reader = ClaudeWebSseReader(reader(body, trickle = true))
        val events = reader.drain()

        val deltas = events.filter { it.event == "content_block_delta" }.map { it.data }
        assertEquals(2, deltas.size)
        assertTrue(deltas[0].contains(ClaudeWebFixtures.json("你好")))
        assertTrue(deltas[1].contains(ClaudeWebFixtures.json("🌍 世界")))
        // 事件以空行结束，EOF 干净，不会多出半截事件。
        assertTrue(events.none { it.data.contains("�") })
    }

    @Test
    fun `crlf line endings are accepted`() {
        val body = "event: message_stop\r\ndata: {\"type\":\"message_stop\"}\r\n\r\n"
        val events = ClaudeWebSseReader(reader(body)).drain()

        assertEquals(1, events.size)
        assertEquals("message_stop", events.single().event)
        assertEquals("{\"type\":\"message_stop\"}", events.single().data)
    }

    @Test
    fun `a stream that ends mid line is rejected`() {
        val body = "event: message_start\ndata: {\"type\":\"mess"
        try {
            ClaudeWebSseReader(reader(body)).drain()
            fail("truncated line must not decode as a clean end")
        } catch (e: WebProtocolException) {
            assertTrue(e is java.io.IOException)
        }
    }

    @Test
    fun `a pending event without terminating blank line is rejected`() {
        val body = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n"
        try {
            ClaudeWebSseReader(reader(body)).drain()
            fail("pending event at EOF must not be delivered")
        } catch (e: WebProtocolException) {
            assertTrue(e is java.io.IOException)
        }
    }

    @Test
    fun `oversized event is rejected`() {
        val body = "event: content_block_delta\ndata: " + "x".repeat(64) + "\n\n"
        try {
            ClaudeWebSseReader(reader(body), maxEventChars = 32).drain()
            fail("oversized event must be rejected")
        } catch (e: WebProtocolException) {
            assertTrue(e is java.io.IOException)
        }
    }

    @Test
    fun `total size limit is enforced`() {
        val body = "event: ping\ndata: 1\n\n".repeat(8)
        try {
            ClaudeWebSseReader(reader(body), maxTotalChars = 40).drain()
            fail("total size limit must be enforced")
        } catch (e: WebProtocolException) {
            assertTrue(e is java.io.IOException)
        }
    }

    @Test
    fun `comments and unknown fields are ignored`() {
        val body = ": keep-alive\nretry: 1000\n\nevent: message_stop\ndata: {}\n\n"
        val events = ClaudeWebSseReader(reader(body)).drain()

        assertEquals(1, events.size)
        assertEquals("message_stop", events.single().event)
        assertNull(events.single().id)
    }
}
