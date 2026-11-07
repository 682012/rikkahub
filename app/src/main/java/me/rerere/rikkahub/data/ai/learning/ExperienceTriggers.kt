package me.rerere.rikkahub.data.ai.learning

import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * 「一轮任务结束了吗、是哪一种结束」的唯一判定。
 *
 * 为什么要有这个文件：需求是「每完成一轮任务触发一次」，而不是「每次 tool call 触发一次」。
 * 在 ChatService.runTurn 里，一轮可能有十几个工具调用、若干次流式回调；它们都不是轮次的终点。
 * 只有 agent 给出**终态**、且这条助手消息确实落地时，才构造 [TurnFacts]。
 *
 * 明确排除的情况（返回 null = 不触发）：
 * - 取消（用户点了停止 / 协程被取消）：取消路径不会走到这里，即使走到也判为 null；
 * - 等待工具审批（AwaitingApproval）：这一轮还没结束，审批后还会继续；
 * - 网络恢复中的中间失败：由重试策略接管，最终成功/放弃时才是一次终态；
 * - 没有任何助手消息（还没产出内容就被打断）。
 */
object ExperienceTriggers {

    /**
     * 把一次 agent 结束转换成经验学习的事实。
     *
     * @param reason agent 给出的结束原因；null 表示调用方也不知道（一律不触发）
     * @param reply 本次真正落地的助手消息
     * @param cancelled 调用方明确知道是取消（含协程取消）时传 true，直接不触发
     * @return null 表示这次不是可总结的终态
     */
    fun facts(
        assistantId: Uuid,
        conversationId: Uuid,
        modelId: Uuid,
        reply: UIMessage?,
        reason: AgentFinishReason?,
        cancelled: Boolean = false,
        userRequest: String? = null,
        stopReason: String? = null,
    ): TurnFacts? {
        if (cancelled) return null
        if (reason == null) return null
        if (reply == null) return null
        val outcome = when (reason) {
            AgentFinishReason.Completed -> TurnOutcomeKind.SUCCESS
            // 触到步数上限：有结果但没跑完，仍然是一次可以总结的终态
            AgentFinishReason.StepLimitReached -> TurnOutcomeKind.TERMINATED
            // 等待审批只是暂停：审批后这一轮还会继续，不是终态
            is AgentFinishReason.AwaitingApproval -> return null
        }
        val executed = reply.getTools().filter { it.isExecuted }
        val failed = executed.filter { tool -> tool.output.any(::isErrorPayload) }
        return TurnFacts(
            assistantId = assistantId,
            conversationId = conversationId,
            messageId = reply.id,
            modelId = modelId,
            outcome = outcome,
            toolNames = executed.mapNotNull { it.toolName }.distinct()
                .take(LearningExperienceLimits.MAX_TOOL_NAMES),
            failedToolNames = failed.mapNotNull { it.toolName }.distinct()
                .take(LearningExperienceLimits.MAX_TOOL_NAMES),
            userRequest = clip(userRequest),
            assistantReply = clip(reply.toText()),
            stopReason = stopReason ?: describe(outcome),
        )
    }

    private fun describe(outcome: TurnOutcomeKind): String = when (outcome) {
        TurnOutcomeKind.SUCCESS -> "生成正常完成"
        TurnOutcomeKind.TERMINATED -> "生成在没有正常完成的情况下结束"
    }

    /** 工具输出是不是结构化错误。只看是不是 `{"error": ...}`，不读参数。 */
    private fun isErrorPayload(part: UIMessagePart): Boolean {
        val text = (part as? UIMessagePart.Text)?.text ?: return false
        if (text.length > 64_000) return false
        val trimmed = text.trimStart()
        if (!trimmed.startsWith('{')) return false
        return trimmed.contains("\"error\"") && !trimmed.contains("\"error\":null") &&
            !trimmed.contains("\"error\": null")
    }

    private fun clip(text: String?): String? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        return if (value.length <= LearningExperienceLimits.MAX_FACT_CHARS) {
            value
        } else {
            value.take(LearningExperienceLimits.MAX_FACT_CHARS)
        }
    }
}
