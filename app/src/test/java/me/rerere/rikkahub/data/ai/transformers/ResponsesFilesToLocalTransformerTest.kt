package me.rerere.rikkahub.data.ai.transformers

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ResponsesFileOutputMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.responsesContainerFileReference
import me.rerere.ai.ui.responsesStoreFileReference
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Responses 文件引用的落地行为（只测 app 侧：取内容、校验、落盘、改写部件）。
 *
 * 用真实的 loopback 服务器而不是假 HTTP client：鉴权头、重定向、大小上限、取消这些必须在真实的
 * OkHttp 请求路径上验证，否则测试等于没测。
 *
 * 覆盖点：
 * - 地址严格按 `{baseUrl}/containers/{cid}/files/{fid}/content` 拼，id 按路径段编码，密钥只在请求头；
 * - 重定向不跟随（凭据不会被带到别处）；
 * - 超过上限的响应整份丢弃、不落盘；空响应算失败；
 * - 缺 file_id / container_id 的引用不发请求、不伪造成功；
 * - 历史消息（modelId 与当前模型不一致）不取；
 * - 失败只标在这个部件上，正文与注解照常可读，标记里没有密钥；
 * - 原封装盘（不重编码），MIME 按真实字节嗅探，不信任文件名；
 * - 取消向上抛，不变成“失败部件”。
 */
class ResponsesFilesToLocalTransformerTest {

    /** 只会回固定响应的最小 HTTP/1.1 服务器，用于观察真实请求。 */
    private class FakeServer : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        private val responses = Collections.synchronizedList(mutableListOf<Reply>())

        data class Reply(
            val status: Int,
            val headers: List<Pair<String, String>> = emptyList(),
            val body: ByteArray = ByteArray(0),
            val delayMs: Long = 0,
        )

        val url: String get() = "http://127.0.0.1:${socket.localPort}/v1"

        @Volatile
        private var closed = false

        init {
            thread(isDaemon = true, name = "fake-responses-server") {
                while (!closed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { handle(client) }
                }
            }
        }

        fun enqueue(reply: Reply) {
            responses += reply
        }

        fun takeLines(): List<String> = requests.firstOrNull()?.lines().orEmpty()

        private fun handle(client: Socket) = client.use { conn ->
            val input = conn.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            val headers = mutableListOf<String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                headers += line
            }
            requests += (listOf(requestLine) + headers).joinToString("\n")
            val reply = responses.removeFirstOrNull() ?: Reply(500)
            if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
            val head = buildString {
                append("HTTP/1.1 ${reply.status} X\r\n")
                reply.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Content-Length: ${reply.body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            runCatching {
                conn.getOutputStream().apply {
                    write(head.toByteArray())
                    write(reply.body)
                    flush()
                }
            }
        }

        private fun readLine(input: java.io.InputStream): String? {
            val buffer = StringBuilder()
            while (true) {
                val read = input.read()
                if (read < 0) return null
                if (read == '\n'.code) return buffer.toString().trimEnd('\r')
                buffer.append(read.toChar())
            }
        }

        override fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    private lateinit var server: FakeServer
    private lateinit var client: OkHttpClient
    private lateinit var filesDir: File
    private lateinit var manager: FilesManager

    @Before
    fun setUp() {
        server = FakeServer()
        client = responsesFileHttpClient(OkHttpClient())
        filesDir = Files.createTempDirectory("responses-files").toFile()
        manager = FakeFilesManager(filesDir)
    }

    @After
    fun tearDown() {
        server.close()
        filesDir.deleteRecursively()
    }

    private fun policy(
        baseUrl: String = server.url,
        apiKey: String = "sk-test",
    ): ResponsesFileFetchPolicy? = responsesFileFetchPolicy(
        ProviderSetting.OpenAI(apiKey = apiKey, baseUrl = baseUrl, useResponseApi = true),
    )

    private fun storeOnlyPolicy(): ResponsesFileFetchPolicy = ResponsesFileFetchPolicy(
        containerContentUrl = null,
        storeContentUrl = { fileId -> "${server.url}/files/$fileId/content" },
        authorization = null,
        extraHeaders = Headers.headersOf(),
    )

    private fun document(
        url: String,
        fileName: String = "chart.png",
        mime: String = "image/png",
    ) = UIMessagePart.Document(
        url = url,
        fileName = fileName,
        mime = mime,
        metadata = ResponsesFileOutputMetadata(
            protocol = ResponsesFileOutputMetadata.PROTOCOL,
            kind = ResponsesFileOutputMetadata.KIND_CONTAINER_FILE,
            containerId = "cntr_1",
            fileId = "cfile_1",
            filename = fileName,
            annotationStartIndex = 11,
            annotationEndIndex = 30,
            source = "code_interpreter",
        ).toMetadata(),
    )

    private fun pngBytes(): ByteArray {
        val header = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
        )
        return header + ByteArray(64) { it.toByte() }
    }

