package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test

class SubagentPermitPoolTest {
    @Test fun `limit is never exceeded and cancellation does not consume a permit`() {
        runBlocking {
        val pool = SubagentPermitPool()
        var active = 0
        var peak = 0
        coroutineScope {
            repeat(6) {
                launch {
                    pool.acquire(2)
                    synchronized(this@SubagentPermitPoolTest) { active++; peak = maxOf(peak, active) }
                    delay(20)
                    synchronized(this@SubagentPermitPoolTest) { active-- }
                    pool.release()
                }
            }
        }
        assertEquals(2, peak)

        pool.acquire(1)
        val waiter = launch { pool.acquire(1) }
        yield()
        waiter.cancelAndJoin()
        pool.release()
        withTimeout(500) { pool.acquire(1) }
        pool.release()
        }
    }
}
