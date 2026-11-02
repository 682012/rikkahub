package me.rerere.ai.provider.providers.claudeweb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.web.WebChatExchangeException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 端到端：真实 URL/请求体/状态码分类/SSE 解析，只把 socket 换成拦截器。 */
class ClaudeWebProviderTest {

    private fun generate(
        provider: ClaudeWebProvider,
        setting: ProviderSetting.ClaudeWeb = testSetting(),
        messages: List<UIMessage> = listOf(UIMessage.user("你好")),
        params: me.rerere.ai.provider.TextGenerationParams = testParams(),
    ) = runBlocking { provider.generateText(setting, messages, params) }

    @Test
    fun `single organization produces one complete answer`() {
        val backend = ClaudeWebFakeBackend()
        val provider = testProvider(backend)

        val result = generate(provider)

        assertEquals("你好，世界", result.message.toText())
        assertEquals("msg_sanitized", result.id)
        assertEquals(CLAUDE_WEB_DEFAULT_MODEL_ID, result.model)
        assertEquals(3, backend.requests.size)
        assertEquals("/api/organizations", backend.requests[0].url.encodedPath)
        assertEquals("claude.ai", backend.requests[0].url.host)
    }

    @Test
    fun `streaming is buffered and still ends with a finish chunk`() {
        val backend = ClaudeWebFakeBackend()
        val provider = testProvider(backend)

        val chunks = runBlocking {
            provider.streamText(testSetting(), listOf(UIMessage.user("你好")), testParams()).toList()
        }

        assertEquals(
            listOf("TextStart", "TextDelta", "TextEnd", "Finish"),
            chunks.map { it::class.simpleName },
        )
        assertEquals("你好，世界", (chunks[1] as StreamChunk.TextDelta).text)
        assertEquals("msg_sanitized", (chunks.last() as StreamChunk.Finish).responseId)
    }

    @Test
    fun `completion request body and headers match the web shape without native tools`() {
        val backend = ClaudeWebFakeBackend()
        generate(testProvider(backend))

        val completion = backend.completionRequests().single()
        assertEquals("https", completion.url.scheme)
        assertEquals("claude.ai", completion.url.host)
        val segments = completion.url.pathSegments
        assertEquals(
            listOf("api", "organizations", ORG_A, "chat_conversations"),
            segments.subList(0, 4),
        )
        assertEquals("completion", segments.last())
        assertEquals(6, segments.size)

        val buffer = okio.Buffer()
        completion.body!!.writeTo(buffer)
        val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(
            setOf("prompt", "parent_message_uuid", "rendering_mode", "locale", "timezone", "sync_sources", "tools", "attachments", "files"),
            body.keys,
        )
        assertEquals(CLAUDE_WEB_PARENT_MESSAGE_UUID, body["parent_message_uuid"]!!.jsonPrimitive.content)
        assertEquals("messages", body["rendering_mode"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), body["tools"])
        assertEquals(JsonArray(emptyList()), body["attachments"])
        assertEquals(JsonArray(emptyList()), body["files"])
        assertTrue(body["prompt"]!!.jsonPrimitive.content.contains("你好"))

        // 诚实的客户端身份 + 只带我们自己的 sessionKey。
        val userAgent = completion.header("User-Agent")!!
        assertEquals(CLAUDE_WEB_USER_AGENT, userAgent)
        assertFalse(userAgent.contains("Chrome"))
        assertFalse(userAgent.contains("ClaudeCode"))
        val cookie = completion.header("Cookie")!!
        assertTrue(cookie.contains("sessionKey=sk-ant-sid01-sanitized"))
        assertFalse(cookie.contains("cf_clearance"))
        assertTrue(completion.header("Referer")!!.startsWith("$CLAUDE_WEB_ORIGIN/chat/"))
    }

    @Test
    fun `conversation is created with a fresh uuid for every request`() {
        val backend = ClaudeWebFakeBackend()
        val provider = testProvider(backend)

        generate(provider)
        generate(provider)

        val uuids = backend.createRequests().map { backend.requestedConversationUuid(it) }
        assertEquals(2, uuids.size)
        assertNotEquals(uuids[0], uuids[1])
        uuids.forEach { requireClaudeWebUuid(it, "conversation") }
        backend.completionRequests().forEachIndexed { index, request ->
            assertTrue(request.url.pathSegments.contains(uuids[index]))
        }
    }

