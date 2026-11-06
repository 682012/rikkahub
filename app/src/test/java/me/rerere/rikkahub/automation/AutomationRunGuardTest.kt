package me.rerere.rikkahub.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRunGuardTest {

    private fun run(
        id: String,
        recipeId: String = "r1",
        status: AutomationRunStatus,
        steps: List<AutomationStepResult> = emptyList(),
        acknowledged: Boolean = false,
    ) = AutomationRun(
        id = id,
        recipeId = recipeId,
        recipeName = "recipe",
        trigger = AutomationTrigger.MANUAL,
        startedAt = 0,
        status = status,
        steps = steps,
        acknowledged = acknowledged,
    )

    @Test
    fun `finished runs never block`() {
        val runs = listOf(
            run("a", status = AutomationRunStatus.SUCCEEDED),
            run("b", status = AutomationRunStatus.FAILED),
            run("c", status = AutomationRunStatus.CANCELLED),
        )
        assertNull(AutomationRunGuard.blockingRun(emptyList(), runs, "r1"))
    }

    @Test
    fun `pending unknown side effect blocks the next run`() {
        val pending = listOf(AutomationPendingBlock(runId = "a", recipeId = "r1"))
        assertEquals("a", AutomationRunGuard.blockingRun(pending, emptyList(), "r1")?.runId)
        assertNull(AutomationRunGuard.blockingRun(pending, emptyList(), "other-recipe"))
    }

    @Test
    fun `clearing history cannot bypass a pending confirmation`() {
        // 历史被清了，但待确认记录还在：仍然必须阻塞
        val pending = listOf(AutomationPendingBlock(runId = "a", recipeId = "r1"))
        assertEquals("a", AutomationRunGuard.blockingRun(pending, emptyList(), "r1")?.runId)
        assertTrue(AutomationRunGuard.acknowledgePending(pending, "a").isEmpty())
        assertNull(AutomationRunGuard.blockingRun(emptyList(), emptyList(), "r1"))
    }

    @Test
    fun `interrupted write step is normalised to unknown side effect and registers a pending block`() {
        val interrupted = run(
            "a",
            status = AutomationRunStatus.RUNNING,
            steps = listOf(
                AutomationStepResult(0, "device_snapshot", AutomationStepStatus.SUCCEEDED, sideEffect = false),
                AutomationStepResult(1, "device_tap_node", AutomationStepStatus.RUNNING, sideEffect = true),
            ),
        )
        val normalised = AutomationRunGuard.normalizeOnLoad(listOf(interrupted), nowMillis = 999)
        val normalisedRun = normalised.runs.single()
        assertEquals(AutomationRunStatus.UNKNOWN_SIDE_EFFECT, normalisedRun.status)
        assertEquals(999L, normalisedRun.finishedAt)
        // 那一步必须被标成"未知副作用"，不能留 RUNNING 也不能当 FAILED（FAILED 允许自动再跑）
        assertEquals(AutomationStepStatus.UNKNOWN_SIDE_EFFECT, normalisedRun.steps.last().status)
        assertEquals("a", normalised.pending.single().runId)
        // 已执行的步骤保留
        assertEquals(AutomationStepStatus.SUCCEEDED, normalisedRun.steps.first().status)
        assertNull(AutomationRunGuard.blockingRun(emptyList(), normalised.runs, "r1"))
        assertEquals("a", AutomationRunGuard.blockingRun(normalised.pending, normalised.runs, "r1")?.runId)
    }

    @Test
    fun `interrupted read only run is only marked failed and never blocks`() {
        val interrupted = run(
            "a",
            status = AutomationRunStatus.RUNNING,
            steps = listOf(AutomationStepResult(0, "device_snapshot", AutomationStepStatus.RUNNING, sideEffect = false)),
        )
        val normalised = AutomationRunGuard.normalizeOnLoad(listOf(interrupted), nowMillis = 10)
        assertEquals(AutomationRunStatus.FAILED, normalised.runs.single().status)
        assertTrue(normalised.pending.isEmpty())
        assertNull(AutomationRunGuard.blockingRun(normalised.pending, normalised.runs, "r1"))
    }

    @Test
    fun `acknowledge only touches the requested run`() {
        val runs = listOf(
            run("a", status = AutomationRunStatus.UNKNOWN_SIDE_EFFECT),
            run("b", status = AutomationRunStatus.UNKNOWN_SIDE_EFFECT),
        )
        val updated = AutomationRunGuard.acknowledge(runs, "a")
        assertTrue(updated.first { it.id == "a" }.acknowledged)
        assertFalse(updated.first { it.id == "b" }.acknowledged)
    }
}
