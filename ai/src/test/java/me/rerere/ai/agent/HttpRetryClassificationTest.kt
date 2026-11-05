package me.rerere.ai.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.withStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.time.Duration.Companion.seconds

/**
 * 真 HTTP 服务端错误的可重试白名单：只认 provider 用真实 `Response.code` 构造出来的状态码。
 *
 * 这组断言的意义是「别把不该重发的又发一遍」：额度用尽、鉴权失败、请求格式错各自重发一次都是白花时间，
 * 而 408/429/5xx 不重试则等于把本来会自己好的错误直接摔给用户。
 */
class HttpRetryClassificationTest {
    @Test
    fun `temporary server errors are retryable`() {
        listOf(408, 429, 500, 502, 503, 504, 599).forEach { status ->
            val error = HttpException("Failed to get response: $status {}", statusCode = status)
            assertTrue("$status 应该可重试", retryableFailure(error))
            assertEquals(status, httpStatusOf(error))
        }
    }

    @Test
    fun `auth quota and request format errors are terminal`() {
        listOf(400, 401, 402, 403, 404, 409, 422).forEach { status ->
            val error = HttpException("Failed to get response: $status {}", statusCode = status)
            assertFalse("$status 不应该重试", retryableFailure(error))
            // 状态码本身仍要读得出来：界面和自动恢复要靠它决定提示什么
            assertEquals(status, httpStatusOf(error))
        }
    }

    @Test
    fun `429 with explicit quota markers is terminal`() {
        val quotaBodies = listOf(
            """{"error":{"message":"You exceeded your current quota, please check your plan","type":"insufficient_quota"}}""",
            """{"error":{"code":"insufficient_quota","message":"quota exhausted"}}""",
            """{"error":{"code":"billing_hard_limit_reached","message":"hard limit"}}""",
            """{"error":{"message":"Your credit balance is too low"}}""",
        )
        quotaBodies.forEach { body ->
            val error = HttpException("Failed to get response: 429 $body", statusCode = 429)
            assertFalse("额度错误不该重试：$body", retryableFailure(error))
        }
    }

    @Test
    fun `provider shaped 429 quota error stays terminal after the code is attached`() {
        // provider 的 SSE onFailure 路径：body 先 parseErrorDetail，再由 withStatusCode 补真实码。
        // type/code 字段在这一步被丢掉，判定只能靠 body 里的 message——OpenAI/Claude/Gemini 的额度
        // 文案都在 message 里，所以补上码之后仍然要判成不可重试。
        val parsed = Json.parseToJsonElement(
            """{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota"}}"""
        ).parseErrorDetail()
        val typed = parsed.withStatusCode(429)
        assertEquals(429, typed.statusCode)
        assertFalse(retryableFailure(typed))
        assertEquals(FailureKind.TERMINAL, RecoveryPolicy.classify(failureFacts(typed)))
    }

    @Test
    fun `plain 429 rate limit stays retryable`() {
        val error = HttpException(
            "Failed to get response: 429 {\"error\":{\"message\":\"Rate limit reached for gpt-4o\"}}",
            statusCode = 429,
        )
        assertTrue(retryableFailure(error))
    }

    @Test
    fun `status code is never guessed from message text`() {
        // 文本里出现 503 / 429 字样，但没有真正的状态码 → 不知道码，不重试
        val parsed = HttpException("Failed to get response: 503 upstream unavailable")
        assertNull(httpStatusOf(parsed))
        assertFalse(retryableFailure(parsed))

        val fromBody = Json.parseToJsonElement(
            """{"error":{"message":"Service Unavailable 503","code":"server_error"}}"""
        ).parseErrorDetail()
        assertNull("body 解析出来的错误没有真实状态码", fromBody.statusCode)
        assertNull(httpStatusOf(fromBody))
        assertFalse("没码就不盲重试", retryableFailure(fromBody))
    }

    @Test
    fun `parsed body message survives when the real status code is attached`() {
        val parsed = Json.parseToJsonElement(
            """{"error":{"message":"Upstream generation failed","code":"server_error"}}"""
        ).parseErrorDetail()
        val cause = IOException("connection reset")
        val typed = parsed.withStatusCode(503, cause = cause)

        assertEquals("Upstream generation failed", typed.message)
        assertEquals(503, typed.statusCode)
        assertTrue("cause 必须保留", typed.cause === cause)
        assertTrue(retryableFailure(typed))

        // 没有码时原样返回，不造一个新异常
        assertTrue(parsed.withStatusCode(null) === parsed)
    }

    @Test
    fun `transport failures keep their existing classification`() {
        assertTrue(retryableFailure(IOException("timeout")))
        assertFalse(retryableFailure(SSLHandshakeException("bad certificate")))
        assertFalse(retryableFailure(SSLPeerUnverifiedException("hostname mismatch")))
        assertFalse(retryableFailure(WebProtocolException("response was not JSON")))
        assertFalse(retryableFailure(IllegalStateException("bad request")))
    }

    @Test
    fun `retry policy default follows the same whitelist`() {
        val policy = RetryPolicy(maxRetries = 3)
        assertTrue(policy.retryOn(HttpException("Failed to get response: 503", statusCode = 503)))
        assertTrue(policy.retryOn(IOException("connection reset")))
        assertFalse(policy.retryOn(HttpException("Failed to get response: 401", statusCode = 401)))
        assertFalse(
            policy.retryOn(
                HttpException(
                    "Failed to get response: 429 {\"code\":\"insufficient_quota\"}",
                    statusCode = 429,
                )
            )
        )
        assertFalse(policy.retryOn(HttpException("tool_choice is not supported")))
    }

    @Test
    fun `failure facts carry the real status code`() {
        assertEquals(503, failureFacts(HttpException("Failed to get response: 503", statusCode = 503)).httpStatus)
        assertEquals(401, failureFacts(HttpException("Failed to get response: 401", statusCode = 401)).httpStatus)
        assertNull(failureFacts(IOException("timeout")).httpStatus)
    }

    @Test
    fun `retrying a 5xx goes through the budget and a 400 does not`() = runTest {
        var calls = 0
        val result = withRetry(RetryPolicy(maxRetries = 2, initialDelay = 0.seconds)) {
            calls++
            if (calls < 2) throw HttpException("Failed to get response: 503 {}", statusCode = 503)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)

        var terminalCalls = 0
        val error = runCatching {
            withRetry(RetryPolicy(maxRetries = 3, initialDelay = 0.seconds)) {
                terminalCalls++
                throw HttpException("Failed to get response: 400 {}", statusCode = 400)
            }
        }.exceptionOrNull()

        assertTrue(error is HttpException)
        assertEquals(1, terminalCalls)
    }
}