    @Test
    fun `multiple organizations without a personal marker ask the user to choose`() {
        val backend = ClaudeWebFakeBackend(
            organizationsBody = """[{"uuid":"$ORG_A"},{"uuid":"$ORG_B"}]""",
        )

        val error = assertThrows(WebSessionException::class.java) { generate(testProvider(backend)) }

        assertTrue(error.message!!.contains("organizationId"))
        // 只在组织列表停住：没有建会话、没有 completion。
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun `a single personal organization is selected among several`() {
        val backend = ClaudeWebFakeBackend(
            organizationsBody = """[{"uuid":"$ORG_A"},{"uuid":"$ORG_B","is_personal":true}]""",
        )

        generate(testProvider(backend))

        assertTrue(backend.completionRequests().single().url.pathSegments.contains(ORG_B))
    }

    @Test
    fun `explicit organizationId is honoured`() {
        val backend = ClaudeWebFakeBackend(
            organizationsBody = """[{"uuid":"$ORG_A","is_personal":true},{"uuid":"$ORG_B"}]""",
        )

        generate(testProvider(backend), setting = testSetting(organizationId = ORG_B))

        assertTrue(backend.completionRequests().single().url.pathSegments.contains(ORG_B))
    }

    @Test
    fun `explicit organizationId outside the account is rejected, never swapped`() {
        val backend = ClaudeWebFakeBackend()

        val error = assertThrows(WebSessionException::class.java) {
            generate(testProvider(backend), setting = testSetting(organizationId = ORG_NOT_LISTED))
        }

        assertTrue(error.message!!.contains("organizationId"))
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun `http 403 is a session error and is not retried`() {
        val backend = ClaudeWebFakeBackend(
            organizationsCode = 403,
            organizationsBody = """{"error":"just a moment" }""",
        )

        val error = assertThrows(WebSessionException::class.java) { generate(testProvider(backend)) }

        // 会话失效不是 IO 错误，任何重试层都不会把它当成"网络抖动"再打一次。
        assertFalse(IOException::class.java.isInstance(error))
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun `http 200 html challenge is a session error, not empty model output`() {
        val backend = ClaudeWebFakeBackend(
            organizationsBody = "<html><body>captcha</body></html>",
            organizationsContentType = "text/html",
        )

        val error = assertThrows(WebSessionException::class.java) { generate(testProvider(backend)) }
        assertFalse(IOException::class.java.isInstance(error))
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun `server supplied non uuid organization id never reaches the request path`() {
        val backend = ClaudeWebFakeBackend(
            organizationsBody = """[{"uuid":"../../api/other"}]""",
        )

        assertThrows(IOException::class.java) { generate(testProvider(backend)) }

        // 组织列表之后就被拒绝，没有用注入值去拼任何路径。
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun `unknown model id is rejected instead of being replaced by the web default`() {
        val backend = ClaudeWebFakeBackend()

        val error = assertThrows(IllegalArgumentException::class.java) {
            generate(testProvider(backend), params = testParams("claude-sonnet-4-5"))
        }

        assertTrue(error.message!!.contains(CLAUDE_WEB_DEFAULT_MODEL_ID))
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun `attachments are rejected before any request is sent`() {
        val backend = ClaudeWebFakeBackend()
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("看图"), UIMessagePart.Image("data:image/png;base64,AAAA")),
        )

        assertThrows(WebChatExchangeException.UnsupportedContent::class.java) {
            generate(testProvider(backend), messages = listOf(message))
        }

        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun `listModels validates the session and exposes the single web model`() {
        val backend = ClaudeWebFakeBackend()
        val provider = testProvider(backend)

        val models = runBlocking { provider.listModels(testSetting()) }

        assertEquals(1, models.size)
        assertEquals(CLAUDE_WEB_DEFAULT_MODEL_ID, models.single().modelId)
        assertEquals(32_768, models.single().contextLength)
        assertTrue(models.single().abilities.isEmpty())
        assertEquals(1, backend.requests.size)
        assertEquals("/api/organizations", backend.requests.single().url.encodedPath)

        val toolModels = runBlocking { provider.listModels(testSetting(toolBridgeEnabled = true)) }
        assertTrue(toolModels.single().abilities.contains(ModelAbility.TOOL))
    }

    @Test
    fun `listModels surfaces an expired session instead of inventing models`() {
        val backend = ClaudeWebFakeBackend(organizationsCode = 401, organizationsBody = "{}")

        assertThrows(WebSessionException::class.java) {
            runBlocking { testProvider(backend).listModels(testSetting()) }
        }
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun `truncated stream is reported as IO failure, never as a finished answer`() {
        val backend = ClaudeWebFakeBackend(
            completionBody = ClaudeWebFixtures.textStream("半截", terminal = false),
        )

        assertThrows(IOException::class.java) { generate(testProvider(backend)) }
    }

    @Test
    fun `error event is reported without echoing the server body`() {
        val backend = ClaudeWebFakeBackend(
            completionBody = ClaudeWebFixtures.event(
                "error",
                """{"type":"error","error":{"type":"overloaded_error","message":"SECRET_SERVER_TEXT"}}""",
            ),
        )

        val error = assertThrows(Exception::class.java) { generate(testProvider(backend)) }
        assertFalse(error.message.orEmpty().contains("SECRET_SERVER_TEXT"))
    }

    @Test
    fun `legacy completion stream is supported when it carries a terminal stop reason`() {
        val backend = ClaudeWebFakeBackend(
            completionBody = ClaudeWebFixtures.event(
                "completion",
                """{"completion":"旧格式回答","stop_reason":"stop_sequence"}""",
            ),
        )

        assertEquals("旧格式回答", generate(testProvider(backend)).message.toText())
    }

    @Test
    fun `cancelling the coroutine cancels the in-flight http call`() {
        val started = CountDownLatch(1)
        val calls = mutableListOf<Call>()
        val blockingBackend = okhttp3.Interceptor { chain ->
            calls += chain.call()
            started.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(2)
            throw IOException("canceled")
        }
        val client = OkHttpClient.Builder().addInterceptor(blockingBackend).build()
        val provider = ClaudeWebProvider(ClaudeWebFakeVault(), OkHttpClaudeWebTransport(client))

        runBlocking {
            val job = launch(Dispatchers.IO) {
                runCatching { provider.generateText(testSetting(), listOf(UIMessage.user("hi")), testParams()) }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            job.cancel()
            job.join()
        }

        assertTrue(calls.single().isCanceled())
    }

    @Test
    fun `default model id constant is exposed to settings`() {
        // 设置里只允许这一个 id；它不是新的服务端模型名，只是"网页默认"的占位。
        assertEquals("claude-web-default", CLAUDE_WEB_DEFAULT_MODEL_ID)
        assertEquals(Model(modelId = CLAUDE_WEB_DEFAULT_MODEL_ID).modelId, CLAUDE_WEB_DEFAULT_MODEL_ID)
    }
}
