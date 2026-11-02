package me.rerere.ai.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ModelRequestLimiterTest {
    private fun model(limit: Int?, id: Uuid = Uuid.random()) = Model(
        modelId = "chat",
        displayName = "Chat",
        id = id,
        maxConcurrentRequests = limit,
    )

    @Test
    fun `normalize keeps null and clamps illegal values into 1 to 64`() {
        assertNull(normalizeRequestLimit(null))
        assertEquals(
            listOf(1, 1, 1, 2, 64, 64, 64),
            listOf(0, -7, 1, 2, 64, 1000, Int.MAX_VALUE).map { normalizeRequestLimit(it) },
        )
    }

    @Test
    fun `same model shares one cap and peak never exceeds it`() = runTest {
        val limiter = ModelRequestLimiter()
        val shared = model(2)
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        var done = 0

        val jobs = (1..5).map {
            launch {
                limiter.withPermit(shared) {
                    active++
                    peak = maxOf(peak, active)
                    gate.await()
                    active--
                    done++
                }
            }
        }

        advanceUntilIdle()
        assertEquals(2, active)
        assertEquals(2, limiter.inFlightCount(shared.id))
        assertEquals(3, limiter.waitingCount(shared.id))

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, peak)
        assertEquals(5, done)
        assertEquals(0, limiter.inFlightCount(shared.id))
        assertEquals(0, limiter.waitingCount(shared.id))
        // 空桶会被回收，晚到请求不会复用过期限额
        assertEquals(0, limiter.trackedModelCount())
        jobs.forEach { assertTrue(it.isCompleted) }
    }

    @Test
    fun `new requests cannot barge ahead of already queued calls`() = runTest {
        val target = model(1)
        val limiter = ModelRequestLimiter()
        val first = limiter.acquire(target)
        val order = mutableListOf<Int>()
        repeat(3) { index -> launch { limiter.withPermit(target) { order += index } } }
        advanceUntilIdle()
        first.release()
        // Runs synchronously until suspension, before previously woken jobs are dispatched.
        launch(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit(target) { order += 3 }
        }
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2, 3), order)
        assertEquals(0, limiter.trackedModelCount())
    }

    @Test
    fun `already cancelled caller never starts a non suspending API body`() = runTest {
        val limiter = ModelRequestLimiter()
        val target = model(1)
        var invoked = false
        launch {
            currentCoroutineContext().cancel()
            runCatching { limiter.withPermit(target) { invoked = true } }
        }
        advanceUntilIdle()
        assertTrue(!invoked)
        assertEquals(0, limiter.trackedModelCount())
    }

    @Test
    fun `different models keep independent caps`() = runTest {
        val limiter = ModelRequestLimiter()
        val first = model(1)
        val second = model(1)
        val gate = CompletableDeferred<Unit>()
        var active = 0

        val jobs = listOf(first, first, second, second).map { target ->
            launch {
                limiter.withPermit(target) {
                    active++
                    gate.await()
                    active--
                }
            }
        }

        advanceUntilIdle()
        assertEquals(2, active)
        assertEquals(1, limiter.inFlightCount(first.id))
        assertEquals(1, limiter.inFlightCount(second.id))
        assertEquals(1, limiter.waitingCount(first.id))
        assertEquals(1, limiter.waitingCount(second.id))

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, limiter.trackedModelCount())
        assertEquals(0, active)
        jobs.forEach { assertTrue(it.isCompleted) }
    }

    @Test
    fun `null limit adds no cap but requests are still counted`() = runTest {
        var limit: Int? = null
        val target = model(null)
        val limiter = ModelRequestLimiter { limit }
        val gate = CompletableDeferred<Unit>()
        var active = 0

        val running = (1..5).map {
            launch {
                limiter.withPermit(target) {
                    active++
                    gate.await()
                    active--
                }
            }
        }

        advanceUntilIdle()
        assertEquals(5, active)
        assertEquals(5, limiter.inFlightCount(target.id))

        // 限额变成 2 之后，新请求要等在途数量降到 2 以下
        limit = 2
        limiter.notifyChanged()
        val late = launch { limiter.withPermit(target) { } }
        advanceUntilIdle()
        assertTrue(late.isActive)
        assertEquals(5, limiter.inFlightCount(target.id))

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, limiter.inFlightCount(target.id))
        assertTrue(late.isCompleted)
        running.forEach { assertTrue(it.isCompleted) }
    }

    @Test
    fun `shrinking the cap never kills running requests and blocks new ones`() = runTest {
        var limit: Int? = 4
        val target = model(null)
        val limiter = ModelRequestLimiter { limit }
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0

        val running = (1..6).map {
            launch {
                limiter.withPermit(target) {
                    active++
                    peak = maxOf(peak, active)
                    gate.await()
                    active--
                }
            }
        }

        advanceUntilIdle()
        assertEquals(4, active)
        assertEquals(4, limiter.inFlightCount(target.id))
        assertEquals(2, limiter.waitingCount(target.id))

        // 调小限额：已运行的 4 个不被打断，排队的不会被放进
        limit = 1
        limiter.notifyChanged()
        advanceUntilIdle()
        assertEquals(4, active)
        assertEquals(4, limiter.inFlightCount(target.id))

        // 新请求也进不来
        val late = launch { limiter.withPermit(target) { } }
        advanceUntilIdle()
        assertTrue(late.isActive)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, limiter.inFlightCount(target.id))
        assertEquals(0, active)
        assertEquals(4, peak)
        assertTrue(late.isCompleted)
        running.forEach { assertTrue(it.isCompleted) }
    }

    @Test
    fun `raising the cap wakes queued requests`() = runTest {
        var limit: Int? = 1
        val target = model(null)
        val limiter = ModelRequestLimiter { limit }
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0

        val jobs = (1..3).map {
            launch {
                limiter.withPermit(target) {
                    active++
                    peak = maxOf(peak, active)
                    gate.await()
                    active--
                }
            }
        }

        advanceUntilIdle()
        assertEquals(1, active)
        assertEquals(2, limiter.waitingCount(target.id))

        limit = 3
        limiter.notifyChanged()
        advanceUntilIdle()
        assertEquals(3, active)
        assertEquals(3, limiter.inFlightCount(target.id))
        assertEquals(0, limiter.waitingCount(target.id))

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, peak)
        jobs.forEach { assertTrue(it.isCompleted) }
    }

    @Test
    fun `old model snapshot follows the latest settings limit of the same id`() = runTest {
        val id = Uuid.random()
        val snapshot = model(1, id)
        val limits = LiveModelLimits()
        val limiter = ModelRequestLimiter(limits::limitFor)
        val gate = CompletableDeferred<Unit>()
        var active = 0

        // Settings 里同 id 的新副本放宽到 3，手里的旧快照仍然是 1
        limits.update(listOf(model(3, id)))
        val jobs = (1..3).map {
            launch {
                limiter.withPermit(snapshot) {
                    active++
                    gate.await()
                    active--
                }
            }
        }

        advanceUntilIdle()
        assertEquals(3, active)
        assertEquals(3, limiter.inFlightCount(id))

        // Settings 里删掉这个模型后，回落到快照自带的值：已在跑的不受影响
        limits.update(emptyList())
        limiter.notifyChanged()
        advanceUntilIdle()
        assertEquals(3, active)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, limiter.inFlightCount(id))
        assertEquals(0, limiter.trackedModelCount())
        jobs.forEach { assertTrue(it.isCompleted) }
    }

    @Test
    fun `cancelling a queued request frees its slot bookkeeping`() = runTest {
        val target = model(1)
        val limiter = ModelRequestLimiter()
        val gate = CompletableDeferred<Unit>()

        val holder = launch { limiter.withPermit(target) { gate.await() } }
        advanceUntilIdle()
        val queued = launch { limiter.withPermit(target) { } }
        advanceUntilIdle()
        assertEquals(1, limiter.waitingCount(target.id))

        queued.cancel()
        advanceUntilIdle()
        assertEquals(0, limiter.waitingCount(target.id))
        assertEquals(1, limiter.inFlightCount(target.id))

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, limiter.inFlightCount(target.id))
        assertEquals(0, limiter.trackedModelCount())
        assertTrue(holder.isCompleted)
    }

    @Test
    fun `cancelling a running request releases the slot for the next one`() = runTest {
        val target = model(1)
        val limiter = ModelRequestLimiter()
        val gate = CompletableDeferred<Unit>()
        var secondRan = false

        val holder = launch { limiter.withPermit(target) { gate.await() } }
        advanceUntilIdle()
        val waiting = launch { limiter.withPermit(target) { secondRan = true } }
        advanceUntilIdle()
        assertEquals(1, limiter.waitingCount(target.id))

        holder.cancel()
        advanceUntilIdle()
        assertTrue(secondRan)
        assertEquals(0, limiter.inFlightCount(target.id))
        assertEquals(0, limiter.trackedModelCount())
        assertTrue(waiting.isCompleted)
    }

    @Test
    fun `exception releases the slot and a retry can acquire again`() = runTest {
        val target = model(1)
        val limiter = ModelRequestLimiter()
        var attempts = 0

        val failure = runCatching {
            limiter.withPermit(target) {
                attempts++
                error("boom")
            }
        }
        assertTrue(failure.isFailure)
        assertEquals(0, limiter.inFlightCount(target.id))
        assertEquals(0, limiter.trackedModelCount())

        limiter.withPermit(target) { attempts++ }
        assertEquals(2, attempts)
        assertEquals(0, limiter.inFlightCount(target.id))
    }

    @Test
    fun `illegal stored values are clamped so they never flood the provider`() = runTest {
        // 0 按 1 处理
        val zero = model(0)
        val zeroLimiter = ModelRequestLimiter()
        val zeroGate = CompletableDeferred<Unit>()
        var zeroActive = 0

        val zeroJobs = (1..2).map {
            launch {
                zeroLimiter.withPermit(zero) {
                    zeroActive++
                    zeroGate.await()
                    zeroActive--
                }
            }
        }
        advanceUntilIdle()
        assertEquals(1, zeroActive)
        assertEquals(1, zeroLimiter.waitingCount(zero.id))

        zeroGate.complete(Unit)
        advanceUntilIdle()
        zeroJobs.forEach { assertTrue(it.isCompleted) }

        // 超大值收敛到 64
        val huge = model(10_000)
        val hugeLimiter = ModelRequestLimiter()
        val hugeGate = CompletableDeferred<Unit>()
        var hugeActive = 0
        var hugePeak = 0

        val hugeJobs = (1..70).map {
            launch {
                hugeLimiter.withPermit(huge) {
                    hugeActive++
                    hugePeak = maxOf(hugePeak, hugeActive)
                    hugeGate.await()
                    hugeActive--
                }
            }
        }
        advanceUntilIdle()
        assertEquals(MAX_MODEL_CONCURRENT_REQUESTS, hugeActive)
        assertEquals(70 - MAX_MODEL_CONCURRENT_REQUESTS, hugeLimiter.waitingCount(huge.id))

        hugeGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(MAX_MODEL_CONCURRENT_REQUESTS, hugePeak)
        hugeJobs.forEach { assertTrue(it.isCompleted) }
    }
}
