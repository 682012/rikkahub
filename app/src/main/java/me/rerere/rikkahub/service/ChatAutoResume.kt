package me.rerere.rikkahub.service

import me.rerere.ai.agent.AgentFinishReason

/**
 * 这次结束为什么不能自动续接。null 才表示可以从原会话检查点接着写。
 *
 * 这里刻意只有「有证据」的一条自动路径：[AgentFinishReason.StepLimitReached]。框架在步数上限处停下时，
 * 消息尾部的工具结果已经落库、没有半截参数、没有待审批的工具，所以再跑一次 agent loop 只是继续，
 * 不是重放。`Completed` 是正常结束，`AwaitingApproval` 是中断点（自动继续等于替用户点同意），
 * 用户停止是显式取消 —— 三者都不在这里放行。
 */
enum class AutoResumeBlocker {
    /** 总开关关着（默认）。 */
    DISABLED,

    /** 用户点了停止，或会话被替换/删除。停止不能复活，待续接也必须随取消一起消失。 */
    MANUAL_STOP,

    /** 这一轮正常完成，模型已经写完并且没再调用工具。绝不自动重开。 */
    COMPLETED,

    /** 停在等待用户审批：自动继续等于替用户点了同意。 */
    APPROVAL_PENDING,

    /** 尾部还有「已开始但没结果」的工具，或本轮有不确定副作用：重放会把写入做第二遍。 */
    UNKNOWN_SIDE_EFFECT,

    /** 没有可用的回复节点，接不上（例如还没开始写就结束）。 */
    NO_REPLY_NODE,

    /** 这一轮已经把可配置的次数用完。 */
    ATTEMPTS_EXHAUSTED,

    /** 其它结束原因：本框架没有证据说明它可以安全续接，不猜。 */
    UNSUPPORTED_END,
}

/**
 * 「异常停止自动续接」的决策层：只回答能不能从原会话检查点接着写，不碰协程、不碰 Android、不改消息。
 *
 * 真正的续接动作全部复用 r21 已有的一条链：`ChatReconnectTicket` + `scheduleRecovery` + `tryReconnect`
 * + `runTurn`。这里不新增 runner，也不新建会话、不 append 新的 user「继续」消息。
 *
 * 复用到的安全边界（都还在原来的位置上，没有被这里放宽）：
 * - 用户停止 / 会话被换掉：[ChatAutoRecovery.blockReason] 的 `cancelled` 分支 + `clearRecovery`；
 * - 待审批：`awaitingApproval`，自动续接不会替用户点同意；
 * - 未知副作用：[canResume] 为 false（存在「已开始未完成」的工具或半截参数）就停；
 * - 次数：与传输层恢复共用 [ChatRecoveryLedger]，上限是用户配的 1..10。
 */
object ChatAutoResume {
    /** 可配置次数的下界：0 等于关掉，所以最小值是 1。 */
    const val MIN_ATTEMPTS: Int = 1

    /** 可配置次数的上界，防止「一直续」变成死循环。 */
    const val MAX_ATTEMPTS: Int = 10

    /** 默认次数，与 r21/r22 的自动恢复默认值一致。 */
    const val DEFAULT_ATTEMPTS: Int = 3

    /** 把任意输入夹进 1..10。 */
    fun clampAttempts(raw: Int): Int = raw.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS)

    /**
     * 决策。
     *
     * @param enabled 用户开关（默认 false）
     * @param cancelled 是否被取消（用户停止/会话替换）。true 一定是 [AutoResumeBlocker.MANUAL_STOP]
     * @param reason 这一轮的结束原因
     * @param awaitingApproval 结束时会话是否停在等待审批
     * @param canResume [ChatReconnectCheckpoint.canResume]；没有「已开始未完成」的工具才为 true
     * @param hasCommittedSideEffects 本轮有不确定副作用（如媒体生成未落检查点）
     * @param hasReplyNode 是否有可以接着写的回复节点
     * @param attemptsUsed 这一轮已经自动续接过几次
     * @param attemptLimit 允许的次数（已经夹进 1..10）
     * @return null 表示可以从检查点续接
     */
    fun blockReason(
        enabled: Boolean,
        cancelled: Boolean,
        reason: AgentFinishReason,
        awaitingApproval: Boolean = false,
        canResume: Boolean = true,
        hasCommittedSideEffects: Boolean = false,
        hasReplyNode: Boolean = true,
        attemptsUsed: Int = 0,
        attemptLimit: Int = DEFAULT_ATTEMPTS,
    ): AutoResumeBlocker? {
        if (cancelled) return AutoResumeBlocker.MANUAL_STOP
        if (!enabled) return AutoResumeBlocker.DISABLED
        if (awaitingApproval) return AutoResumeBlocker.APPROVAL_PENDING
        if (reason is AgentFinishReason.Completed) return AutoResumeBlocker.COMPLETED
        // 只有「步数上限」这一条有证据：尾部工具结果已落库、没有半截参数、没有待审批
        if (reason !is AgentFinishReason.StepLimitReached) return AutoResumeBlocker.UNSUPPORTED_END
        if (hasCommittedSideEffects || !canResume) return AutoResumeBlocker.UNKNOWN_SIDE_EFFECT
        if (!hasReplyNode) return AutoResumeBlocker.NO_REPLY_NODE
        if (attemptsUsed >= clampAttempts(attemptLimit)) return AutoResumeBlocker.ATTEMPTS_EXHAUSTED
        return null
    }

    /** [blockReason] 的布尔形式。 */
    fun shouldResume(
        enabled: Boolean,
        cancelled: Boolean,
        reason: AgentFinishReason,
        awaitingApproval: Boolean = false,
        canResume: Boolean = true,
        hasCommittedSideEffects: Boolean = false,
        hasReplyNode: Boolean = true,
        attemptsUsed: Int = 0,
        attemptLimit: Int = DEFAULT_ATTEMPTS,
    ): Boolean = blockReason(
        enabled = enabled,
        cancelled = cancelled,
        reason = reason,
        awaitingApproval = awaitingApproval,
        canResume = canResume,
        hasCommittedSideEffects = hasCommittedSideEffects,
        hasReplyNode = hasReplyNode,
        attemptsUsed = attemptsUsed,
        attemptLimit = attemptLimit,
    ) == null
}
