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

internal fun autoCompressionPlan(conversation: Conversation, config: AutoCompressSettings, estimateWhenUsageMissing: Boolean = false): AutoCompressionPlan? {
    if (!config.enabled) return null
    val nodes = conversation.messageNodes
    // Never compress while continuing an assistant/tool-approval turn.
    if (nodes.lastOrNull()?.currentMessage?.role != MessageRole.USER) return null
    val history = nodes.dropLast(1)
    if (history.isEmpty() || history.last().currentMessage.isContextCheckpoint) return null
    val messages = history.map { it.currentMessage }
    if (messages.any { m -> m.getTools().any { it.isPending || it.canResumeExecution } }) return null
    val measured = conversation.copy(messageNodes = history).lastContextTokens()?.takeIf { it > 0 }
    val used = if (estimateWhenUsageMissing) estimateWebContextBudget(messages) else measured ?: return null
    return AutoCompressionPlan(history.last().id, messages, used)
}

/** Conservative client-side budget only, never reported as provider token usage or billing. */
internal fun estimateWebContextBudget(messages: List<UIMessage>): Int {
    val checkpoint = messages.indexOfLast { it.isContextCheckpoint }
    val current = if (checkpoint >= 0) messages.drop(checkpoint) else messages
    val bytes = current.sumOf { message ->
        64L + message.parts.sumOf { part -> when (part) {
            is me.rerere.ai.ui.UIMessagePart.Text -> part.text.toByteArray(Charsets.UTF_8).size.toLong()
            is me.rerere.ai.ui.UIMessagePart.Tool -> part.input.toByteArray(Charsets.UTF_8).size.toLong() +
                part.output.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
                    .sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() }
            else -> 0L
        } }
    }
    return ((bytes + 1) / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
