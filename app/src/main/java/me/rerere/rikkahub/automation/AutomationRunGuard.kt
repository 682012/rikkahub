package me.rerere.rikkahub.automation

/**
 * 三道闸：能不能跑、读盘时怎么归类、确认后怎么放行。全是纯函数，可直接单测。
 *
 * 关键点：**阻塞记录独立于运行历史**。
 * 如果把"上次有未知副作用"只记在 history 里，用户点一下"清除历史"就绕过了确认——
 * 所以 store 单独持久化 [AutomationPendingBlock]，清历史不动它，只有显式 acknowledge 才清。
 */
object AutomationRunGuard {

    /** 读盘归一的结果：修正后的历史 + 读盘时新发现的待确认记录。 */
    data class Normalized(
        val runs: List<AutomationRun>,
        val pending: List<AutomationPendingBlock>,
    )

    /**
     * 返回阻塞这次运行的那条待确认记录；null = 可以跑。
     *
     * @param pending 持久化的待确认记录（清历史也不会消失）
     * @param runs 当前进程里的历史（用来兜住"这次进程里还有一条 RUNNING"的情况）
     */
    fun blockingRun(
        pending: List<AutomationPendingBlock>,
        runs: List<AutomationRun>,
        recipeId: String,
    ): AutomationPendingBlock? {
        pending.firstOrNull { it.recipeId == recipeId }?.let { return it }
        val interrupted = runs.firstOrNull { it.recipeId == recipeId && it.status == AutomationRunStatus.RUNNING }
        return interrupted?.toPendingBlock("still running in this process")
    }

    /**
     * 读盘归一：把进程死掉留下的 RUNNING 变成明确状态。
     *
     * - 最后一步是写步骤且仍是 RUNNING → UNKNOWN_SIDE_EFFECT（可能已经生效），并登记待确认；
     * - 其它（只读步骤中断 / 一步都没开始）→ FAILED，message 说明"没有发生状态改变"。
     *
     * 注意：这里只处理"值不值得确认"，不允许把可能生效的写操作标成 FAILED，
     * 因为 FAILED 在别处意味着"确定没生效、可以再跑"。
     */
    fun normalizeOnLoad(runs: List<AutomationRun>, nowMillis: Long): Normalized {
        val pending = mutableListOf<AutomationPendingBlock>()
        val normalized = runs.map { run ->
            if (run.status != AutomationRunStatus.RUNNING) {
                run
            } else {
                val inflightWrite = run.steps.lastOrNull()?.let {
                    it.status == AutomationStepStatus.RUNNING && it.sideEffect
                } == true
                if (inflightWrite) {
                    pending += run.toPendingBlock("interrupted during a write step; the change may or may not have happened")
                    run.copy(
                        status = AutomationRunStatus.UNKNOWN_SIDE_EFFECT,
                        steps = run.steps.map { step ->
                            if (step.status == AutomationStepStatus.RUNNING) {
                                step.copy(status = AutomationStepStatus.UNKNOWN_SIDE_EFFECT)
                            } else {
                                step
                            }
                        },
                        finishedAt = run.finishedAt ?: nowMillis,
                        message = "interrupted during a write step; the change may or may not have happened",
                    )
                } else {
                    run.copy(
                        status = AutomationRunStatus.FAILED,
                        steps = run.steps.map { step ->
                            if (step.status == AutomationStepStatus.RUNNING) {
                                step.copy(status = AutomationStepStatus.CANCELLED)
                            } else {
                                step
                            }
                        },
                        finishedAt = run.finishedAt ?: nowMillis,
                        message = "interrupted before any state change",
                    )
                }
            }
        }
        return Normalized(normalized, pending.distinctBy { it.runId })
    }

    /** 上一条未知副作用记录被用户确认后，允许重跑。 */
    fun acknowledge(runs: List<AutomationRun>, runId: String): List<AutomationRun> =
        runs.map { if (it.id == runId) it.copy(acknowledged = true) else it }

    fun acknowledgePending(pending: List<AutomationPendingBlock>, runId: String): List<AutomationPendingBlock> =
        pending.filterNot { it.runId == runId }

    /** 一个 run 结束在"可能已生效"状态时，登记待确认。 */
    fun pendingFromRun(run: AutomationRun): AutomationPendingBlock? {
        val unknown = run.status == AutomationRunStatus.UNKNOWN_SIDE_EFFECT ||
            run.steps.any { it.status == AutomationStepStatus.UNKNOWN_SIDE_EFFECT }
        if (!unknown) return null
        if (run.acknowledged) return null
        return run.toPendingBlock(run.message.ifBlank { "unknown side effect" })
    }

    private fun AutomationRun.toPendingBlock(message: String): AutomationPendingBlock = AutomationPendingBlock(
        runId = id,
        recipeId = recipeId,
        recipeName = recipeName,
        at = startedAt,
        message = message,
    )
}
