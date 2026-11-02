package me.rerere.rikkahub.data.ai.mcp

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重连策略的纯逻辑（r3）：准入（合并 / 禁用 / 删除 / 401）、预算（离线不烧、耗尽不停死）、
 * 以及工具调用失败后「绝不重放」的边界。
 *
 * 这些都跑生产代码里的函数本身，不需要连真实 MCP server。
 */
class McpReconnectAdmissionTest {

    private fun state(
        serverExists: Boolean = true,
        enabled: Boolean = true,
        nameValid: Boolean = true,
        needsAuthorization: Boolean = false,
        hasActiveReconnectJob: Boolean = false,
        networkAvailable: Boolean = true,
    ) = ReconnectGateState(
        serverExists = serverExists,
        enabled = enabled,
        nameValid = nameValid,
        needsAuthorization = needsAuthorization,
        hasActiveReconnectJob = hasActiveReconnectJob,
        networkAvailable = networkAvailable,
    )

    @Test
    fun `a healthy enabled server on a usable network is admitted`() {
        assertEquals(ReconnectAdmission.ACCEPT, admitReconnect(state()))
    }

    @Test
    fun `a second notification joins the running reconnect instead of opening another one`() {
        // 合并：onError + onClose 同时到达时最多一个 job
        assertEquals(ReconnectAdmission.JOIN_RUNNING, admitReconnect(state(hasActiveReconnectJob = true)))
        assertEquals(
            ReconnectAdmission.JOIN_RUNNING,
            admitReconnect(state(hasActiveReconnectJob = true, networkAvailable = false)),
        )
    }

    @Test
    fun `deleted or disabled servers are never revived`() {
        assertEquals(ReconnectAdmission.DROP_DELETED, admitReconnect(state(serverExists = false)))
        assertEquals(ReconnectAdmission.DROP_DISABLED, admitReconnect(state(enabled = false)))
        assertEquals(ReconnectAdmission.DROP_DISABLED, admitReconnect(state(nameValid = false)))
        // 禁用优先于离线停车：不能因为掉线把禁用服务器留在重连队列里
        assertEquals(
            ReconnectAdmission.DROP_DISABLED,
            admitReconnect(state(enabled = false, networkAvailable = false)),
        )
    }

    @Test
    fun `needs authorization never loops automatically`() {
        assertEquals(ReconnectAdmission.DROP_NEEDS_AUTH, admitReconnect(state(needsAuthorization = true)))
        assertEquals(
            ReconnectAdmission.DROP_NEEDS_AUTH,
            admitReconnect(state(needsAuthorization = true, hasActiveReconnectJob = true)),
        )
    }

    @Test
    fun `offline parks instead of burning the reconnect budget`() {
        assertEquals(ReconnectAdmission.PARK_OFFLINE, admitReconnect(state(networkAvailable = false)))
    }
}

class McpReconnectBudgetTest {

    @Test
    fun `ordinary failures back off exponentially and stop at five`() {
        val delays = (0 until 5).map { previous ->
            val plan = planReconnectAttempt(previousAttempts = previous, networkAvailable = true)
            assertFalse(plan.deferred)
            assertFalse(plan.budgetExhausted)
            assertEquals(previous + 1, plan.attempt)
            assertEquals(previous + 1, plan.attemptsAfter)
            plan.delayMillis
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), delays)
    }

    @Test
    fun `backoff is capped`() {
        val plan = planReconnectAttempt(previousAttempts = 7, networkAvailable = true, maxAttempts = 8)
        assertEquals(30_000L, plan.delayMillis)
    }

    @Test
    fun `being offline does not consume the retry budget`() {
        var attempts = 0
        repeat(50) {
            val plan = planReconnectAttempt(previousAttempts = attempts, networkAvailable = false)
            assertTrue(plan.deferred)
            assertEquals(0, plan.attemptsAfter)
            assertEquals(0L, plan.delayMillis)
            attempts = plan.attemptsAfter
        }
        assertEquals(0, attempts)

        // 已经用掉几次预算后掉线，也不能把它推得更远
        val parked = planReconnectAttempt(previousAttempts = 3, networkAvailable = false)
        assertTrue(parked.deferred)
        assertEquals(3, parked.attemptsAfter)
    }

    @Test
    fun `an exhausted budget parks without a permanent timer and can be reset by recovery`() {
        var attempts = 5
        repeat(10) {
            val plan = planReconnectAttempt(previousAttempts = attempts, networkAvailable = true)
            assertTrue(plan.budgetExhausted)
            assertTrue(plan.deferred)
            assertEquals(0L, plan.delayMillis)
            assertEquals(5, plan.attempt)
            // 保持耗尽态，避免慢速失败后又白送一整轮快速重试
            assertEquals(5, plan.attemptsAfter)
            attempts = plan.attemptsAfter
        }
    }

    @Test
    fun `network recovery resets the budget and retries immediately`() {
        // registry 在 onNetworkAvailable 里把 reconnectAttempt 归零
        val plan = planReconnectAttempt(previousAttempts = 0, networkAvailable = true)
        assertEquals(1, plan.attempt)
        assertEquals(1_000L, plan.delayMillis)
        assertFalse(plan.budgetExhausted)
    }

    @Test
    fun `a session that burned its budget recovers after the network comes back`() {
        var attempts = 0
        repeat(5) {
            attempts = planReconnectAttempt(attempts, networkAvailable = true).attemptsAfter
        }
        assertEquals(5, attempts)
        assertTrue(planReconnectAttempt(attempts, networkAvailable = true).budgetExhausted)

        // 网络恢复 -> 预算归零 -> 又能正常尝试
        attempts = 0
        val recovered = planReconnectAttempt(attempts, networkAvailable = true)
        assertFalse(recovered.budgetExhausted)
        assertEquals(1, recovered.attempt)
    }
}

