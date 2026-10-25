package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.agent.RetryAttempt
import me.rerere.ai.agent.RetryDelay
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.withRetry
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.RetryDelayMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "网络 → 自动重试"里等待时长的配置：默认值、老配置兼容、坏值归整，
 * 以及这些配置真的落到 [RetryPolicy] 的等待上（界面写多少就等多少）。
 */
class RetryDelayConfigTest {
    /** 跑一遍必然失败的重试，返回每次重试前的等待（不再真的等，runTest 用虚拟时间）。 */
    private suspend fun delaysOf(policy: RetryPolicy): List<Duration> {
        val attempts = mutableListOf<RetryAttempt>()
        runCatching {
            withRetry(policy, onRetry = { attempts += it }) {
                throw IOException("connection reset")
            }
        }
        return attempts.map { it.delay }
    }

    @Test
    fun `default setting waits a fixed 60 seconds`() {
        val policy = Settings().retryPolicy()

        assertEquals(3, policy.maxRetries)
        assertEquals(RetryDelay.Fixed(60.seconds), policy.delayMode)
        assertEquals(listOf(60.seconds, 60.seconds, 60.seconds), (1..3).map { policy.delayFor(it) })
    }

    @Test
    fun `unconfigured ai policy keeps the exponential backoff`() {
        // ai 模块默认值不能跟着 App 改，仓库里已有测试就依赖 1s、2s、4s
        val policy = RetryPolicy(maxRetries = 3)

        assertEquals(RetryDelay.Exponential, policy.delayMode)
        assertEquals(listOf(1.seconds, 2.seconds, 4.seconds), (1..3).map { policy.delayFor(it) })
    }

    @Test
    fun `old stored json without the new fields still loads`() {
        // 这就是上一版写进 preferences 的格式，一个字段都没多
        val stored = """{"userAgent":"ua","proxyUrl":"","proxyUsername":"","proxyPassword":"","enableAutoRetry":true,"maxRetries":5}"""

        val decoded = JsonInstant.decodeFromString<NetworkSetting>(stored)

        assertEquals("ua", decoded.userAgent)
        assertEquals(5, decoded.maxRetries)
        assertEquals(RetryDelayMode.FIXED, decoded.retryDelayMode)
        assertEquals(60, decoded.retryDelaySeconds)
        assertEquals(30, decoded.retryDelayMinSeconds)
        assertEquals(60, decoded.retryDelayMaxSeconds)
    }

    @Test
    fun `old settings keep loading through the stored per field json`() {
        // DataStore 里 network_setting 这个 key 存的就是 NetworkSetting 的 JSON，上一版只有这 6 个字段
        val stored = """{"userAgent":"","proxyUrl":"","proxyUsername":"","proxyPassword":"","enableAutoRetry":false,"maxRetries":2}"""

        val settings = Settings(networkSetting = JsonInstant.decodeFromString<NetworkSetting>(stored))

        assertEquals(2, settings.networkSetting.maxRetries)
        assertEquals(60, settings.networkSetting.normalizedRetryDelay().fixedSeconds)
        assertEquals(0, settings.retryPolicy().maxRetries)
    }

    @Test
    fun `new fields survive a json round trip`() {
        val setting = NetworkSetting(
            enableAutoRetry = true,
            maxRetries = 7,
            retryDelayMode = RetryDelayMode.RANGE,
            retryDelaySeconds = 45,
            retryDelayMinSeconds = 12,
            retryDelayMaxSeconds = 99,
        )

        val json = JsonInstant.encodeToString(setting)
        val decoded = JsonInstant.decodeFromString<NetworkSetting>(json)

        assertEquals(setting, decoded)
        assertTrue("区间模式要能看出来是 range，不能悄悄变成 fixed", json.contains("\"range\""))
    }

    @Test
    fun `bad range values are normalized instead of dropped`() {
        val outOfRange = NetworkSetting(
            retryDelayMode = RetryDelayMode.RANGE,
            retryDelayMinSeconds = 0,
            retryDelayMaxSeconds = 9_000,
        ).normalizedRetryDelay()
        assertEquals(1, outOfRange.minSeconds)
        assertEquals(600, outOfRange.maxSeconds)

        val reversed = NetworkSetting(
            retryDelayMode = RetryDelayMode.RANGE,
            retryDelayMinSeconds = 90,
            retryDelayMaxSeconds = 20,
        ).normalizedRetryDelay()
        assertEquals(20, reversed.minSeconds)
        assertEquals(90, reversed.maxSeconds)

        val badFixed = NetworkSetting(
            retryDelayMode = RetryDelayMode.FIXED,
            retryDelaySeconds = 0,
        ).normalizedRetryDelay()
        assertEquals(1, badFixed.fixedSeconds)

        val tooLong = NetworkSetting(
            retryDelayMode = RetryDelayMode.FIXED,
            retryDelaySeconds = 5_000,
        ).normalizedRetryDelay()
        assertEquals(600, tooLong.fixedSeconds)
    }

