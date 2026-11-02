package me.rerere.common.http

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class NetworkHandoverSchedulerTest {
    @Test fun `vpn change after retry started wakes early rather than waiting sixty seconds`() = runTest {
        val state = NetworkHandover().apply { updateRoute("wifi", true) }
        val wait = async { state.awaitRetry(0, 60.seconds) }
        runCurrent()
        advanceTimeBy(2000)
        state.updateRoute("vpn", true)
        runCurrent()
        advanceTimeBy(750)
        runCurrent()
        assertTrue(wait.isCompleted)
        wait.await()
        assertEquals(2750L, currentTime)
    }
    @Test fun `offline timeout is finite with real coroutine waiter`() = runTest {
        val state = NetworkHandover().apply { updateRoute(null, false) }
        assertFalse(state.awaitUsable(120_000))
        assertEquals(120_000L, currentTime)
    }
    @Test fun `cancelling the retry wait releases timer and subscription`() = runTest {
        val state = NetworkHandover().apply { updateRoute("wifi", true) }
        val job = launch { state.awaitRetry(0, 60.seconds) }
        runCurrent(); job.cancelAndJoin()
        state.updateRoute("vpn", true)
        assertTrue(job.isCancelled)
        assertEquals(0L, currentTime)
    }
    @Test fun `same handle recovering from suspended connection still starts a fresh generation`() {
        val state = NetworkHandover().apply { updateRoute("wifi", true) }
        state.updateRoute("wifi", false)
        state.updateRoute("wifi", true)
        assertEquals(1L, state.state.value.epoch)
        repeat(3) { state.updateRoute("wifi", true) }
        assertEquals(1L, state.state.value.epoch)
    }
}
