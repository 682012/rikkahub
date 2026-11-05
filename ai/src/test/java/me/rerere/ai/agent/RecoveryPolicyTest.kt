package me.rerere.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败分类与重试预算。纯 JVM，不碰网络，不依赖协程。
 *
 * 断言的重点是「哪一类失败会永久停」：断网、换网各自有一份独立预算，
 * 用户的 maxRetries 只统计真正失败的尝试；用户按停止永远不复活；工具/图片永不重放。
 */
class RecoveryPolicyTest {

    // ---- 分类 ----

    @Test
    fun `user cancellation outranks every network signal`() {
        val kind = RecoveryPolicy.classify(
            FailureFacts(cancelled = true, notSent = true, routeRetired = true, transport = true),
        )
        assertEquals(FailureKind.USER_STOPPED, kind)
        assertFalse(RecoveryPolicy.isRetryable(kind))
        assertFalse(RecoveryPolicy.waitsForNetwork(kind))
        assertFalse(RecoveryPolicy.mayReplayText(kind, hasCommittedSideEffects = false))
    }

    @Test
    fun `request that never left the device is offline, not a real failure`() {
        val kind = RecoveryPolicy.classify(FailureFacts(notSent = true))
        assertEquals(FailureKind.OFFLINE, kind)
        assertTrue(RecoveryPolicy.waitsForNetwork(kind))
        assertTrue(RecoveryPolicy.mayReplayText(kind, hasCommittedSideEffects = false))
    }

    @Test
    fun `route retired mid request is replayable text but never a committed side effect`() {
        val kind = RecoveryPolicy.classify(FailureFacts(routeRetired = true))
        assertEquals(FailureKind.ROUTE_RETIRED, kind)
        assertTrue(RecoveryPolicy.mayReplayText(kind, hasCommittedSideEffects = false))
        // 工具已经执行、图片已经生成：重放只会再花一次钱
        assertFalse(RecoveryPolicy.mayReplayText(kind, hasCommittedSideEffects = true))
    }

    @Test
    fun `truncated stream is not a success and stays retryable`() {
        val kind = RecoveryPolicy.classify(FailureFacts(truncatedStream = true))
        assertEquals(FailureKind.TRUNCATED, kind)
        assertTrue(RecoveryPolicy.isRetryable(kind))
    }

    @Test
    fun `http status separates transient server errors from terminal rejections`() {
        assertEquals(FailureKind.SERVER, RecoveryPolicy.classify(FailureFacts(httpStatus = 500, transport = false)))
        assertEquals(FailureKind.SERVER, RecoveryPolicy.classify(FailureFacts(httpStatus = 429, transport = false)))
        assertEquals(FailureKind.SERVER, RecoveryPolicy.classify(FailureFacts(httpStatus = 408, transport = false)))
        val terminal = RecoveryPolicy.classify(FailureFacts(httpStatus = 401, transport = false))
        assertEquals(FailureKind.TERMINAL, terminal)
        assertFalse(RecoveryPolicy.isRetryable(terminal))
        assertFalse(RecoveryPolicy.mayReplayText(terminal, hasCommittedSideEffects = false))
    }

    @Test
    fun `quota exhausted 429 is terminal even though it carries a retryable status`() {
        val quota = RecoveryPolicy.classify(
            FailureFacts(httpStatus = 429, quotaExhausted = true, transport = false)
        )
        assertEquals(FailureKind.TERMINAL, quota)
        assertFalse(RecoveryPolicy.isRetryable(quota))
    }

    @Test
    fun `transport failure is retryable and does not wait for a network event`() {
        val kind = RecoveryPolicy.classify(FailureFacts(transport = true))
        assertEquals(FailureKind.TRANSPORT, kind)
        assertTrue(RecoveryPolicy.isRetryable(kind))
        assertFalse(RecoveryPolicy.waitsForNetwork(kind))
    }

    // ---- 预算记账 ----

