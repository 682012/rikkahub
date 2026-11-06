package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.NetworkUnavailableException
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionException
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 「任何错误也重试」的用户自选范围：类别折算、默认分类不变、预算随范围记账、取消永不重放。
 *
 * 全部是确定性的：不 sleep、不依赖随机、不猜错误文本里的数字。
 */
class RetryScopeTest {

    private val customProviderError = IllegalStateException("vendor returned nonsense")

    // ---- 类别折算：只认真实类型和真实状态码 ------------------------------------

    @Test
    fun `real status codes map to their own category`() {
        assertEquals(RetryErrorCategory.AUTH, retryCategoryOf(HttpException("unauthorized", 401)))
        assertEquals(RetryErrorCategory.AUTH, retryCategoryOf(HttpException("forbidden", 403)))
        assertEquals(RetryErrorCategory.QUOTA, retryCategoryOf(HttpException("payment required", 402)))
        assertEquals(RetryErrorCategory.SERVER, retryCategoryOf(HttpException("boom", 500)))
        assertEquals(RetryErrorCategory.SERVER, retryCategoryOf(HttpException("slow down", 429)))
        assertEquals(RetryErrorCategory.QUOTA, retryCategoryOf(HttpException("insufficient_quota", 429)))
        assertEquals(RetryErrorCategory.REQUEST_FORMAT, retryCategoryOf(HttpException("bad", 400)))
        assertEquals(RetryErrorCategory.REQUEST_FORMAT, retryCategoryOf(HttpException("bad", 422)))
    }

    @Test
    fun `status codes are never guessed from the message text`() {
        // 文本里写着 401，但没有真实状态码：按格式错误处理，不跟着文本走
        val unparsed = HttpException("provider said 401 unauthorized in the body")
        assertNull(httpStatusOf(unparsed))
        assertEquals(RetryErrorCategory.REQUEST_FORMAT, retryCategoryOf(unparsed))
        assertEquals(RetryErrorCategory.REQUEST_FORMAT, retryCategoryOf(WebProtocolException("not json")))
        assertEquals(RetryErrorCategory.AUTH, retryCategoryOf(WebSessionException("session expired")))
        assertEquals(
            RetryErrorCategory.EMPTY_RESPONSE,
            retryCategoryOf(StreamTruncatedException("closed before finish_reason")),
        )
        assertEquals(RetryErrorCategory.NETWORK, retryCategoryOf(IOException("reset")))
        assertEquals(RetryErrorCategory.NETWORK, retryCategoryOf(NetworkUnavailableException()))
        assertEquals(RetryErrorCategory.UNKNOWN, retryCategoryOf(customProviderError))
    }

    // ---- 默认分类必须和旧行为一致 ----------------------------------------------

    @Test
    fun `default scope keeps the old classification`() {
        val scope = RetryScope.CLASSIFIED
        assertTrue(scope.allows(IOException("reset")))
        assertTrue(scope.allows(HttpException("boom", 503)))
        assertTrue(scope.allows(StreamTruncatedException("half")))
        // 永久错误默认一次都不试
        assertFalse(scope.allows(HttpException("unauthorized", 401)))
        assertFalse(scope.allows(HttpException("forbidden", 403)))
        assertFalse(scope.allows(HttpException("payment required", 402)))
        assertFalse(scope.allows(HttpException("insufficient_quota", 429)))
        assertFalse(scope.allows(HttpException("bad", 422)))
        assertFalse(scope.allows(WebProtocolException("not json")))
        assertFalse(scope.allows(customProviderError))
        assertEquals(RetryScope.CLASSIFIED, RetryScope())
        // 老入口 retryableFailure 走同一个判定
        assertEquals(
            scope.allows(HttpException("boom", 500)),
            retryableFailure(HttpException("boom", 500)),
        )
        assertEquals(
            scope.allows(HttpException("unauthorized", 401)),
            retryableFailure(HttpException("unauthorized", 401)),
        )
    }

    @Test
    fun `custom scope adds the selected categories and keeps the defaults`() {
        val scope = RetryScope(
            mode = RetryScopeMode.CUSTOM,
            categories = setOf(RetryErrorCategory.AUTH),
        )
        assertTrue(scope.allows(HttpException("unauthorized", 401)))
        // 追加而不是替换：网络重试还在
        assertTrue(scope.allows(IOException("reset")))
        assertFalse(scope.allows(HttpException("payment required", 402)))
    }

