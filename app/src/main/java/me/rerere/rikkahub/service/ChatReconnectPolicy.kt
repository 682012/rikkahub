package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** Save only completed model steps and the newest tool lifecycle, never a half-written stream. */
class ChatReconnectCheckpoint(initial: UIMessage? = null) {
    var message: UIMessage? = initial
        private set
    private var uncheckpointedMedia = false

    fun observe(updated: UIMessage) {
        val previous = message
        val lifecycleChanged = updated.getTools().map { Triple(it.toolCallId, it.startedAt, it.isExecuted) } !=
            previous?.getTools().orEmpty().map { Triple(it.toolCallId, it.startedAt, it.isExecuted) }
        val stepFinished = updated.finishedAt != null && updated.finishedAt != previous?.finishedAt
        if (stepFinished) {
            uncheckpointedMedia = false
        } else if (updated.parts.any { it.isProducedMedia() && it !in previous?.parts.orEmpty() }) {
            // Reissuing a half-finished media response may generate/bill the same file again.
            uncheckpointedMedia = true
        }
        val noticeBoundary = updated.parts.lastOrNull() is UIMessagePart.Notice &&
            updated.parts.size != previous?.parts?.size
        if (stepFinished || lifecycleChanged || noticeBoundary) {
            message = updated
        }
    }

    val canResume: Boolean
        get() = !uncheckpointedMedia &&
            message?.getTools().orEmpty().none { it.startedAt != null && !it.isExecuted }
}

/** A route event may resume only the original failed turn, never a stopped/edited/replaced chat. */
data class ChatReconnectTicket(
    val failedEpoch: Long,
    val incarnation: Int,
    val userMessage: UIMessage?,
    val replyNodeId: Uuid,
    val checkpoint: UIMessage?,
    val failedTail: UIMessage? = null,
    /** Only this interrupted response may be rolled back; an older variant is never deleted. */
    val interruptedMessageId: Uuid? = null,
    val regenerateNodeId: Uuid? = null,
    val previousSelectIndex: Int? = null,
) {
    val retryRegenerateNodeId: Uuid? get() = regenerateNodeId.takeIf { checkpoint == null }

    fun restoredSelectIndex(current: Int, variants: Int): Int =
        (if (checkpoint == null) previousSelectIndex ?: current else current).coerceIn(0, variants - 1)

    fun restoreReplyVariants(messages: List<UIMessage>): List<UIMessage> = messages.mapNotNull { message ->
        if (message.id == interruptedMessageId) checkpoint else message
    }

    fun canResume(routeEpoch: Long, available: Boolean, currentIncarnation: Int, currentUser: UIMessage?): Boolean =
        available && routeEpoch != failedEpoch && incarnation == currentIncarnation && userMessage == currentUser
}

private fun UIMessagePart.isProducedMedia(): Boolean = when (this) {
    is UIMessagePart.Image -> url.isNotBlank()
    is UIMessagePart.Video -> url.isNotBlank()
    is UIMessagePart.Audio -> url.isNotBlank()
    is UIMessagePart.Document -> url.isNotBlank()
    else -> false
}
