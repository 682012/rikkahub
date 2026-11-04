package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/** Save only completed model steps and the newest tool lifecycle, never a half-written stream. */
class ChatReconnectCheckpoint(initial: UIMessage? = null) {
    var message: UIMessage? = initial
        private set

    fun observe(updated: UIMessage) {
        val previous = message
        val lifecycleChanged = updated.getTools().map { Triple(it.toolCallId, it.startedAt, it.isExecuted) } !=
            previous?.getTools().orEmpty().map { Triple(it.toolCallId, it.startedAt, it.isExecuted) }
        if ((updated.finishedAt != null && updated.finishedAt != previous?.finishedAt) || lifecycleChanged) {
            message = updated
        }
    }

    val canResume: Boolean
        get() = message?.getTools().orEmpty().none { it.startedAt != null && !it.isExecuted }
}

/** A route event may resume only the original failed turn, never a stopped/edited/replaced chat. */
data class ChatReconnectTicket(
    val failedEpoch: Long,
    val incarnation: Int,
    val userMessage: UIMessage?,
    val replyNodeId: Uuid,
    val checkpoint: UIMessage?,
    val failedTail: UIMessage? = null,
) {
    fun canResume(routeEpoch: Long, available: Boolean, currentIncarnation: Int, currentUser: UIMessage?): Boolean =
        available && routeEpoch != failedEpoch && incarnation == currentIncarnation && userMessage == currentUser
}
