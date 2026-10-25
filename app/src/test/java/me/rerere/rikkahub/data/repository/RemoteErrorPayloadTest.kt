package me.rerere.rikkahub.data.repository

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.RemoteExecutionSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * 远程执行 HTTP 错误细节（review P4）。
 *
 * 旧实现 `check(it.isSuccessful)` 在读 body 之前抛错，服务端的 `{"error": ...}`
 * 和反代的 HTML 错误页全被丢掉，模型只看到 "HTTP 400"/"HTTP 429"，无法自纠。
 *
 * 集成用例用 JDK 自带 com.sun.net.httpserver（不新增依赖），与 [RemoteExecutionClient]
 * 走真实 OkHttp 往返；回环地址允许 http，所以不需要 TLS。
 */
class RemoteErrorPayloadTest {
    private var server: HttpServer? = null

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    /** 起一个回环 HTTP 服务，固定回复 [code] + [contentType] + 由 [body] 生成的正文。 */
    private fun serve(
        code: Int,
        contentType: String = "application/json",
        body: (HttpExchange) -> String,
    ): RemoteExecutionSettings {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val bytes = body(exchange).toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return RemoteExecutionSettings(
            enabled = true,
            baseUrl = "http://127.0.0.1:${http.address.port}",
            token = "s3cr3t-token",
        )
    }

    private fun failureMessage(settings: RemoteExecutionSettings): String {
        val error = runCatching {
            runBlocking { RemoteExecutionClient().startJob(settings, "echo hi", "~/rk-work") }
        }.exceptionOrNull()
        assertTrue("必须抛 IllegalStateException，实际：$error", error is IllegalStateException)
        return error!!.message.orEmpty()
    }

    @Test
    fun `raw reflected client secret is removed without needing a bearer prefix`() {
        val settings = serve(401) { """{"error":"invalid token s3cr3t-token for this route"}""" }
        val message = failureMessage(settings)
        assertTrue(message, message.contains("HTTP 401"))
        assertTrue(message, message.contains("[redacted]"))
        assertFalse(message, message.contains("s3cr3t-token"))
    }

    @Test
    fun `empty redaction secret does not modify ordinary reasons`() {
        assertEquals("execution slots full", remoteErrorReason("execution slots full", ""))
    }

    @Test
    fun `400 keeps the server reason so the model can fix the cwd`() {
        val settings = serve(400) { """{"error":"Working directory does not exist"}""" }
        val message = failureMessage(settings)
        assertTrue(message, message.contains("HTTP 400"))
        assertTrue(message, message.contains("Working directory does not exist"))
    }

    @Test
    fun `429 keeps the slot exhaustion reason`() {
        val settings = serve(429) { """{"error":"execution slots full"}""" }
        val message = failureMessage(settings)
        assertTrue(message, message.contains("HTTP 429"))
        assertTrue(message, message.contains("execution slots full"))
    }

    @Test
    fun `401 reports the code without echoing the bearer token`() {
        // 模拟坏反代把请求头回显进错误正文
        val settings = serve(401) { ex ->
            """{"error":"unauthorized for ${ex.requestHeaders.getFirst("Authorization")}"}"""
        }
        val message = failureMessage(settings)
        assertTrue(message, message.contains("HTTP 401"))
        assertFalse("不能泄露 token：$message", message.contains("s3cr3t-token"))
        assertTrue(message, message.contains("[redacted]"))
    }

    @Test
    fun `html error page degrades to a short summary instead of raw markup`() {
        val settings = serve(502, "text/html") {
            "<html><head><title>502 Bad Gateway</title></head><body><h1>nginx</h1></body></html>"
        }
        val message = failureMessage(settings)
        assertTrue(message, message.contains("HTTP 502"))
        assertTrue(message, message.contains("502 Bad Gateway"))
        assertFalse("不能把标签塞进上下文：$message", message.contains("<html"))
    }

