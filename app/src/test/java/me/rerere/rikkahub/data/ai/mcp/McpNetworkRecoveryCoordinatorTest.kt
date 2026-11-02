package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络恢复的决策层（r3）：基线不误伤、重复不叠加、离线不空转、换网只恢复一次。
 *
 * 这些用例直接跑生产用的 [NetworkRecoveryCoordinator]，
 * 而不是「源码里有某个字符串」那种假测试。
 */
class McpNetworkRecoveryCoordinatorTest {

    @Test
    fun `first state is only a baseline so already connected servers are not touched`() {
        val coordinator = NetworkRecoveryCoordinator()

        // 初始化时的第一条状态（默认 NetworkRouteState(0, true)）不能触发任何重连
        assertEquals(NetworkRecoveryDirective.None, coordinator.onRouteState(epoch = 0L, available = true))
        // 即使 debounce 到点也不允许恢复，因为压根没排过
        assertFalse(coordinator.onDebounceElapsed(0L))
        assertTrue(coordinator.isAvailable())
    }

    @Test
    fun `baseline offline only marks offline without scheduling a recovery`() {
        val coordinator = NetworkRecoveryCoordinator()

        assertEquals(NetworkRecoveryDirective.MarkOffline, coordinator.onRouteState(epoch = 0L, available = false))
        assertFalse(coordinator.isAvailable())
        assertFalse(coordinator.onDebounceElapsed(0L))
    }

    @Test
    fun `identical repeated callbacks are dropped`() {
        val coordinator = NetworkRecoveryCoordinator()

        assertEquals(NetworkRecoveryDirective.None, coordinator.onRouteState(3L, true))
        repeat(20) {
            assertEquals(NetworkRecoveryDirective.None, coordinator.onRouteState(3L, true))
        }
        // 掉线只播一次 MarkOffline，后续重复回调彻底丢弃
        assertEquals(NetworkRecoveryDirective.MarkOffline, coordinator.onRouteState(3L, false))
        repeat(20) {
            assertEquals(NetworkRecoveryDirective.None, coordinator.onRouteState(3L, false))
        }
        assertFalse(coordinator.onDebounceElapsed(3L))
    }

    @Test
    fun `epoch change while available recovers exactly once`() {
        val coordinator = NetworkRecoveryCoordinator()
        coordinator.onRouteState(1L, true)

        assertEquals(NetworkRecoveryDirective.RestartDebounce(2L), coordinator.onRouteState(2L, true))
        assertTrue(coordinator.onDebounceElapsed(2L))
        // 同一个 epoch 的 debounce 只能被消费一次，避免一个信号触发多次重连
        assertFalse(coordinator.onDebounceElapsed(2L))
    }

    @Test
    fun `newer epoch during the debounce window supersedes the older one`() {
        val coordinator = NetworkRecoveryCoordinator()
        coordinator.onRouteState(1L, true)

        assertEquals(NetworkRecoveryDirective.RestartDebounce(2L), coordinator.onRouteState(2L, true))
        assertEquals(NetworkRecoveryDirective.RestartDebounce(3L), coordinator.onRouteState(3L, true))
        assertEquals(NetworkRecoveryDirective.RestartDebounce(4L), coordinator.onRouteState(4L, true))

        // 只有最新的一次能落地：前两个已被取代
        assertFalse(coordinator.onDebounceElapsed(2L))
        assertFalse(coordinator.onDebounceElapsed(3L))
        assertTrue(coordinator.onDebounceElapsed(4L))
        assertFalse(coordinator.onDebounceElapsed(4L))
    }

    @Test
    fun `going offline cancels a pending recovery and offline stays silent`() {
        val coordinator = NetworkRecoveryCoordinator()
        coordinator.onRouteState(1L, true)
        assertEquals(NetworkRecoveryDirective.RestartDebounce(2L), coordinator.onRouteState(2L, true))

        // debounce 还没到点就掉线：这次恢复必须作废，不能在没网的时候去连
        assertEquals(NetworkRecoveryDirective.MarkOffline, coordinator.onRouteState(2L, false))
        assertFalse(coordinator.onDebounceElapsed(2L))

        // 离线期间无论来多少条（哪怕 epoch 变了）都只标记离线
        assertEquals(NetworkRecoveryDirective.MarkOffline, coordinator.onRouteState(3L, false))
        assertFalse(coordinator.onDebounceElapsed(3L))
    }

