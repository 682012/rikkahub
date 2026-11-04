package me.rerere.common.http

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * 退避期间掉线的行为。
 *
 * 掉线不是「换到新网络」：如果照旧把剩余预算缩短成一个稳定期就返回，
 * 调用方会在完全没网的时候立刻发起一次必然失败的请求，用户看到的就是
 * 「断线后再也不重连」。这里断言等待要么等到网络恢复，要么把这次配置的间隔等满。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkHandoverOfflineDropTest {

    @Test
    fun `drop during backoff does not end the wait at once`() = runTest {
        val waiter = DropWaiter(testScheduler)
        val network = NetworkHandover(waiter).apply { updateRoute("wifi", true) }
        var waited: Long = -1

        val job = launch { network.awaitRetry(attemptEpoch = 0L, regularDelay = 60.seconds); waited = currentTime }

        advanceTimeBy(5.seconds)
        // 退避途中整条默认网络掉了
        network.updateRoute(null, false)
        advanceTimeBy(30.seconds)

        // 旧行为：掉线被当成「换代已结束」，几毫秒后就返回，于是立刻重试撞墙
        assertFalse("掉线后不该立刻结束退避等待", job.isCompleted)

        // 网络恢复：等待醒来，只补一小段稳定期
        network.updateRoute("cell", true)
        job.join()

        assertTrue("应当等到恢复之后才重试，实际 $waited ms", waited >= 35_000L)
        assertEquals(35_000L + NetworkHandover.STABILIZE_MILLIS, waited)
    }

    @Test
    fun `a network that never comes back still ends the wait at the configured delay`() = runTest {
        val waiter = DropWaiter(testScheduler)
        val network = NetworkHandover(waiter).apply { updateRoute("wifi", true) }
        var waited: Long = -1

        val job = launch { network.awaitRetry(attemptEpoch = 0L, regularDelay = 60.seconds); waited = currentTime }

        advanceTimeBy(5.seconds)
        network.updateRoute(null, false)
        advanceTimeBy(120.seconds)
        job.join()

        // 离线不会永远挂着：这一次配置的 60s 等满就返回，交给重试策略记账
        assertEquals(60_000L, waited)
    }

    @Test
    fun `a real route change still retries after the short stabilize window`() = runTest {
        val waiter = DropWaiter(testScheduler)
        val network = NetworkHandover(waiter).apply { updateRoute("wifi", true) }
        var waited: Long = -1

        val job = launch { network.awaitRetry(attemptEpoch = 0L, regularDelay = 60.seconds); waited = currentTime }

        advanceTimeBy(5.seconds)
        // 换成另一个可用网络：这是换代，不是掉线
        network.updateRoute("cell", true)
        job.join()

        assertEquals(5_000L + NetworkHandover.STABILIZE_MILLIS, waited)
    }
}

/** 用虚拟时间的等待原语：时钟直接读 runTest 的调度器，不 sleep 真线程。 */
private class DropWaiter(private val scheduler: TestCoroutineScheduler) : RouteWaiter {

    override suspend fun awaitAvailable(
        state: StateFlow<NetworkRouteState>,
        timeoutMillis: Long,
    ): Boolean {
        if (timeoutMillis <= 0L) return state.value.available
        return withTimeoutOrNull(timeoutMillis) {
            state.first { it.available }
            true
        } ?: false
    }

    override suspend fun sleep(millis: Long) {
        if (millis > 0L) kotlinx.coroutines.delay(millis)
    }

    override fun nowMillis(): Long = scheduler.currentTime
}