    @Test
    fun `overlong error body is capped and keeps the code`() {
        val settings = serve(429) { """{"error":"execution slots full """ + "x".repeat(10_000) + "\"}" }
        val message = failureMessage(settings)
        assertTrue(message, message.contains("HTTP 429"))
        assertTrue(message, message.contains("execution slots full"))
        assertTrue("错误信息必须受限，实际 ${message.length} 字符", message.length <= REMOTE_ERROR_BODY_LIMIT)
        assertTrue(message, message.endsWith("…"))
    }

    @Test
    fun `empty error body still reports the http code`() {
        val settings = serve(500) { "" }
        val message = failureMessage(settings)
        assertTrue(message, message.contains("HTTP 500"))
        assertFalse("不要回显 null/空白：$message", message.contains("null"))
    }

    @Test
    fun `successful response still parses the json payload`() {
        val settings = serve(200) { """{"id":"job-1","logPath":"/tmp/job-1.log"}""" }
        val info = runBlocking { RemoteExecutionClient().startJob(settings, "echo hi", "~/rk-work") }
        assertEquals("job-1", info.id)
        assertEquals("/tmp/job-1.log", info.logPath)
    }

    @Test
    fun `oversized success body is still rejected by the 1 MiB cap`() {
        val settings = serve(200) { " ".repeat(1_100_000) }
        val error = runCatching {
            runBlocking { RemoteExecutionClient().startJob(settings, "echo hi", "~/rk-work") }
        }.exceptionOrNull()
        assertTrue("1 MiB 上限必须保留，实际：$error", error?.message.orEmpty().contains("1 MiB"))
    }
}

/** 纯解析辅助函数（不需要起服务）的用例。 */
class RemoteErrorReasonTest {
    @Test
    fun `json error field wins over message`() {
        assertEquals("slots full", remoteErrorReason("""{"error":"slots full","message":"ignored"}"""))
    }

    @Test
    fun `json message field is used when error is absent`() {
        assertEquals("boom", remoteErrorReason("""{"message":"boom"}"""))
    }

    @Test
    fun `blank or missing body has no reason`() {
        assertNull(remoteErrorReason(null))
        assertNull(remoteErrorReason(""))
        assertNull(remoteErrorReason("   \n "))
        assertNull(remoteErrorReason("""{"error":""}"""))
        assertEquals(
            "Remote request /job/status failed: HTTP 404",
            remoteHttpFailure("/job/status", 404, null),
        )
    }

    @Test
    fun `html page is reduced to plain text`() {
        val reason = remoteErrorReason("<html><body><h1>503 Service Unavailable</h1></body></html>")
        assertEquals("503 Service Unavailable", reason)
    }

    @Test
    fun `bearer credentials are masked`() {
        val reason = remoteErrorReason("""{"error":"bad header Bearer abc.def-123"}""")
        assertEquals("bad header Bearer [redacted]", reason)
    }

    @Test
    fun `full urls are redacted instead of echoed`() {
        val reason = remoteErrorReason("upstream https://10.0.0.7:8443/rk-exec?token=x failed").orEmpty()
        assertTrue(reason, reason.contains("[url]"))
        assertFalse(reason, reason.contains("10.0.0.7"))
    }

    @Test
    fun `long reasons are truncated with an ellipsis`() {
        val reason = remoteErrorReason("x".repeat(5_000))!!
        assertTrue("实际 ${reason.length} 字符", reason.length <= 200)
        assertTrue(reason.endsWith("…"))
    }

    @Test
    fun `truncated json body still yields the error field`() {
        // body 被 2KiB 上限切断时 JSON 解析必然失败，不能把半个 '{' 回显出去
        val reason = remoteErrorReason("""{"error":"execution slots full","padding":"xxxx""")
        assertEquals("execution slots full", reason)
    }

    @Test
    fun `reason is collapsed to a single line`() {
        val reason = remoteErrorReason("line one\n\n\tline two")
        assertEquals("line one line two", reason)
    }
}
