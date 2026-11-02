package me.rerere.ai.provider.providers.geminiweb

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionCredential
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.WebSessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * End-to-end protocol behaviour of [GeminiWebChatSession] over a stub transport: framing is real (the same
 * byte decoder that production uses), only the socket is replaced.
 */
class GeminiWebChatSessionTest {

    private val credential = WebSessionCredential(WebSessionService.GEMINI, mapOf("SID" to "sanitized"))
    private val bootstrap = GeminiWebBootstrap("csrf-token", "build-label", "-1")

    private class StubTransport(
        private val frames: List<String> = emptyList(),
        private val failure: Throwable? = null,
        private val truncateBody: Boolean = false,
        private val chunkSize: Int = 9,
    ) : GeminiWebTransport {
        var bootstrapCalls = 0
        var streamCalls = 0
        var lastPrompt: String? = null

        override suspend fun bootstrap(credential: WebSessionCredential): GeminiWebBootstrap {
            bootstrapCalls++
            failure?.let { throw it }
            return GeminiWebBootstrap("csrf-token", "build-label", "-1")
        }

        override suspend fun streamGenerate(
            credential: WebSessionCredential,
            bootstrap: GeminiWebBootstrap,
            prompt: String,
            requestId: Int,
            onFrame: suspend (GeminiWebFrame) -> Unit,
        ) {
            streamCalls++
            lastPrompt = prompt
            failure?.let { throw it }
            var body = GeminiWebFixtures.body(frames)
            if (truncateBody) body = body.dropLast(25)
            val decoder = GeminiWebFrameDecoder()
            val bytes = body.toByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + chunkSize, bytes.size)
                decoder.push(bytes.copyOfRange(offset, end)).forEach {
                    onFrame(GeminiWebFrameParser.parse(it))
                }
                offset = end
            }
            decoder.finish().forEach { onFrame(GeminiWebFrameParser.parse(it)) }
        }
    }

    @Test
    fun `cumulative frames become ordered deltas`() = runTest {
        val transport = StubTransport(
            frames = listOf(
                GeminiWebFixtures.frame("Hel", complete = false),
                GeminiWebFixtures.frame("Hello", complete = false),
                GeminiWebFixtures.frame("Hello 世界", responseId = "rc_final"),
            )
        )
        val updates = GeminiWebChatSession(transport, credential, requestIdFactory = { 42 })
            .stream("完整本地上下文").toList()

        assertEquals(3, updates.size)
        assertEquals("Hello 世界", updates.joinToString("") { it.delta })
        assertEquals("rc_final", updates.last().responseId)
        assertTrue(updates.first().isFirstDelta)
        assertEquals("完整本地上下文", transport.lastPrompt)
        assertEquals(1, transport.bootstrapCalls)
        assertEquals(1, transport.streamCalls)
    }

    @Test
    fun `auth rejection is a session error and is never retried`() = runTest {
        val transport = StubTransport(failure = WebSessionException("rejected"))
        try {
            GeminiWebChatSession(transport, credential).completeText("prompt")
            fail("expected WebSessionException")
        } catch (e: WebSessionException) {
            assertFalse(IOException::class.java.isInstance(e))
        }
        assertEquals(1, transport.bootstrapCalls)
        assertEquals(0, transport.streamCalls)
    }

    @Test
    fun `http 403 after bootstrap does not retry either`() = runTest {
        val transport = object : GeminiWebTransport {
            var streamCalls = 0
            override suspend fun bootstrap(credential: WebSessionCredential) =
                GeminiWebBootstrap("csrf", "bl", null)

            override suspend fun streamGenerate(
                credential: WebSessionCredential,
                bootstrap: GeminiWebBootstrap,
                prompt: String,
                requestId: Int,
                onFrame: suspend (GeminiWebFrame) -> Unit,
            ) {
                streamCalls++
                throw WebSessionException(GEMINI_WEB_RELOGIN_MESSAGE)
            }
        }
        try {
            GeminiWebChatSession(transport, credential).completeText("prompt")
            fail("expected WebSessionException")
        } catch (e: WebSessionException) {
            assertTrue(e.message!!.contains("重新登录"))
        }
        assertEquals(1, transport.streamCalls)
    }

    @Test
    fun `half streamed body fails instead of returning a partial answer`() = runTest {
        val transport = StubTransport(
            frames = listOf(
                GeminiWebFixtures.frame("Hello", complete = false),
                GeminiWebFixtures.frame("Hello world"),
            ),
            truncateBody = true,
        )
        try {
            GeminiWebChatSession(transport, credential).completeText("prompt")
            fail("expected WebProtocolException")
        } catch (e: WebProtocolException) {
            assertTrue(e.message!!.contains("帧中途结束"))
        }
    }

    @Test
    fun `empty response is a protocol failure`() = runTest {
        val transport = StubTransport(frames = emptyList())
        try {
            GeminiWebChatSession(transport, credential).completeText("prompt")
            fail("expected WebProtocolException")
        } catch (e: WebProtocolException) {
            assertTrue(e.message!!.contains("终态"))
        }
    }

    @Test
    fun `clean eof at a frame boundary without terminal marker fails`() = runTest {
        val transport = StubTransport(
            frames = listOf(
                GeminiWebFixtures.frame("answer so far", complete = false),
                GeminiWebFixtures.frame("answer so far, still running", complete = false),
            )
        )
        try {
            GeminiWebChatSession(transport, credential).completeText("prompt")
            fail("expected WebProtocolException")
        } catch (e: WebProtocolException) {
            assertTrue(e.message!!.contains("终态"))
        }
    }

    @Test
    fun `history stored frame is not a completion`() = runTest {
        val transport = StubTransport(
            frames = listOf(
                GeminiWebFixtures.frame("saved but not finished", complete = false, historyStored = true)
            )
        )
        try {
            GeminiWebChatSession(transport, credential).completeText("prompt")
            fail("expected WebProtocolException")
        } catch (e: WebProtocolException) {
            assertTrue(e.message!!.contains("终态"))
        }
    }

    @Test
    fun `revised snapshot returns the latest text not a concatenation`() = runTest {
        val transport = StubTransport(
            frames = listOf(
                GeminiWebFixtures.frame("Hello world", complete = false),
                GeminiWebFixtures.frame("Hi there"),
            )
        )
        val (text, _) = GeminiWebChatSession(transport, credential).completeText("prompt")
        assertEquals("Hi there", text)
    }

    @Test
    fun `a second candidate rcid is ignored instead of mixing text`() = runTest {
        val transport = StubTransport(
            frames = listOf(
                GeminiWebFixtures.frame("first answer", rcid = "rcid_a"),
                GeminiWebFixtures.frame("second answer", rcid = "rcid_b", complete = false),
            )
        )
        val (text, _) = GeminiWebChatSession(transport, credential).completeText("prompt")
        assertEquals("first answer", text)
    }

    @Test
    fun `completeText keeps the full answer and response id`() = runTest {
        val transport = StubTransport(
            frames = listOf(
                GeminiWebFixtures.frame("第一段", complete = false),
                GeminiWebFixtures.frame("第一段，第二段", responseId = "rc_9"),
            )
        )
        val (text, responseId) = GeminiWebChatSession(transport, credential).completeText("prompt")
        assertEquals("第一段，第二段", text)
        assertEquals("rc_9", responseId)
    }

    @Test
    fun `protocol failure is an IOException so it is not mistaken for a session rejection`() {
        assertTrue(WebProtocolException("x") is IOException)
        assertFalse(IOException::class.java.isInstance(WebSessionException("x")))
    }

    @Test
    fun `bootstrap values are used for the rpc`() {
        val url = GeminiWebRequestBuilder.streamUrl(GEMINI_WEB_ORIGIN, bootstrap, 100_500)
        assertTrue(url.startsWith("$GEMINI_WEB_ORIGIN/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate?bl=build-label"))
        assertTrue(url.contains("f.sid=-1"))
        assertTrue(url.contains("_reqid=100500"))
        assertTrue(!url.contains("csrf-token"))
    }
}