    @Test
    fun `recovery after offline requires the network to be available again`() {
        val coordinator = NetworkRecoveryCoordinator()
        coordinator.onRouteState(5L, true)
        coordinator.onRouteState(5L, false)
        assertFalse(coordinator.isAvailable())

        assertEquals(NetworkRecoveryDirective.RestartDebounce(6L), coordinator.onRouteState(6L, true))
        assertTrue(coordinator.isAvailable())
        assertTrue(coordinator.onDebounceElapsed(6L))
    }

    @Test
    fun `availability returning without an epoch bump still recovers`() {
        // 有些设备从飞行模式回来时路由标识没变，只翻转 available，不能因此永远不重连
        val coordinator = NetworkRecoveryCoordinator()
        coordinator.onRouteState(7L, true)
        assertEquals(NetworkRecoveryDirective.MarkOffline, coordinator.onRouteState(7L, false))

        assertEquals(NetworkRecoveryDirective.RestartDebounce(7L), coordinator.onRouteState(7L, true))
        assertTrue(coordinator.onDebounceElapsed(7L))
    }
}

/**
 * 把 coordinator 和真实的 debounce 任务拼起来跑（虚拟时间），验证「重连风暴」这类时序问题。
 *
 * [Harness.dispatch] 复刻了 McpManager 里那段分发逻辑：只有一个 debounce 任务，
 * 新的 RestartDebounce 会取消旧的，到点还要通过 onDebounceElapsed 才真正恢复。
 */
class McpNetworkRecoveryDispatchTest {
    private class Harness {
        val coordinator = NetworkRecoveryCoordinator()
        val recoveries = mutableListOf<Long>()
        private var debounceJob: Job? = null

        fun dispatch(scope: CoroutineScope, epoch: Long, available: Boolean) {
            when (val directive = coordinator.onRouteState(epoch, available)) {
                NetworkRecoveryDirective.None -> Unit
                NetworkRecoveryDirective.MarkOffline -> {
                    debounceJob?.cancel()
                    debounceJob = null
                }

                is NetworkRecoveryDirective.RestartDebounce -> {
                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        delay(NETWORK_RECOVERY_DEBOUNCE_MS)
                        if (!coordinator.onDebounceElapsed(directive.epoch)) return@launch
                        recoveries += directive.epoch
                    }
                }
            }
        }

        fun clearRecoveries() {
            recoveries.clear()
        }
    }

    @Test
    fun `flapping network produces one recovery for the latest epoch`() = runTest {
        val harness = Harness()
        harness.dispatch(this, 0L, true) // 基线
        harness.dispatch(this, 1L, true)
        harness.dispatch(this, 2L, true)
        harness.dispatch(this, 3L, true)
        harness.dispatch(this, 3L, true) // 重复回调
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(3L), harness.recoveries)
    }

    @Test
    fun `repeated identical callbacks never accumulate extra recoveries`() = runTest {
        val harness = Harness()
        harness.dispatch(this, 0L, true)
        harness.dispatch(this, 1L, true)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1L), harness.recoveries)

        harness.clearRecoveries()
        repeat(10) { harness.dispatch(this, 1L, true) }
        testScheduler.advanceUntilIdle()
        assertTrue(harness.recoveries.isEmpty())
    }

    @Test
    fun `offline then online recovers once and nothing happens while offline`() = runTest {
        val harness = Harness()
        harness.dispatch(this, 0L, true)
        harness.dispatch(this, 1L, false)
        harness.dispatch(this, 2L, false)
        testScheduler.advanceUntilIdle()
        assertTrue(harness.recoveries.isEmpty())

        harness.dispatch(this, 3L, true)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(3L), harness.recoveries)
    }
}
