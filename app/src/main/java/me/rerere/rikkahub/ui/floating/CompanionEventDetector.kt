package me.rerere.rikkahub.ui.floating

import me.rerere.rikkahub.data.companion.CompanionEventKind
import me.rerere.rikkahub.data.companion.CompanionTrigger
import me.rerere.rikkahub.data.companion.CompanionTriggerKind

/**
 * 把悬浮窗状态的两次快照变成"真的发生了什么"。
 *
 * 输入是 [FloatingChatUiState] 的相邻两帧（会话、是否在生成、本轮错误、待审批工具），
 * 输出 0~n 个自动互动事件。这里不读聊天正文：事件的 detail 一律留空，提示词按 [CompanionEventKind]
 * 用固定句子，所以即使"跟随会话"，聊天内容也不会被发给另一个模型。
 *
 * 事件键带本轮生成的开始时间 / 错误时间戳 / 工具调用 ID，同一件事只会得到同一个键，
 * 去重交给 `CompanionTriggerPolicy`，这里只负责生成键。
 */
class CompanionEventDetector {
    fun detect(previous: FloatingChatUiState?, current: FloatingChatUiState): List<CompanionTrigger> {
        // 第一帧不算"变化"，否则每次窗口出现都会凭空说一句
        if (previous == null) return emptyList()
        if (previous.conversationId != current.conversationId) return emptyList()
        val events = ArrayList<CompanionTrigger>(3)
        val conversation = current.conversationId.toString()

        if (!previous.generating && current.generating) {
            events += trigger(
                CompanionEventKind.GENERATION_STARTED,
                "start:$conversation:${current.generationStartedAt ?: -1L}",
            )
        }
        if (previous.generating && !current.generating && current.error == null) {
            val started = current.generationStartedAt ?: previous.generationStartedAt ?: -1L
            events += trigger(CompanionEventKind.GENERATION_FINISHED, "finish:$conversation:$started")
        }
        val error = current.error
        if (error != null && error.id != previous.error?.id) {
            events += trigger(
                CompanionEventKind.GENERATION_FAILED,
                "error:$conversation:${error.timestamp}",
            )
        }
        val knownApprovals = previous.pendingTools.mapTo(HashSet()) { it.toolCallId }
        current.pendingTools
            .filterNot { it.toolCallId in knownApprovals }
            .forEach { tool ->
                events += trigger(CompanionEventKind.APPROVAL_PENDING, "approval:${tool.toolCallId}")
            }
        return events
    }

    private fun trigger(kind: CompanionEventKind, key: String) = CompanionTrigger(
        kind = CompanionTriggerKind.EVENT,
        event = kind,
        // 刻意留空：聊天内容不参与自动互动
        detail = "",
        eventKey = key,
    )
}