    @Test
    fun `any error scope allows every category including unknown provider errors`() {
        val scope = RetryScope.ANY_ERROR
        RetryErrorCategory.entries.forEach { category ->
            assertTrue(category.name, scope.allows(category))
        }
        assertTrue(scope.allows(customProviderError))
        assertTrue(scope.allows(HttpException("unauthorized", 401)))
        assertEquals(RetryScopeMode.ANY_ERROR, scope.mode)
        assertTrue(scope.triesEverything)
    }

    // ---- 分类层：同一份事实、不同范围 ------------------------------------------

    @Test
    fun `classify turns opted in categories into retryable opt in`() {
        val unauthorized = FailureFacts(httpStatus = 401, transport = false)
        assertEquals(FailureKind.TERMINAL, RecoveryPolicy.classify(unauthorized))
        assertEquals(FailureKind.OPT_IN, RecoveryPolicy.classify(unauthorized, RetryScope.ANY_ERROR))
        assertTrue(RecoveryPolicy.isRetryable(FailureKind.OPT_IN))

        val quota = FailureFacts(quotaExhausted = true, httpStatus = 429, transport = false)
        assertEquals(FailureKind.TERMINAL, RecoveryPolicy.classify(quota))
        assertEquals(FailureKind.OPT_IN, RecoveryPolicy.classify(quota, RetryScope.ANY_ERROR))

        // 没有状态码的未知异常：默认终止，勾了未知类别才试
        val unknown = FailureFacts(transport = false)
        assertEquals(FailureKind.TERMINAL, RecoveryPolicy.classify(unknown))
        assertEquals(FailureKind.OPT_IN, RecoveryPolicy.classify(unknown, RetryScope.ANY_ERROR))
        assertEquals(RetryErrorCategory.UNKNOWN, RecoveryPolicy.categoryOf(unknown))

        // 用户取消在任何范围下都不重试
        val cancelled = FailureFacts(cancelled = true, httpStatus = 401)
        assertEquals(FailureKind.USER_STOPPED, RecoveryPolicy.classify(cancelled, RetryScope.ANY_ERROR))
        assertFalse(RecoveryPolicy.isRetryable(FailureKind.USER_STOPPED))
    }

    // ---- 预算：类别贯通到记账 ------------------------------------------------

    @Test
    fun `budget records opted in failures like real retries`() {
        val budget = RetryBudget(2, RetryScope.ANY_ERROR)
        assertTrue(budget.record(FailureKind.OPT_IN))
        assertTrue(budget.record(FailureKind.OPT_IN))
        assertFalse("第三次必须停下", budget.record(FailureKind.OPT_IN))
        assertEquals(2, budget.retries)
    }

    @Test
    fun `budget never records a cancelled turn`() {
        val budget = RetryBudget(3, RetryScope.ANY_ERROR)
        assertFalse(budget.record(FailureKind.USER_STOPPED))
        assertEquals(0, budget.retries)
    }

    @Test
    fun `unlimited budget ignores the retry count but keeps cancellation terminal`() {
        val budget = RetryBudget(maxRetries = 1, scope = RetryScope.ANY_ERROR, unlimited = true)
        repeat(50) { assertTrue(budget.record(FailureKind.OPT_IN)) }
        assertEquals(50, budget.retries)
        assertFalse(budget.record(FailureKind.USER_STOPPED))
        assertFalse(budget.record(FailureKind.TERMINAL))
    }

    // ---- 请求层：范围真的改变 withRetry 的行为 --------------------------------

    @Test
    fun `withRetry retries a 401 only when the user opted in`() = runTest {
        var attempts = 0
        val error = HttpException("unauthorized", 401)

        runCatching {
            withRetry(RetryPolicy(maxRetries = 2)) {
                attempts++
                throw error
            }
        }
        assertEquals("默认分类不该重试 401", 1, attempts)

        attempts = 0
        var retried = 0
        runCatching {
            withRetry(
                RetryPolicy(
                    maxRetries = 2,
                    scope = RetryScope.ANY_ERROR,
                    retryOn = { RetryScope.ANY_ERROR.allows(it) },
                ),
                onRetry = { retried++ },
            ) {
                attempts++
                throw error
            }
        }
        assertEquals(3, attempts)
        assertEquals(2, retried)
    }

