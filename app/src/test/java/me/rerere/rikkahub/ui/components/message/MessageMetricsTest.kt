package me.rerere.rikkahub.ui.components.message

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class MessageMetricsTest {
    private val start = LocalDateTime.parse("2026-11-05T23:58:00")
    private val end = LocalDateTime.parse("2026-11-06T00:12:00")
    private fun message(role: MessageRole = MessageRole.ASSISTANT) = UIMessage(
        role = role, parts = emptyList(), createdAt = start, finishedAt = end,
    )

    @Test fun assistantDisplaysActualEndInsteadOfRequestCreation() {
        val original = message()
        assertEquals(end, original.timeline().displayAt)
        assertEquals(start, original.createdAt)
    }

    @Test fun midnightAndLongToolWaitStayInWallTime() {
        assertEquals(840_000L, message().timeline().wallDurationMillis)
    }

    @Test fun missingEndStaysUnknownInsteadOfBecomingNow() {
        val timeline = message().copy(finishedAt = null).timeline()
        assertNull(timeline.endedAt)
        assertNull(timeline.wallDurationMillis)
        assertEquals(start, timeline.displayAt)
    }

    @Test fun endBeforeStartIsNotDisplayedAsCompletion() {
        val timeline = message().copy(finishedAt = LocalDateTime.parse("2026-11-05T23:57:59")).timeline()
        assertNull(timeline.endedAt)
        assertNull(timeline.wallDurationMillis)
    }

    @Test fun userTimeMeansSentTime() {
        val timeline = message(MessageRole.USER).timeline()
        assertEquals(start, timeline.displayAt)
        assertNull(timeline.endedAt)
    }

    @Test fun identicalTimestampsAreZeroNotMissing() {
        assertEquals(0L, message().copy(finishedAt = start).timeline().wallDurationMillis)
    }

    @Test fun unicodeIsNotUtf16LengthAndWhitespaceIsNotCounted() {
        val text = message().copy(parts = listOf(UIMessagePart.Text("你好 😀 A\n\t　\u00a0")))
        assertEquals(4, text.bodyCharacterCount())
    }

    @Test fun reasoningAndNoticesAreNotBodyCharacters() {
        val text = message().copy(parts = listOf(
            UIMessagePart.Text("你好，world!"),
            UIMessagePart.Reasoning(reasoning = "not the body"),
            UIMessagePart.Notice(title = "tool ended", text = "not the body"),
            UIMessagePart.Text(" 再见"),
        ))
        assertEquals(11, text.bodyCharacterCount())
    }

    @Test fun noBodyIsZeroEvenWhenUsageIsUnknown() {
        assertNull(message().usage)
        assertEquals(0, message().bodyCharacterCount())
    }
}
