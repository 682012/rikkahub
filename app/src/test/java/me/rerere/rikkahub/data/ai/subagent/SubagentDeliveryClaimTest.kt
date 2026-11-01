package me.rerere.rikkahub.data.ai.subagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首屏交付是"原子 claim"：同一 task 多个并发 wait 只能有一份首屏，其余只拿 status；
 * 而且 claim 必须发生在读成功之后，读失败不能把交付权吃掉（否则结果就被吞了）。
 */
class SubagentDeliveryClaimTest {
    @Test
    fun `claim succeeds once and every later claim fails`() {
        val registry = SubagentDeliveryRegistry()

        assertTrue(registry.claimDelivery("t1"))
        assertFalse(registry.claimDelivery("t1"))
        assertFalse(registry.claimDelivery("t1"))
        assertTrue(registry.isDelivered("t1"))

        registry.forget("t1")
        assertFalse(registry.isDelivered("t1"))
        assertTrue(registry.claimDelivery("t1"))
    }

    @Test
    fun `concurrent claims on the same task yield exactly one winner`() {
        val registry = SubagentDeliveryRegistry()
        val winners = AtomicInteger(0)
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    if (registry.claimDelivery("same-task")) winners.incrementAndGet()
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, winners.get())
    }

    @Test
    fun `claim is per task so independent tasks all win`() {
        val registry = SubagentDeliveryRegistry()
        assertTrue(registry.claimDelivery("a"))
        assertTrue(registry.claimDelivery("b"))
        assertFalse(registry.claimDelivery("a"))
    }

    @Test
    fun `a failing read does not consume the delivery claim`() {
        val registry = SubagentDeliveryRegistry()

        val failed = runCatching {
            claimFirstScreenAfterRead(registry, "t1") { error("disk read failed") }
        }
        assertTrue(failed.isFailure)
        assertFalse("read failure must not consume the claim", registry.isDelivered("t1"))

        val nullRead = claimFirstScreenAfterRead<String>(registry, "t1") { null }
        assertNull(nullRead)
        assertFalse(registry.isDelivered("t1"))

        // 修好之后仍然能拿到首屏。
        assertEquals("body", claimFirstScreenAfterRead(registry, "t1") { "body" })
        assertTrue(registry.isDelivered("t1"))
    }

    @Test
    fun `only the first successful read gets the first screen`() {
        val registry = SubagentDeliveryRegistry()
        var reads = 0
        val read = { reads++; "screen-$reads" }

        assertEquals("screen-1", claimFirstScreenAfterRead(registry, "t1", read))
        // 第二次读能读到内容，但 claim 已经没了 → 只回 status。
        assertNull(claimFirstScreenAfterRead(registry, "t1", read))
        assertEquals(2, reads)
        assertNotNull(registry.isDelivered("t1"))
    }
}