    @Test
    fun `withRetry replays a custom provider exception only in any error mode`() = runTest {
        var defaultAttempts = 0
        runCatching {
            withRetry(RetryPolicy(maxRetries = 1)) {
                defaultAttempts++
                throw customProviderError
            }
        }
        assertEquals(1, defaultAttempts)

        var attempts = 0
        runCatching {
            withRetry(
                RetryPolicy(
                    maxRetries = 1,
                    scope = RetryScope.ANY_ERROR,
                    retryOn = { RetryScope.ANY_ERROR.allows(it) },
                )
            ) {
                attempts++
                throw customProviderError
            }
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `cancellation is never replayed even in any error mode`() = runTest {
        var attempts = 0
        val result = runCatching {
            withRetry(
                RetryPolicy(
                    maxRetries = 5,
                    scope = RetryScope.ANY_ERROR,
                    retryOn = { RetryScope.ANY_ERROR.allows(it) },
                )
            ) {
                attempts++
                throw CancellationException("user stopped")
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals("取消不能重放", 1, attempts)
    }

    // ---- 序列化：老配置能读，新配置能存 --------------------------------------

    @Test
    fun `scope serializes with stable names and old json still decodes`() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            """{"mode":"any_error"}""",
            json.encodeToString(RetryScope.serializer(), RetryScope.ANY_ERROR),
        )
        assertEquals(
            RetryScope.CLASSIFIED,
            json.decodeFromString(RetryScope.serializer(), """{"mode":"classified"}"""),
        )
        assertEquals(
            RetryScope.CLASSIFIED,
            json.decodeFromString(RetryScope.serializer(), "{}"),
        )
    }

    // ---- 默认分类必须和旧 retryableFailure 逐条等价，包括真实码的边界 ----------

    @Test
    fun `classified scope matches the old retryableFailure on odd status codes`() {
        // 旧实现只放行 408/429/5xx。1xx/3xx/6xx 既不是服务端临时错误也不在白名单里，
        // 新版不能因为"不是 4xx 就当 SERVER"，否则 CLASSIFIED 会比旧行为多试一次。
        for (status in listOf(100, 204, 302, 404, 418, 600)) {
            val error = HttpException("odd $status", status)
            assertFalse("status $status", retryableFailure(error))
            val expected = if (status in 400..499) RetryErrorCategory.REQUEST_FORMAT else RetryErrorCategory.UNKNOWN
            assertEquals("status $status", expected, retryCategoryOf(error))
        }
        assertTrue("5xx 仍然重试", retryableFailure(HttpException("boom", 503)))
    }

    @Test
    fun `classified scope never retries what the old implementation refused`() {
        assertFalse(retryableFailure(WebSessionException("session expired")))
        assertFalse(retryableFailure(javax.net.ssl.SSLHandshakeException("bad cert")))
        assertFalse(retryableFailure(javax.net.ssl.SSLPeerUnverifiedException("hostname")))
        assertTrue(retryableFailure(java.net.SocketTimeoutException("timeout")))
        assertTrue(retryableFailure(NetworkUnavailableException()))
    }

    // ---- 总开关：实时读取，退避中途关掉立刻停 --------------------------------

    @Test
    fun `closing the switch during backoff stops a retry that was already scheduled`() = runTest {
        var enabled = true
        var attempts = 0
        runCatching {
            withRetry(
                RetryPolicy(
                    maxRetries = 5,
                    delayMode = RetryDelay.fixed(30),
                    retryGate = RetryGate { enabled },
                ),
                onRetry = { enabled = false },
            ) {
                attempts++
                throw IOException("reset")
            }
        }
        assertEquals("关掉开关后不再发第二次，也不等满 30 秒", 1, attempts)
    }

    @Test
    fun `a closed switch still allows the first attempt`() = runTest {
        var attempts = 0
        runCatching {
            withRetry(RetryPolicy(maxRetries = 3, retryGate = RetryGate { false })) {
                attempts++
                throw IOException("reset")
            }
        }
        assertEquals("关掉自动重试不等于不发请求", 1, attempts)
    }

    @Test
    fun `one shared budget covers every step of a turn`() = runTest {
        val budget = RetryBudget(maxRetries = 2)
        val policy = RetryPolicy(maxRetries = 2, sharedBudget = budget)

        var firstStep = 0
        runCatching {
            withRetry(policy) {
                firstStep++
                throw IOException("reset")
            }
        }
        assertEquals(3, firstStep)

        var secondStep = 0
        runCatching {
            withRetry(policy) {
                secondStep++
                throw IOException("reset")
            }
        }
        assertEquals("整轮预算已经在第一步用完", 1, secondStep)
        assertEquals(2, budget.retries)
    }
}
