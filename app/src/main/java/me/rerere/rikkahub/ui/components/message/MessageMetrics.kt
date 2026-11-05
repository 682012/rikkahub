package me.rerere.rikkahub.ui.components.message

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Duration

/** Message creation, last model request duration and turn completion are different clocks. */
internal data class MessageTimeline(
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val wallDurationMillis: Long?,
) {
    val displayAt: LocalDateTime get() = endedAt ?: startedAt
}

internal fun UIMessage.timeline(): MessageTimeline {
    // Missing/invalid historical completion times stay unknown; never substitute "now".
    val end = finishedAt?.takeIf { role == MessageRole.ASSISTANT && it >= createdAt }
    return MessageTimeline(
        startedAt = createdAt,
        endedAt = end,
        wallDurationMillis = end?.let {
            Duration.between(createdAt.toJavaLocalDateTime(), it.toJavaLocalDateTime()).toMillis()
        },
    )
}

/** Unicode code points in body text, including punctuation, excluding whitespace.
 * Provider tokens, reasoning and tool output are deliberately not treated as body characters.
 * Count Markdown source rather than claiming a rendered-word count.
 */
internal fun UIMessage.bodyCharacterCount(): Int {
    var count = 0L
    parts.forEach { part ->
        if (part is UIMessagePart.Text) {
            var offset = 0
            while (offset < part.text.length) {
                val codePoint = part.text.codePointAt(offset)
                if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) count++
                offset += Character.charCount(codePoint)
            }
        }
    }
    return count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