    private fun ok(body: ByteArray) = FakeServer.Reply(200, body = body)

    private fun storedFiles(): List<String> = filesDir.list()?.toList().orEmpty()

    /* ------------------------------------------------------------------ 地址与鉴权 */

    @Test
    fun `container reference resolves against the configured base url with a bearer header`() = runTest {
        server.enqueue(ok(pngBytes()))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)

        val outcome = resolver.resolvePart(document(responsesContainerFileReference("cntr_1", "cfile_1")))

        val lines = server.takeLines()
        assertEquals("GET /v1/containers/cntr_1/files/cfile_1/content HTTP/1.1", lines.firstOrNull())
        assertTrue(lines.any { it.equals("Authorization: Bearer sk-test", ignoreCase = true) })
        // 密钥绝不能出现在 URL 里
        assertFalse(lines.first().contains("sk-test"))
        assertTrue(outcome is ResponsesFileOutcome.Stored)
        assertEquals("image/png", (outcome as ResponsesFileOutcome.Stored).mime)
    }

    @Test
    fun `path segments are percent encoded so ids cannot escape the path`() {
        assertEquals("..%2Fa%3Fb%23c%2Fd", "../a?b#c/d".encodePathSegment())
        assertEquals("cntr_68a1", "cntr_68a1".encodePathSegment())
        assertEquals("a%20b", "a b".encodePathSegment())
        assertEquals("caf%C3%A9", "café".encodePathSegment())
    }

    @Test
    fun `store reference uses the files endpoint and the real mime wins over the file name`() = runTest {
        server.enqueue(ok("name,value\na,1\n".encodeToByteArray()))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)
        val part = UIMessagePart.Document(
            url = responsesStoreFileReference("file-abc123"),
            fileName = "annual-report.pdf",
            mime = "application/pdf",
        )

        val outcome = resolver.resolvePart(part)

        assertEquals("GET /v1/files/file-abc123/content HTTP/1.1", server.takeLines().firstOrNull())
        assertEquals("text/plain", (outcome as ResponsesFileOutcome.Stored).mime)
    }

    @Test
    fun `non responses providers never fetch`() {
        assertNull(
            responsesFileFetchPolicy(
                ProviderSetting.Claude(apiKey = "k", baseUrl = "https://api.anthropic.com/v1"),
            ),
        )
        assertNull(
            responsesFileFetchPolicy(ProviderSetting.OpenAI(apiKey = "k", baseUrl = server.url, useResponseApi = false)),
        )
        assertNull(
            responsesFileFetchPolicy(
                ProviderSetting.OpenAI(apiKey = "k", baseUrl = "file:///tmp", useResponseApi = true),
            ),
        )
        assertNull(responsesFileFetchPolicy(null))
    }

    @Test
    fun `missing api key sends no authorization header`() = runTest {
        server.enqueue(ok(pngBytes()))
        val resolver = ResponsesFileResolver(policy = policy(apiKey = ""), filesManager = manager, client = client)

        resolver.resolvePart(document(responsesContainerFileReference("cntr_1", "cfile_1")))

        assertTrue(server.takeLines().none { it.startsWith("Authorization:", ignoreCase = true) })
    }

    @Test
    fun `custom provider headers are forwarded but no session header is added`() {
        val custom = responsesFileFetchPolicy(
            ProviderSetting.OpenAI(
                apiKey = "k",
                baseUrl = server.url,
                useResponseApi = true,
                customHeaders = listOf(CustomHeader("X-Custom", "1")),
            ),
        )
        assertEquals("1", custom?.extraHeaders?.get("X-Custom"))
        assertNull(custom?.extraHeaders?.get("X-Session-ID"))
    }

    /* ------------------------------------------------------------------ 凭据边界 */

    @Test
    fun `redirect is not followed so credentials never leave the origin`() = runTest {
        server.enqueue(FakeServer.Reply(302, headers = listOf("Location" to "http://127.0.0.1:1/steal")))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)

        val outcome = resolver.resolvePart(document(responsesContainerFileReference("cntr_1", "cfile_1")))

        assertEquals(ResponsesFileOutcome.Failed(ResponsesFileFailure.REDIRECTED), outcome)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `dedicated client never follows redirects or retries`() {
        val dedicated = responsesFileHttpClient(OkHttpClient.Builder().followRedirects(true).build())
        assertFalse(dedicated.followRedirects)
        assertFalse(dedicated.followSslRedirects)
        assertFalse(dedicated.retryOnConnectionFailure)
    }

    @Test
    fun `stored part keeps reference metadata but no credentials`() = runTest {
        server.enqueue(ok(pngBytes()))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)
        val part = document(responsesContainerFileReference("cntr_1", "cfile_1"))

        val outcome = resolver.resolvePart(part) as ResponsesFileOutcome.Stored
        val local = part.toLocalPart(outcome.localUrl, outcome.mime)

        assertTrue(local is UIMessagePart.Image)
        val text = local.metadata.toString()
        assertFalse(text.contains("sk-test"))
        assertFalse(text.contains("Bearer"))
        val meta = (local as UIMessagePart.Image).metadataAs<ResponsesFileOutputMetadata>()
        assertEquals("cfile_1", meta?.fileId)
        assertEquals("cntr_1", meta?.containerId)
        assertEquals(11, meta?.annotationStartIndex)
    }

    /* ------------------------------------------------------------------ 边界与失败 */

    @Test
    fun `oversized body is rejected without storing anything`() = runTest {
        server.enqueue(ok("0123456789".encodeToByteArray()))
        val resolver = ResponsesFileResolver(
            policy = policy(),
            filesManager = manager,
            client = client,
            maxBytes = 4,
        )

        val outcome = resolver.resolvePart(document(responsesContainerFileReference("cntr_1", "cfile_1")))

        assertEquals(ResponsesFileOutcome.Failed(ResponsesFileFailure.TOO_LARGE), outcome)
        assertEquals(emptyList<String>(), storedFiles())
    }

    @Test
    fun `empty body is a failure not an empty file`() = runTest {
        server.enqueue(ok(ByteArray(0)))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)

        val outcome = resolver.resolvePart(document(responsesContainerFileReference("cntr_1", "cfile_1")))

        assertEquals(ResponsesFileOutcome.Failed(ResponsesFileFailure.EMPTY), outcome)
        assertEquals(emptyList<String>(), storedFiles())
    }

    @Test
    fun `http error becomes a stable code without dumping the body`() = runTest {
        server.enqueue(FakeServer.Reply(404, body = "{\"error\":\"not found\"}".encodeToByteArray()))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)

        val outcome = resolver.resolvePart(document(responsesContainerFileReference("cntr_1", "cfile_1")))

        assertEquals(ResponsesFileOutcome.Failed(ResponsesFileFailure.HTTP_404), outcome)
    }

    @Test
    fun `http status codes map to coarse buckets`() {
        assertEquals(ResponsesFileFailure.HTTP_401, ResponsesFileFailure.httpStatus(401))
        assertEquals(ResponsesFileFailure.HTTP_403, ResponsesFileFailure.httpStatus(403))
        assertEquals(ResponsesFileFailure.HTTP_404, ResponsesFileFailure.httpStatus(404))
        assertEquals(ResponsesFileFailure.HTTP_4XX, ResponsesFileFailure.httpStatus(429))
        assertEquals(ResponsesFileFailure.HTTP_5XX, ResponsesFileFailure.httpStatus(503))
        assertEquals(ResponsesFileFailure.HTTP_OTHER, ResponsesFileFailure.httpStatus(200))
    }

    @Test
    fun `a dead link without container id never issues a request and never fakes success`() = runTest {
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)
        // 真实 Responses 探活拿到的 dead link 形态：只有 file_id、没有 container_id
        val deadLink = document("rkresponses-file://container?file_id=cfile_1")

        val outcome = resolver.resolvePart(deadLink)

        assertEquals(ResponsesFileOutcome.Untouched, outcome)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun `policy without a container endpoint fails instead of guessing a url`() = runTest {
        val resolver = ResponsesFileResolver(policy = storeOnlyPolicy(), filesManager = manager, client = client)

        val outcome = resolver.resolvePart(document(responsesContainerFileReference("cntr_1", "cfile_1")))

        assertEquals(ResponsesFileOutcome.Failed(ResponsesFileFailure.NO_ENDPOINT), outcome)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun `foreign urls are untouched`() = runTest {
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)
        listOf(
            "https://example.com/a.png",
            "sandbox:/mnt/data/chart.png",
            "file:///data/user/0/x/files/upload/a.png",
            "data:image/png;base64,AAAA",
        ).forEach { url ->
            assertEquals(url, ResponsesFileOutcome.Untouched, resolver.resolvePart(document(url)))
        }
        assertEquals(0, server.requests.size)
    }

    @Test
    fun `missing files manager fails the part instead of pretending success`() = runTest {
        server.enqueue(ok(pngBytes()))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = null, client = client)

        val outcome = resolver.resolvePart(document(responsesContainerFileReference("cntr_1", "cfile_1")))

        assertEquals(ResponsesFileOutcome.Failed(ResponsesFileFailure.SAVE_FAILED), outcome)
        assertEquals(emptyList<String>(), storedFiles())
    }

    @Test
    fun `cancellation propagates instead of being turned into a failed part`() = runTest {
        // 服务器拖着不返回：取消必须抛 CancellationException，而不是打上失败标记
        server.enqueue(FakeServer.Reply(200, body = "x".encodeToByteArray(), delayMs = 20_000))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)

        var cancelled = false
        val job = launch {
            try {
                resolver.resolvePart(document(responsesContainerFileReference("cntr_1", "cfile_1")))
            } catch (e: CancellationException) {
                cancelled = true
            }
        }
        // 等请求真正发出后再取消，确保打断的是响应读取
        withTimeout(10_000) {
            while (server.requests.isEmpty()) delay(5)
        }
        job.cancel()
        job.join()

        assertTrue(cancelled)
        assertEquals(emptyList<String>(), storedFiles())
    }

    /* ------------------------------------------------------------------ 身份快照 / 只看本轮 */

    @Test
    fun `historical message from another model is never fetched`() = runTest {
        val resolver = ResponsesFileResolver(
            policy = policy(),
            currentModelId = Uuid.random(),
            filesManager = manager,
            client = client,
        )
        val historical = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(document(responsesContainerFileReference("cntr_1", "cfile_1"))),
            modelId = Uuid.random(), // 另一个模型生成的
        )

        val result = resolver.resolveFinished(listOf(historical))

        assertEquals(0, server.requests.size)
        val part = result.single().parts.single() as UIMessagePart.Document
        assertEquals("provider_mismatch", part.responsesFileFailure())
        // 引用原样保留，仍然指向同一个文件
        assertTrue(part.url.startsWith("rkresponses-file://"))
    }

    @Test
    fun `current model message is resolved and keeps text and annotations`() = runTest {
        server.enqueue(ok(pngBytes()))
        val model = Uuid.random()
        val resolver = ResponsesFileResolver(
            policy = policy(),
            currentModelId = model,
            filesManager = manager,
            client = client,
        )
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("draw a chart")))
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("here: sandbox:/mnt/data/chart.png"),
                document(responsesContainerFileReference("cntr_1", "cfile_1")),
            ),
            annotations = listOf(
                UIMessageAnnotation.ContainerFileCitation(
                    containerId = "cntr_1",
                    fileId = "cfile_1",
                    title = "chart.png",
                    startIndex = 6,
                    endIndex = 34,
                ),
            ),
            modelId = model,
        )

        val result = resolver.resolveFinished(listOf(user, assistant))

        val updated = result.last()
        assertEquals(
            listOf("here: sandbox:/mnt/data/chart.png"),
            updated.parts.filterIsInstance<UIMessagePart.Text>().map { it.text },
        )
        assertTrue(updated.parts[1] is UIMessagePart.Image)
        assertEquals(1, updated.annotations.size)
        assertEquals(user, result.first())
    }

    @Test
    fun `only new parts are processed when a step bound is given`() = runTest {
        val model = Uuid.random()
        val resolver = ResponsesFileResolver(
            policy = policy(),
            currentModelId = model,
            filesManager = manager,
            client = client,
            onlyNewParts = 1,
        )
        val old = document(responsesContainerFileReference("cntr_old", "cfile_old"), fileName = "old.png")
        val fresh = document(responsesContainerFileReference("cntr_1", "cfile_1"))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(old, fresh), modelId = model)
        server.enqueue(ok(pngBytes()))

        val result = resolver.resolveFinished(listOf(message))

        assertEquals(1, server.requests.size)
        assertSame(old, result.single().parts[0])
        assertTrue(result.single().parts[1] is UIMessagePart.Image)
    }

    @Test
    fun `messages without references are returned unchanged`() = runTest {
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))))

        assertSame(messages, resolver.resolveFinished(messages))
        assertEquals(0, server.requests.size)
    }

    @Test
    fun `user attachments are never touched`() = runTest {
        val userFile = File(filesDir, "user-notes.txt").apply { writeText("keep me") }
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Document(
                        url = "file://${userFile.absolutePath}",
                        fileName = "user-notes.txt",
                        mime = "text/plain",
                    ),
                ),
            ),
        )

        val result = resolver.resolveFinished(messages)

        assertSame(messages, result)
        assertEquals("keep me", userFile.readText())
        assertEquals(0, server.requests.size)
    }

    /* ------------------------------------------------------------------ 落地形态 */

    @Test
    fun `bytes are stored unchanged and the sniffed mime wins over the file name`() = runTest {
        val html = "<!DOCTYPE html><html><body><p>chart</p></body></html>".encodeToByteArray()
        server.enqueue(ok(html))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)
        val part = document(responsesContainerFileReference("cntr_1", "cfile_1"), fileName = "chart.png")

        val outcome = resolver.resolvePart(part) as ResponsesFileOutcome.Stored
        val local = part.toLocalPart(outcome.localUrl, outcome.mime)

        // 名字是 png，内容是 HTML：必须是 Document + text/html，而不是可点的图片
        assertTrue(local is UIMessagePart.Document)
        local as UIMessagePart.Document
        assertEquals("text/html", local.mime)
        val file = File(local.url.removePrefix("file://"))
        assertTrue(file.isFile)
        assertEquals(html.toList(), file.readBytes().toList())
    }

    @Test
    fun `video parts follow the sniffed mime`() = runTest {
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(64)
        server.enqueue(ok(webm))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)
        val part = document(responsesContainerFileReference("cntr_1", "cfile_1"), fileName = "clip.bin")

        val outcome = resolver.resolvePart(part) as ResponsesFileOutcome.Stored
        val local = part.toLocalPart(outcome.localUrl, outcome.mime)

        assertEquals("video/webm", outcome.mime)
        assertTrue(local is UIMessagePart.Video)
    }

    @Test
    fun `mime sniffing never trusts the file name`() {
        // 认不出格式的非文本字节：不能因为文件名是 .png 就当图片
        assertEquals("application/octet-stream", responsesFileMime(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        assertEquals("text/plain", responsesFileMime("a,1\n2,3\n".encodeToByteArray()))
        assertEquals("application/json", responsesFileMime("{\"a\":1}".encodeToByteArray()))
        assertEquals("text/html", responsesFileMime("<html><body>x</body></html>".encodeToByteArray()))
        assertNotNull(sniffTextOrDocumentMime("plain text".encodeToByteArray()))
    }

    @Test
    fun `a failed part keeps text and annotation readable and leaks nothing`() = runTest {
        server.enqueue(FakeServer.Reply(403))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("saved chart.png"),
                document(responsesContainerFileReference("cntr_1", "cfile_1")),
            ),
            annotations = listOf(UIMessageAnnotation.ContainerFileCitation("cntr_1", "cfile_1", "chart.png")),
            modelId = null,
        )

        val result = resolver.resolveFinished(listOf(assistant)).single()

        assertEquals("saved chart.png", (result.parts[0] as UIMessagePart.Text).text)
        val failed = result.parts[1] as UIMessagePart.Document
        assertEquals("http_403", failed.responsesFileFailure())
        assertEquals(1, result.annotations.size)
        assertFalse(failed.metadata.toString().contains("sk-test"))
        assertEquals("cfile_1", failed.metadataAs<ResponsesFileOutputMetadata>()?.fileId)
    }

    @Test
    fun `file failure does not abort the whole turn`() = runTest {
        // 第一个文件 500，第二个正常：整条消息仍然处理完，坏的那个只标失败
        server.enqueue(FakeServer.Reply(500))
        server.enqueue(ok(pngBytes()))
        val resolver = ResponsesFileResolver(policy = policy(), filesManager = manager, client = client)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                document(responsesContainerFileReference("cntr_fail", "cfile_fail"), fileName = "fail.png"),
                UIMessagePart.Text("and this one"),
                document(responsesContainerFileReference("cntr_1", "cfile_1")),
            ),
            modelId = null,
        )

        val result = resolver.resolveFinished(listOf(message)).single()

        assertEquals("http_5xx", (result.parts[0] as UIMessagePart.Document).responsesFileFailure())
        assertEquals("and this one", (result.parts[1] as UIMessagePart.Text).text)
        assertTrue(result.parts[2] is UIMessagePart.Image)
    }

    @Test
    fun `size cap is 64 MiB`() {
        assertEquals(64L * 1024 * 1024, RESPONSES_FILE_MAX_BYTES)
    }
}
