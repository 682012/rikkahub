package me.rerere.ai.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RetryTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `io failures are retried with doubling delays`() = runTest {
        var calls = 0
        val attempts = mutableListOf<RetryAttempt>()

        val result = withRetry(RetryPolicy.NETWORK, onRetry = { attempts += it }) {
            calls++
            if (calls < 3) throw IOException("timeout")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf(1, 2), attempts.map { it.retry })
        assertEquals(listOf(1.seconds, 2.seconds), attempts.map { it.delay })
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `gives up after max retries`() = runTest {
        var calls = 0

        val error = runCatching {
            withRetry(RetryPolicy.NETWORK) {
                calls++
                throw IOException("offline")
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(4, calls)
    }

    @Test
    fun `other errors are thrown without retrying`() = runTest {
        var calls = 0

        val error = runCatching {
            withRetry(RetryPolicy.NETWORK) {
                calls++
                throw IllegalStateException("bad request")
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(1, calls)
    }

    @Test
    fun `io failure caused by cancellation is not retried`() = runTest {
        var calls = 0

        val job = launch {
            withRetry(RetryPolicy(maxRetries = 3, initialDelay = Duration.ZERO)) {
                calls++
                // 停止生成时，底层连接会以 IOException("Canceled") 结束请求
                currentCoroutineContext().cancel()
                throw IOException("Canceled")
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test
    fun `exponential stays the default when no delay mode is given`() {
        val policy = RetryPolicy(maxRetries = 4)

        assertEquals(RetryDelay.Exponential, policy.delayMode)
        assertEquals(1.seconds, policy.delayFor(1))
        assertEquals(2.seconds, policy.delayFor(2))
        assertEquals(4.seconds, policy.delayFor(3))
        assertEquals(32.seconds, policy.delayFor(6))
        // 64s 超过 maxDelay(60s)，之后一直是 60s
        assertEquals(60.seconds, policy.delayFor(7))
        assertEquals(60.seconds, policy.delayFor(20))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `fixed delay waits the same amount every retry`() = runTest {
        val attempts = mutableListOf<RetryAttempt>()
        var calls = 0

        runCatching {
            withRetry(RetryPolicy(maxRetries = 3, delayMode = RetryDelay.fixed(30)), onRetry = { attempts += it }) {
                calls++
                throw IOException("timeout")
            }
        }

        assertEquals(4, calls)
        assertEquals(listOf(30.seconds, 30.seconds, 30.seconds), attempts.map { it.delay })
        assertEquals(90_000L, currentTime)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `random range stays inside the range and follows the injected random`() = runTest {
        // 期望值用同 seed 的 Random 提前算好，测试不依赖概率
        val reference = Random(7)
        val expected = List(4) { reference.nextInt(30, 61).seconds }
        val attempts = mutableListOf<RetryAttempt>()

        runCatching {
            withRetry(
                RetryPolicy(maxRetries = 4, delayMode = RetryDelay.randomSeconds(30, 60, Random(7))),
                onRetry = { attempts += it },
            ) {
                throw IOException("timeout")
            }
        }

        assertEquals(expected, attempts.map { it.delay })
        assertTrue(attempts.all { it.delay.inWholeSeconds in 30..60 })
        assertTrue("每次都在同一区间随机，不该是同一个值恒等", expected.distinct().size > 1)
        assertEquals(expected.sumOf { it.inWholeMilliseconds }, currentTime)
    }

    @Test
    fun `degenerate range waits a single value`() {
        val policy = RetryPolicy(maxRetries = 2, delayMode = RetryDelay.randomSeconds(45, 45, Random(1)))

        assertEquals(45.seconds, policy.delayFor(1))
        assertEquals(45.seconds, policy.delayFor(2))
    }

    @Test
    fun `random range rejects a reversed range but randomSeconds orders it`() {
        val normalized = RetryDelay.randomSeconds(60, 30, Random(1))
        assertEquals(30.seconds, normalized.min)
        assertEquals(60.seconds, normalized.max)

        val error = runCatching { RetryDelay.RandomRange(60.seconds, 30.seconds) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
