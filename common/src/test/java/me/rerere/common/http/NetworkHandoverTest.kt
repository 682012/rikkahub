package me.rerere.common.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 纯虚拟时间的 [NetworkHandover] 测试：不依赖 kotlinx-coroutines-test，也不 sleep。
 *
 * 时间只在 [VirtualTimeWaiter] 里前进，断言因此是确定性的。
 */
class NetworkHandoverTest {

    @Test
    fun `initial state is available with epoch zero`() {
        val handover = NetworkHandover()

        assertEquals(0L, handover.state.value.epoch)
        assertTrue(handover.state.value.available)
    }

    @Test
    fun `repeated callbacks for the same route never bump epoch`() {
        val handover = NetworkHandover()

        handover.updateRoute("wifi", true)
        handover.updateRoute("wifi", true)
        handover.updateRoute("wifi", true)

        assertEquals(0L, handover.state.value.epoch)
        assertTrue(handover.state.value.available)
    }

    @Test
    fun `route switch bumps epoch once per switch and keeps no history`() {
        val handover = NetworkHandover()

        handover.updateRoute("wifi", true)
        handover.updateRoute("cell", true)
        assertEquals(1L, handover.state.value.epoch)

        handover.updateRoute("cell", true)
        assertEquals(1L, handover.state.value.epoch)

        handover.updateRoute("wifi", true)
        assertEquals(2L, handover.state.value.epoch)

        handover.updateRoute(null, false)
        handover.updateRoute("wifi", true)
        assertEquals(3L, handover.state.value.epoch)
    }

    @Test
    fun `old to null to new counts exactly one generation`() {
        val handover = NetworkHandover()

        handover.updateRoute("wifi", true)
        handover.updateRoute(null, false)
        assertEquals(0L, handover.state.value.epoch)
        assertFalse(handover.state.value.available)

        // 重复的掉线回调不再变化
        handover.updateRoute(null, false)
        assertEquals(0L, handover.state.value.epoch)

        handover.updateRoute("cell", true)
        assertEquals(1L, handover.state.value.epoch)
        assertTrue(handover.state.value.available)
    }

    @Test
    fun `await usable returns immediately when already available`() = runBlocking {
        val waiter = VirtualTimeWaiter()
        val handover = NetworkHandover(waiter)

        assertTrue(handover.awaitUsable(120.seconds.inWholeMilliseconds))

        assertEquals(0, waiter.awaitTimeouts.size)
        assertEquals(0L, waiter.clock)
    }

    @Test
    fun `await usable wakes early when network recovers before timeout`() = runBlocking {
        val waiter = VirtualTimeWaiter()
        val handover = NetworkHandover(waiter)
        handover.updateRoute(null, false)

        var usable = false
        val job = launch { usable = handover.awaitUsable(120_000L) }
        waiter.awaitWaitStarted()
        assertEquals(listOf(120_000L), waiter.awaitTimeouts)

        handover.updateRoute("cell", true)
        waiter.completeWait(recovered = true, advanceMillis = 3_000L)
        job.join()

        assertTrue(usable)
        assertEquals(3_000L, waiter.clock)
        assertEquals(0, waiter.sleeps.size)
    }

    @Test
    fun `await usable times out on a bounded wait`() = runBlocking {
        val waiter = VirtualTimeWaiter()
        val handover = NetworkHandover(waiter)
        handover.updateRoute(null, false)

        var usable = true
        val job = launch { usable = handover.awaitUsable(45_000L) }
        waiter.awaitWaitStarted()
        assertEquals(listOf(45_000L), waiter.awaitTimeouts)

        waiter.completeWait(recovered = false, advanceMillis = 45_000L)
        job.join()

        assertFalse(usable)
        assertEquals(45_000L, waiter.clock)
    }

