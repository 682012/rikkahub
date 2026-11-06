package me.rerere.rikkahub.data.ai.subagent

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * 换渠道策略：什么时候换、从哪续跑、预算怎么算。
 *
 * 这些是 r22 的核心口径，必须能纯单测覆盖：
 * - 报错/空输出可以换下一个模型，而不是只在"完全没响应"时才回退；
 * - 换的时候从已完成的 checkpoint 续跑，成功过的工具不再执行；
 * - 超时是整任务预算，不按渠道重置；
 * - 审批/未知副作用/取消不重放。
 */
class SubagentFailoverPolicyTest {
    private fun finishedAssistant(text: String, modelId: Uuid? = null): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
        modelId = modelId,
    )

    @Test
    fun providerErrorAndEmptyOutputSwitchButTimeoutAndApprovalDoNot() {
        assertTrue(subagentShouldSwitchModel(SubagentAttemptOutcome.ERROR, autoFailover = true))
        assertTrue(subagentShouldSwitchModel(SubagentAttemptOutcome.EMPTY_OUTPUT, autoFailover = true))
        // 超时是整个任务预算：预算花完就没有下一次
        assertFalse(subagentShouldSwitchModel(SubagentAttemptOutcome.TIMEOUT, autoFailover = true))
        // 子代理不放开审批，换模型也不会变得可执行
        assertFalse(subagentShouldSwitchModel(SubagentAttemptOutcome.AWAITING_APPROVAL, autoFailover = true))
        assertFalse(subagentShouldSwitchModel(SubagentAttemptOutcome.STEP_LIMIT, autoFailover = true))
        assertFalse(subagentShouldSwitchModel(SubagentAttemptOutcome.COMPLETED, autoFailover = true))
        // 关掉自动换渠道后一律不换（退化成显式失败）
        SubagentAttemptOutcome.entries.forEach {
            assertFalse(subagentShouldSwitchModel(it, autoFailover = false))
        }
    }

    @Test
    fun switchingRequiresResumableCheckpointWhenThereIsProgress() {
        // 没有任何进展（第一步就没响应）：从零开始是安全的
        assertTrue(subagentCanSwitchWithCheckpoint(hasProgress = false, canResume = false))
        // 有进展且 checkpoint 可续：允许换，带着已完成的工具结果继续
        assertTrue(subagentCanSwitchWithCheckpoint(hasProgress = true, canResume = true))
        // 有进展但 checkpoint 不可续（工具已开始未执行 / 半截媒体）：不换，避免重放未知副作用
        assertFalse(subagentCanSwitchWithCheckpoint(hasProgress = true, canResume = false))
    }

    @Test
    fun checkpointOnlyAdvancesOnCompletedSteps() {
        val checkpoint = SubagentRunCheckpoint()
        assertFalse(checkpoint.hasProgress)
        assertNull(checkpoint.resumeSeed())

        checkpoint.observe(finishedAssistant("step 1 with tool results"))
        assertTrue(checkpoint.hasProgress)
        assertTrue(checkpoint.canResume)
        assertEquals("step 1 with tool results", checkpoint.resumeSeed()?.toText())

        // 半截的流（没有 finishedAt）不会成为续跑边界：仍然续跑上一个已完成检查点
        checkpoint.observe(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("half"))),
        )
        assertEquals("step 1 with tool results", checkpoint.resumeSeed()?.toText())
    }

    @Test
    fun timeoutBudgetIsWholeTaskAndNeverResetsPerChannel() {
        var now = 1_000_000L
        val budget = SubagentTimeBudget(10, clock = { now })
        assertEquals(10_000L, budget.remainingMs())
        now += 4_000L
        assertEquals(6_000L, budget.remainingMs())
        // 第二次尝试不会重置预算：同一个 deadline，剩余时间继续减少
        now += 6_000L
        assertTrue(budget.expired)
        assertTrue(budget.remainingMs() <= 0L)

        // 0 表示不限制
        val unlimited = SubagentTimeBudget(0, clock = { now })
        assertTrue(unlimited.unlimited)
        assertEquals(Long.MAX_VALUE, unlimited.remainingMs())
        assertFalse(unlimited.expired)
    }

    @Test
    fun taskRecordKeepsAttemptTrailAfterMigration() {
        val tried = listOf(
            Uuid.parse("11111111-1111-1111-1111-111111111111"),
            Uuid.parse("22222222-2222-2222-2222-222222222222"),
        )
        val task = SubagentTask(
            id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            prompt = "p",
            conversationId = null,
            modelId = tried.last(),
            triedModelIds = tried,
            attempts = 2,
            switchNote = "${tried[0]} -> ${tried[1]} (empty_output)",
        )
        val restored = decodeSubagentTaskRecord(encodeSubagentTaskRecord(task.toRecord()))!!
        assertEquals(2, restored.attempts)
        assertEquals(tried.map { it.toString() }, restored.triedModelIds)
        assertEquals(task.switchNote, restored.switchNote)
        assertEquals(tried, restored.toTask().triedModelIds)

        // 旧记录（没有这些字段）仍能读，按"一次尝试"理解
        val legacy = decodeSubagentTaskRecord(
            """{"version":1,"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","status":"COMPLETED","steps":3}""",
        )!!
        assertEquals(0, legacy.attempts)
        assertTrue(legacy.triedModelIds.isEmpty())
    }
}