class McpToolCallFailurePlanTest {

    @Test
    fun `a sent call with a broken transport schedules a reconnect but never replays`() {
        val plan = planToolCallFailure(
            sent = true,
            needsAuthorization = false,
            transportFailure = true,
            networkAvailable = true,
        )

        assertTrue(plan.markClientUnhealthy)
        assertTrue(plan.scheduleReconnect)
        assertTrue("已发出的调用必须明确告知结果不确定", plan.outcomeUnknown)
        assertFalse(plan.replayToolCall)
        assertFalse(plan.markNeedsAuthorization)
    }

    @Test
    fun `offline after a sent call does not spin reconnects`() {
        val plan = planToolCallFailure(
            sent = true,
            needsAuthorization = false,
            transportFailure = true,
            networkAvailable = false,
        )

        assertFalse(plan.scheduleReconnect)
        assertTrue(plan.outcomeUnknown)
        assertFalse(plan.replayToolCall)
    }

    @Test
    fun `a call that was never sent can be retried safely but is still not replayed by us`() {
        val plan = planToolCallFailure(
            sent = false,
            needsAuthorization = false,
            transportFailure = true,
            networkAvailable = true,
        )

        assertTrue(plan.scheduleReconnect)
        assertTrue(plan.markClientUnhealthy)
        assertFalse("没发出去就不存在结果不确定", plan.outcomeUnknown)
        assertFalse(plan.replayToolCall)
    }

    @Test
    fun `401 asks for authorization and never loops`() {
        val plan = planToolCallFailure(
            sent = true,
            needsAuthorization = true,
            transportFailure = true,
            networkAvailable = true,
        )

        assertTrue(plan.markNeedsAuthorization)
        assertFalse(plan.scheduleReconnect)
        assertFalse(plan.outcomeUnknown)
        assertFalse(plan.replayToolCall)
    }

    @Test
    fun `a server side error neither reconnects nor replays`() {
        val plan = planToolCallFailure(
            sent = true,
            needsAuthorization = false,
            transportFailure = false,
            networkAvailable = true,
        )

        assertFalse(plan.scheduleReconnect)
        assertFalse(plan.markClientUnhealthy)
        assertFalse(plan.outcomeUnknown)
        assertFalse(plan.replayToolCall)
    }

    @Test
    fun `replay is false for every possible combination`() {
        for (sent in listOf(true, false)) {
            for (needsAuth in listOf(true, false)) {
                for (transport in listOf(true, false)) {
                    for (online in listOf(true, false)) {
                        val plan = planToolCallFailure(sent, needsAuth, transport, online)
                        assertFalse(
                            "sent=$sent auth=$needsAuth transport=$transport online=$online must never replay",
                            plan.replayToolCall,
                        )
                        // 只有「已发送 + 传输断 + 不是 401」才需要把结果标成不确定
                        assertEquals(sent && transport && !needsAuth, plan.outcomeUnknown)
                    }
                }
            }
        }
    }
}

class McpTransportFailureTest {

    @Test
    fun `io failures in the cause chain count as transport failures`() {
        assertTrue(isTransportFailure(IOException("boom")))
        assertTrue(isTransportFailure(SocketTimeoutException("timeout")))
        assertTrue(isTransportFailure(IllegalStateException("wrapper", IOException("connection reset"))))
    }

    @Test
    fun `the sse give up error is a transport failure now instead of being ignored`() {
        // r2 刻意忽略这一类，导致 SSE 放弃重连后会话永远卡住
        assertTrue(isTransportFailure(RuntimeException("Maximum reconnection attempts exceeded")))
        assertTrue(isTransportFailure(IllegalStateException("cause", RuntimeException("Stream closed"))))
    }

    @Test
    fun `plain server side errors are not transport failures`() {
        assertFalse(isTransportFailure(IllegalArgumentException("invalid arguments")))
        assertFalse(isTransportFailure(IllegalStateException("tool returned an error")))
    }
}
