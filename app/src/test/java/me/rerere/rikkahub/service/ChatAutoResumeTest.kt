package me.rerere.rikkahub.service

import me.rerere.ai.agent.AgentFinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m3「异常停止自动续接」的纯策略回归：默认关闭、用户停止、正常完成、等待审批、未知副作用、次数耗尽。
 * 这些用例不碰协程、不碰 Android，只验证决策层不会替用户做危险的事。
 */
class ChatAutoResumeTest {

    private fun decide(
        enabled: Boolean = true,
        cancelled: Boolean = false,
        reason: AgentFinishReason = AgentFinishReason.StepLimitReached,
        awaitingApproval: Boolean = false,
        canResume: Boolean = true,
        hasCommittedSideEffects: Boolean = false,
        hasReplyNode: Boolean = true,
        attemptsUsed: Int = 0,
        attemptLimit: Int = ChatAutoResume.DEFAULT_ATTEMPTS,
    ): AutoResumeBlocker? = ChatAutoResume.blockReason(
        enabled = enabled,
        cancelled = cancelled,
        reason = reason,
        awaitingApproval = awaitingApproval,
        canResume = canResume,
        hasCommittedSideEffects = hasCommittedSideEffects,
        hasReplyNode = hasReplyNode,
        attemptsUsed = attemptsUsed,
        attemptLimit = attemptLimit,
    )

    @Test
    fun `default off never resumes`() {
        // 默认值是关：设置里不打开，步数上限也不会自动续接
        assertEquals(false, me.rerere.rikkahub.data.datastore.NetworkSetting().autoResumeEnabled)
        assertEquals(AutoResumeBlocker.DISABLED, decide(enabled = false))
    }

    @Test
    fun `manual stop wins over everything`() {
        assertEquals(AutoResumeBlocker.MANUAL_STOP, decide(cancelled = true))
        // 即使用户打开开关、次数还没用完，停止也不复活
        assertEquals(AutoResumeBlocker.MANUAL_STOP, decide(cancelled = true, attemptsUsed = 0))
    }

    @Test
    fun `normal completion is not reopened`() {
        assertEquals(AutoResumeBlocker.COMPLETED, decide(reason = AgentFinishReason.Completed))
        assertFalse(
            ChatAutoResume.shouldResume(
                enabled = true, cancelled = false, reason = AgentFinishReason.Completed,
            ),
        )
    }

    @Test
    fun `awaiting approval is not auto approved`() {
        assertEquals(
            AutoResumeBlocker.APPROVAL_PENDING,
            decide(reason = AgentFinishReason.AwaitingApproval(setOf("call-1")), awaitingApproval = true),
        )
    }

    @Test
    fun `unknown side effect or half written tool is never replayed`() {
        assertEquals(AutoResumeBlocker.UNKNOWN_SIDE_EFFECT, decide(canResume = false))
        assertEquals(AutoResumeBlocker.UNKNOWN_SIDE_EFFECT, decide(hasCommittedSideEffects = true))
    }

    @Test
    fun `attempt budget is bounded and stops at the limit`() {
        // 未用完：可以续接
        assertNull(decide(attemptsUsed = 2, attemptLimit = 3))
        // 用完：必须停
        assertEquals(AutoResumeBlocker.ATTEMPTS_EXHAUSTED, decide(attemptsUsed = 3, attemptLimit = 3))
        // 次数一律夹进 1..10
        assertEquals(1, ChatAutoResume.clampAttempts(0))
        assertEquals(1, ChatAutoResume.clampAttempts(-5))
        assertEquals(10, ChatAutoResume.clampAttempts(99))
        assertEquals(10, ChatAutoResume.clampAttempts(10))
        assertEquals(3, ChatAutoResume.DEFAULT_ATTEMPTS)
        // attemptLimit 传越界值也按夹好的上限算
        assertEquals(AutoResumeBlocker.ATTEMPTS_EXHAUSTED, decide(attemptsUsed = 10, attemptLimit = 99))
    }

    @Test
    fun `no reply node means nothing to continue`() {
        assertEquals(AutoResumeBlocker.NO_REPLY_NODE, decide(hasReplyNode = false))
    }

    @Test
    fun `step limit reached is the only supported abnormal end`() {
        assertNull(decide(reason = AgentFinishReason.StepLimitReached))
        assertTrue(
            ChatAutoResume.shouldResume(
                enabled = true, cancelled = false, reason = AgentFinishReason.StepLimitReached,
            ),
        )
    }
}
