package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.AutoCompressSettings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.lastContextTokens
import kotlin.uuid.Uuid

internal data class AutoCompressionPlan(
    val afterNodeId: Uuid,
    val messages: List<UIMessage>,
    val contextTokens: Int,
) {
    fun exceedsThreshold(contextLength: Int, thresholdPercent: Int) =
        contextLength > 0 && contextTokens.toLong() * 100 >=
            contextLength.toLong() * thresholdPercent.coerceIn(10, 100)
}

internal fun autoCompressionPlan(conversation: Conversation, config: AutoCompressSettings): AutoCompressionPlan? {
    if (!config.enabled) return null
    val nodes = conversation.messageNodes
    // Never compress while continuing an assistant/tool-approval turn.
    if (nodes.lastOrNull()?.currentMessage?.role != MessageRole.USER) return null
    val history = nodes.dropLast(1)
    if (history.isEmpty() || history.last().currentMessage.isContextCheckpoint) return null
    val messages = history.map { it.currentMessage }
    if (messages.any { m -> m.getTools().any { it.isPending || it.canResumeExecution } }) return null
    val used = conversation.copy(messageNodes = history).lastContextTokens()?.takeIf { it > 0 } ?: return null
    return AutoCompressionPlan(history.last().id, messages, used)
}