    @Test
    fun `fixed delay waits the configured seconds every retry`() = runTest {
        val setting = NetworkSetting(
            enableAutoRetry = true,
            maxRetries = 2,
            retryDelayMode = RetryDelayMode.FIXED,
            retryDelaySeconds = 30,
        )

        val delays = delaysOf(Settings(networkSetting = setting).retryPolicy())

        assertEquals(listOf(30.seconds, 30.seconds), delays)
        assertEquals(60_000L, currentTime)
    }

    @Test
    fun `30 to 60 range lands inside the range every time`() = runTest {
        val setting = NetworkSetting(
            enableAutoRetry = true,
            maxRetries = 6,
            retryDelayMode = RetryDelayMode.RANGE,
            retryDelayMinSeconds = 30,
            retryDelayMaxSeconds = 60,
        )

        // 期望值用同 seed 的 Random 算出来：不靠概率，跑多少次都是同一串数
        val reference = Random(7)
        val expected = List(6) { reference.nextInt(30, 61).seconds }
        assertTrue("固定 seed 下要真的取到多个不同的值", expected.distinct().size > 1)

        val base = Settings(networkSetting = setting).retryPolicy()
        val configured = base.delayMode as RetryDelay.RandomRange
        assertEquals(30.seconds, configured.min)
        assertEquals(60.seconds, configured.max)
        val delays = delaysOf(base.copy(delayMode = configured.copy(random = Random(7))))

        assertEquals(expected, delays)
        assertTrue(delays.all { it.inWholeSeconds in 30..60 })
    }

    @Test
    fun `seeded range delay is reproducible`() {
        val first = RetryPolicy(
            maxRetries = 4,
            delayMode = RetryDelay.randomSeconds(30, 60, Random(42)),
        )
        val second = RetryPolicy(
            maxRetries = 4,
            delayMode = RetryDelay.randomSeconds(30, 60, Random(42)),
        )

        assertEquals((1..4).map { first.delayFor(it) }, (1..4).map { second.delayFor(it) })
        assertTrue((1..4).map { first.delayFor(it) }.all { it.inWholeSeconds in 30..60 })
    }

    @Test
    fun `turning auto retry off disables retries and waiting`() = runTest {
        val off = Settings(
            networkSetting = NetworkSetting(enableAutoRetry = false, maxRetries = 9),
        ).retryPolicy()

        assertEquals(0, off.maxRetries)
        assertEquals(emptyList<Duration>(), delaysOf(off))
    }

    @Test
    fun `zero retries reads as no retry even when the switch is on`() = runTest {
        val policy = Settings(
            networkSetting = NetworkSetting(enableAutoRetry = true, maxRetries = 0),
        ).retryPolicy()

        assertEquals(0, policy.maxRetries)
        assertEquals(emptyList<Duration>(), delaysOf(policy))
        assertEquals(0L, currentTime)
    }

    @Test
    fun `cancellation is not retried with the configured policy`() = runTest {
        val policy = Settings(
            networkSetting = NetworkSetting(
                enableAutoRetry = true,
                maxRetries = 3,
                retryDelayMode = RetryDelayMode.FIXED,
                retryDelaySeconds = 60,
            ),
        ).retryPolicy()
        var attempts = 0
        var notified = 0

        val job = launch {
            withRetry(policy, onRetry = { notified++ }) {
                attempts++
                // 停止生成时底层连接会以 IOException("Canceled") 收尾
                currentCoroutineContext().cancel()
                throw IOException("Canceled")
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, attempts)
        assertEquals(0, notified)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `cancellation exception itself is never retried`() = runTest {
        val policy = Settings(
            networkSetting = NetworkSetting(enableAutoRetry = true, maxRetries = 3),
        ).retryPolicy()
        var attempts = 0

        val error = runCatching {
            withRetry(policy) {
                attempts++
                throw CancellationException("user stopped the run")
            }
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(1, attempts)
    }

    @Test
    fun `settings json keeps the delay fields in the stored format`() {
        val setting = NetworkSetting(
            retryDelayMode = RetryDelayMode.RANGE,
            retryDelaySeconds = 45,
            retryDelayMinSeconds = 30,
            retryDelayMaxSeconds = 60,
        )

        val json = Json.parseToJsonElement(JsonInstant.encodeToString(setting))

        assertEquals("range", json.jsonObject["retryDelayMode"]?.jsonPrimitive?.content)
        assertEquals("45", json.jsonObject["retryDelaySeconds"]?.jsonPrimitive?.content)
        assertEquals("30", json.jsonObject["retryDelayMinSeconds"]?.jsonPrimitive?.content)
        assertEquals("60", json.jsonObject["retryDelayMaxSeconds"]?.jsonPrimitive?.content)
    }
}
