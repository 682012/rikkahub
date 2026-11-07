package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

/** Request-only references stay attached to the original user turn, including after compaction/restart. */
internal object TurnPromptReferences {
    private fun sourceOf(message: UIMessage): String = MessageDigest.getInstance("SHA-256")
        .digest(message.parts.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun capture(messages: List<UIMessage>, learning: String, knowledge: String): List<UIMessage> {
        val index = messages.indexOfLast {
            it.role == MessageRole.USER && !it.isSynthetic && !it.isContextCheckpoint
        }
        if (index < 0 || index < messages.indexOfLast { it.isContextCheckpoint }) return messages
        val target = messages[index]
        val source = sourceOf(target)
        if (target.promptReferences != null && target.promptReferencesSource == source) return messages
        val reference = listOf(learning, knowledge).filter { it.isNotBlank() }.joinToString("\n\n")
        return messages.toMutableList().also {
            it[index] = target.copy(promptReferences = reference, promptReferencesSource = source)
        }
    }

    fun expand(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
        val reference = message.promptReferences
        if (reference.isNullOrBlank() || message.promptReferencesSource != sourceOf(message)) message
        else message.copy(parts = message.parts + UIMessagePart.Text("\n\n$reference"))
    }

    fun captured(messages: List<UIMessage>): Boolean {
        val message = messages.lastOrNull {
            it.role == MessageRole.USER && !it.isSynthetic && !it.isContextCheckpoint
        } ?: return false
        return message.promptReferences != null && message.promptReferencesSource == sourceOf(message)
    }
}