    @Test
    fun `real failures are bounded by maxRetries`() {
        val budget = RetryBudget(maxRetries = 3)
        assertEquals(true, budget.record(FailureKind.TRANSPORT))
        assertEquals(true, budget.record(FailureKind.TRANSPORT))
        assertEquals(true, budget.record(FailureKind.TRANSPORT))
        // 第 4 次不再给机会：3 次重试已经用完
        assertEquals(false, budget.record(FailureKind.TRANSPORT))
        assertEquals(3, budget.retries)
    }

    @Test
    fun `route handovers do not burn the retry budget`() {
        // 用户设了 1 次重试：换网作废的连接不该把它吃掉，否则切一下 Wi-Fi 就永久停
        val budget = RetryBudget(maxRetries = 1)
        repeat(4) {
            assertTrue("第 ${it + 1} 次换网仍应重连", budget.record(FailureKind.ROUTE_RETIRED))
        }
        assertEquals(0, budget.retries)
        assertEquals(4, budget.retired)
        // 真正失败的尝试仍然只有 1 次机会
        assertTrue(budget.record(FailureKind.TRANSPORT))
        assertFalse(budget.record(FailureKind.TRANSPORT))
    }

    @Test
    fun `route handovers stay bounded so a flapping route cannot replay forever`() {
        val budget = RetryBudget(maxRetries = 3)
        repeat(RecoveryPolicy.ROUTE_RETIRED_LIMIT) {
            assertTrue(budget.record(FailureKind.ROUTE_RETIRED))
        }
        assertFalse(budget.record(FailureKind.ROUTE_RETIRED))
    }

    @Test
    fun `offline waiting is bounded but independent from maxRetries`() {
        val budget = RetryBudget(maxRetries = 1)
        repeat(RecoveryPolicy.OFFLINE_WAIT_LIMIT) { assertEquals(true, budget.record(FailureKind.OFFLINE, epoch = 0L)) }
        assertEquals(0, budget.retries)
        // 一直没网：等待轮数到顶后仍然要结束，不能永远挂着生成中的状态
        assertFalse(budget.record(FailureKind.OFFLINE, epoch = 0L))
    }

    @Test
    fun `a new network generation restores the offline allowance`() {
        val budget = RetryBudget(maxRetries = 2)
        repeat(RecoveryPolicy.OFFLINE_WAIT_LIMIT) { budget.record(FailureKind.OFFLINE, epoch = 7L) }
        assertFalse(budget.record(FailureKind.OFFLINE, epoch = 7L))
        // 换了新网络：离线轮数重新计，用户不用因为上一代网络上等满就永久失败
        assertTrue(budget.record(FailureKind.OFFLINE, epoch = 8L))
        assertEquals(1, budget.unsent)
    }

    @Test
    fun `auto retry disabled never queues a wait`() {
        val budget = RetryBudget(maxRetries = 0)
        assertFalse(budget.record(FailureKind.OFFLINE, epoch = 0L))
        assertFalse(budget.record(FailureKind.ROUTE_RETIRED, epoch = 0L))
        assertFalse(budget.record(FailureKind.TRANSPORT))
    }

    @Test
    fun `user stop is never recorded as retryable`() {
        val budget = RetryBudget(maxRetries = 5)
        assertFalse(budget.record(FailureKind.USER_STOPPED))
        assertFalse(budget.record(FailureKind.TERMINAL))
        assertEquals(0, budget.retries)
    }

    @Test
    fun `the attempt shown to the ui keeps counting while the budget is untouched`() {
        val budget = RetryBudget(maxRetries = 3)
        // 从来没真正失败过：界面上仍然说「第 1 次重连」
        assertEquals(1, budget.attempt)
        budget.record(FailureKind.TRANSPORT)
        assertEquals(1, budget.attempt)
        budget.record(FailureKind.TRANSPORT)
        assertEquals(2, budget.attempt)
    }
}
