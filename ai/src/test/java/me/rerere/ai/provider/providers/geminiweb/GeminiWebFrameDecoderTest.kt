package me.rerere.ai.provider.providers.geminiweb

import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiWebFrameDecoderTest {

    private val frames = listOf(
        GeminiWebFixtures.frame("Hel", complete = false),
        GeminiWebFixtures.frame("Hello 世界", complete = false),
        GeminiWebFixtures.frame("Hello 世界! 完成"),
    )

    @Test
    fun `decodes every length framed record in one chunk`() {
        val decoder = GeminiWebFrameDecoder()
        val decoded = decoder.push(GeminiWebFixtures.body(frames).toByteArray())
        assertEquals(frames, decoded)
        assertEquals(emptyList<String>(), decoder.finish())
    }

    @Test
    fun `same frames when the body is split at every byte boundary`() {
        val bytes = GeminiWebFixtures.body(frames).toByteArray()
        for (cut in 1 until bytes.size) {
            val decoder = GeminiWebFrameDecoder()
            val decoded = mutableListOf<String>()
            decoded += decoder.push(bytes.copyOfRange(0, cut))
            decoded += decoder.push(bytes.copyOfRange(cut, bytes.size))
            decoded += decoder.finish()
            assertEquals("cut at $cut", frames, decoded)
        }
    }

    @Test
    fun `same frames when delivered one byte at a time`() {
        val decoder = GeminiWebFrameDecoder()
        val decoded = mutableListOf<String>()
        GeminiWebFixtures.body(frames).toByteArray().forEach { byte ->
            decoded += decoder.push(byteArrayOf(byte))
        }
        decoded += decoder.finish()
        assertEquals(frames, decoded)
    }

    @Test
    fun `multi byte utf8 split across chunks is not mangled`() {
        val bytes = GeminiWebFixtures.body(listOf(GeminiWebFixtures.frame("你好，世界"))).toByteArray()
        val decoder = GeminiWebFrameDecoder()
        val decoded = mutableListOf<String>()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 3, bytes.size)
            decoded += decoder.push(bytes.copyOfRange(offset, end))
            offset = end
        }
        decoded += decoder.finish()
        assertEquals(listOf(GeminiWebFixtures.frame("你好，世界")), decoded)
        assertTrue(GeminiWebFrameParser.parse(decoded.first()).text == "你好，世界")
    }

    @Test
    fun `accepts a body whose prefix counts characters instead of bytes`() {
        val utf8Frames = listOf(
            GeminiWebFixtures.frame("中文快照一", complete = false),
            GeminiWebFixtures.frame("中文快照一二三"),
        )
        val decoder = GeminiWebFrameDecoder()
        val decoded = decoder.push(GeminiWebFixtures.charFramedBody(utf8Frames).toByteArray())
        assertEquals(utf8Frames, decoded)
    }

    @Test
    fun `truncated body is rejected instead of yielding half a frame`() {
        val body = GeminiWebFixtures.body(frames).dropLast(40)
        val decoder = GeminiWebFrameDecoder()
        val decoded = decoder.push(body.toByteArray())
        assertTrue(decoded.size < frames.size)
        try {
            decoder.finish()
            fail("expected WebProtocolException")
        } catch (e: WebProtocolException) {
            assertTrue(e.message!!.contains("帧中途结束"))
        }
    }

    @Test
    fun `clean frame boundary at eof is not treated as completion by the decoder`() {
        val decoder = GeminiWebFrameDecoder()
        assertEquals(frames.size, decoder.push(GeminiWebFixtures.body(frames).toByteArray()).size)
        assertEquals(emptyList<String>(), decoder.finish())
    }

    @Test
    fun `html challenge page is a non retryable session failure without echoing the page`() {
        val decoder = GeminiWebFrameDecoder()
        try {
            decoder.push("<html><body>accounts.google.com consent secret-cookie</body></html>".toByteArray())
            fail("expected WebSessionException")
        } catch (e: WebSessionException) {
            assertTrue(e.message!!.contains("重新登录"))
            assertTrue(!e.message!!.contains("secret-cookie"))
        }
    }

    @Test
    fun `declared frame larger than one megabyte is rejected`() {
        val decoder = GeminiWebFrameDecoder()
        try {
            decoder.push(")]}'\n${GEMINI_WEB_MAX_FRAME_BYTES + 1}\n[".toByteArray())
            fail("expected WebProtocolException")
        } catch (e: WebProtocolException) {
            assertTrue(e.message!!.contains("1MB"))
        }
    }

    @Test
    fun `unframed json line is still parsed`() {
        val decoder = GeminiWebFrameDecoder()
        val decoded = decoder.push((")]}'\n" + frames[0] + "\n").toByteArray())
        assertEquals(listOf(frames[0]), decoded)
    }

    @Test
    fun `last frame without trailing newline is accepted at finish`() {
        val decoder = GeminiWebFrameDecoder()
        decoder.push(")]}'\n".toByteArray())
        assertEquals(emptyList<String>(), decoder.finish())
    }
}
