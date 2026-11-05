package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * 许可池的三个坑：FIFO 不能乱、取消不能吞许可、许可发出后等待者才被取消不能泄漏。
 */
class SubagentPermitPoolTest {
    @Test
    fun limitIsNeverExceededAndCancellingAQueuedWaiterDoesNotConsumeAPermit() = runBlocking {
        val pool = SubagentPermitPool()
        val inFlight = Collections.synchronizedList(mutableListOf<Unit>())
        var peak = 0

        (1..6).map {
            launch {
                pool.acquire(2)
                synchronized(inFlight) {
                    inFlight.add(Unit)
                    peak = maxOf(peak, inFlight.size)
                }
                delay(20)
                synchronized(inFlight) { inFlight.removeAt(inFlight.size - 1) }
                pool.release()
            }
        }.forEach { it.join() }
        assertEquals(2, peak)

        pool.acquire(1)
        val reached = CompletableDeferred<Unit>()
        val queued = launch { reached.complete(Unit); pool.acquire(1) }
        reached.await()
        repeat(5) { yield() }
        queued.cancelAndJoin()
        // 排队时被取消的那个没有消耗许可：释放以后立刻能拿到
        pool.release()
        withTimeout(1_000) { pool.acquire(1) }
        pool.release()

        var overReleased = false
        try {
            pool.release()
        } catch (e: IllegalStateException) {
            overReleased = true
        }
        assertTrue("多释放一次必须报错，不能把计数减成负数", overReleased)
    }

    @Test
    fun permitsAreHandedOutInFifoOrder() = runBlocking {
        val pool = SubagentPermitPool()
        val order = Collections.synchronizedList(mutableListOf<Int>())
        pool.acquire(1)

        val waiters = (1..4).map { index ->
            launch {
                pool.acquire(1)
                order.add(index)
                pool.release()
            }
        }
        repeat(10) { yield() }
        assertEquals("所有人都还在排队", emptyList<Int>(), order.toList())

        pool.release()
        waiters.forEach { it.join() }
        assertEquals(listOf(1, 2, 3, 4), order.toList())
    }

    @Test
    fun aPermitGrantedToAWaiterCancelledBeforeResumingGoesToTheNextWaiter() = runBlocking {
        val pool = SubagentPermitPool()
        val paused = PausedDispatcher()
        pool.acquire(1)

        val first = launch(paused) { pool.acquire(1) }
        paused.drain() // 第一个等待者挂进队列
        val secondAcquired = CompletableDeferred<Unit>()
        val second = launch(paused) {
            pool.acquire(1)
            secondAcquired.complete(Unit)
            pool.release()
        }
        paused.drain() // 第二个排在第一个后面

        // 释放：许可已经记在第一个等待者名下，但它的恢复还在队列里没跑
        pool.release()
        // 正好在这个空档里取消它：许可不能跟着消失，必须转给下一个排队者
        first.cancel()
        paused.drain()

        withTimeout(1_000) { secondAcquired.await() }
        second.join()

        // 账本干净：许可已经回到池子里，没有泄漏也不会变负
        withTimeout(1_000) { pool.acquire(1) }
        pool.release()
        var overReleased = false
        try {
            pool.release()
        } catch (e: IllegalStateException) {
            overReleased = true
        }
        assertTrue("取消 + 转手之后计数不能变负", overReleased)
    }

    @Test
    fun theLimitFollowsTheMostRecentAcquire() = runBlocking {
        val pool = SubagentPermitPool()
        pool.acquire(1) // 上限 1，占着一个

        val waiterGot = CompletableDeferred<Unit>()
        val waiter = launch {
            pool.acquire(1)
            waiterGot.complete(Unit)
        }
        repeat(5) { yield() }
        assertFalse("旧上限 1 下必须排队", waiterGot.isCompleted)

        // 新的 acquire 把上限抬到 3：排在前面的等待者立刻放行，自己也还能拿一个
        pool.acquire(3)
        withTimeout(1_000) { waiterGot.await() }

        // active = 3 = limit：再来一个必须继续排队
        val blocked = withTimeoutOrNull(200) { pool.acquire(3) }
        assertNull("上限满额时必须排队", blocked)

        pool.release()
        pool.release()
        pool.release()
        withTimeout(1_000) { pool.acquire(3) }
        pool.release()

        // 上限跟着最近一次调用走：收到 1 就只放 1 个
        pool.acquire(1)
        val oneOnly = withTimeoutOrNull(200) { pool.acquire(1) }
        assertNull("limit 必须取最近一次 acquire 的值", oneOnly)
        pool.release()

        waiter.join()
    }

    /**
     * 真线程并发下的泄漏探针。
     *
     * 前面几条用例都是单线程手动泵，只覆盖了一种交错。这里让 48 个协程在 `Dispatchers.Default`
     * 上反复抢占/让出/释放，中途再随机取消掉三分之一：只要有一次「许可发出去了却没人接」，
     * 剩余协程就会永远排在队列里，最后那条「容量必须刚好回到 limit」的检查就会失败。
     */
    @Test
    fun concurrentAcquireReleaseAndCancellationNeitherLeaksNorOversubscribes() = runBlocking {
        val pool = SubagentPermitPool()
        val limit = 4
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val workers = (1..48).map {
            launch(Dispatchers.Default) {
                repeat(10) {
                    pool.acquire(limit)
                    try {
                        val now = inFlight.incrementAndGet()
                        peak.getAndUpdate { old -> maxOf(old, now) }
                        yield() // 真挂起点：取消会在里生效
                    } finally {
                        inFlight.decrementAndGet()
                        pool.release()
                    }
                }
            }
        }
        val canceller = launch(Dispatchers.Default) {
            delay(60)
            workers.filterIndexed { index, _ -> index % 3 == 0 }.forEach { it.cancel() }
        }
        withTimeout(30_000) {
            canceller.join()
            workers.forEach { it.join() }
        }

        assertTrue("并发数超过上限: ${peak.get()} > $limit", peak.get() <= limit)
        // 泄漏探针：一轮跑完，池子必须刚好还能放出 limit 个
        withTimeout(5_000) { repeat(limit) { pool.acquire(limit) } }
        val blocked = withTimeoutOrNull(300) { pool.acquire(limit) }
        assertNull("许可泄漏了：容量没有回到 $limit", blocked)
        repeat(limit) { pool.release() }

        var overReleased = false
        try {
            pool.release()
        } catch (e: IllegalStateException) {
            overReleased = true
        }
        assertTrue("取消 + 并发之后计数不能变负", overReleased)
    }
}

/** 手动泵的调度器：把恢复动作扣在队列里，用来制造「许可已发出、等待者还没恢复」的窗口。 */
private class PausedDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(queued) { queued.addLast(block) }
    }

    fun drain() {
        while (true) {
            val next = synchronized(queued) { if (queued.isEmpty()) null else queued.removeFirst() } ?: return
            next.run()
        }
    }
}