    @Test
    fun `await usable is cancellable`() = runBlocking {
        val waiter = VirtualTimeWaiter()
        val handover = NetworkHandover(waiter)
        handover.updateRoute(null, false)

        val job = launch { handover.awaitUsable(120_000L) }
        waiter.awaitWaitStarted()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0L, waiter.clock)
    }

    @Test
    fun `await retry honors regular delay when generation is unchanged`() = runBlocking {
        val waiter = VirtualTimeWaiter()
        val handover = NetworkHandover(waiter)

        val waited = handover.awaitRetry(0L, 2_000.milliseconds)

        assertEquals(2_000L, waited.inWholeMilliseconds)
        assertEquals(listOf(2_000L), waiter.sleeps)
    }

    @Test
    fun `await retry stabilizes briefly when generation changed and usable`() = runBlocking {
        val waiter = VirtualTimeWaiter()
        val handover = NetworkHandover(waiter)
        handover.updateRoute("wifi", true)
        handover.updateRoute("cell", true)

        val waited = handover.awaitRetry(0L, 5.seconds)

        // 换网后只等稳定期，不按原退避等满
        assertEquals(750L, waited.inWholeMilliseconds)
        assertEquals(listOf(750L), waiter.sleeps)
        assertEquals(750L, waiter.clock)
    }

    @Test
    fun `await retry waits for recovery then stabilizes`() = runBlocking {
        val waiter = VirtualTimeWaiter()
        val handover = NetworkHandover(waiter)
        handover.updateRoute("wifi", true)
        handover.updateRoute(null, false)

        var waited: Duration? = null
        val job = launch { waited = handover.awaitRetry(0L, 1.seconds) }
        waiter.awaitWaitStarted()
        assertEquals(listOf(120_000L), waiter.awaitTimeouts)

        handover.updateRoute("cell", true)
        waiter.completeWait(recovered = true, advanceMillis = 3_000L)
        job.join()

        val actual = requireNotNull(waited).inWholeMilliseconds
        assertEquals(3_750L, actual)
        assertEquals(3_750L, waiter.clock)
    }

    @Test
    fun `await retry keeps the offline wait bounded`() = runBlocking {
        val waiter = VirtualTimeWaiter()
        val handover = NetworkHandover(waiter)
        handover.updateRoute(null, false)

        var waited: Duration? = null
        val job = launch { waited = handover.awaitRetry(0L, 1.seconds) }
        waiter.awaitWaitStarted()

        waiter.completeWait(recovered = false, advanceMillis = 120_000L)
        job.join()

        val actual = requireNotNull(waited).inWholeMilliseconds
        assertEquals(120_000L, actual)
        assertTrue(waiter.sleeps.isEmpty())
        assertTrue("offline wait must stay under 120s + 750ms", waiter.clock <= 120_750L)
    }

    /**
     * 只按虚拟时间推进的等待原语：sleep 立即返回并推时钟，awaitAvailable 等测试侧唤醒。
     */
    private class VirtualTimeWaiter : RouteWaiter {
        var clock: Long = 0L
            private set

        val sleeps = mutableListOf<Long>()
        val awaitTimeouts = mutableListOf<Long>()

        private var pending: CompletableDeferred<Boolean>? = null

        override fun nowMillis(): Long = clock

        override suspend fun sleep(millis: Long) {
            sleeps.add(millis)
            if (millis > 0L) {
                clock += millis
            }
        }

        override suspend fun awaitAvailable(
            state: StateFlow<NetworkRouteState>,
            timeoutMillis: Long,
        ): Boolean {
            awaitTimeouts.add(timeoutMillis)
            val deferred = CompletableDeferred<Boolean>()
            pending = deferred
            return deferred.await()
        }

        /** 让出执行权直到有协程真的挂在 awaitAvailable 上（没有真实等待）。 */
        suspend fun awaitWaitStarted() {
            var spins = 0
            while (pending == null && spins < 100) {
                yield()
                spins++
            }
        }

        fun completeWait(recovered: Boolean, advanceMillis: Long) {
            clock += advanceMillis
            val deferred = pending
            pending = null
            deferred?.complete(recovered)
        }
    }
}
