package me.rerere.rikkahub.data.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import org.junit.Assert.*
import org.junit.Test

class TurnPromptReferencesTest {
    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
    private fun capture(messages: List<UIMessage>, learning: String = "lesson", kb: String = "reference") =
        TurnPromptReferences.capture(messages, learning, kb)
    private fun rendered(messages: List<UIMessage>) = TurnPromptReferences.expand(messages).map { it.toText() }

    @Test fun appendPreservesPreviousReferencePrefix() {
        val first = capture(listOf(user("one")))
        val next = capture(first + user("two"), "new lesson", "new reference")
        assertEquals(rendered(first), rendered(next).take(first.size))
        assertTrue(rendered(next).last().contains("new reference"))
    }

    @Test fun retryDoesNotReplaceSnapshot() {
        val first = capture(listOf(user("one")))
        assertSame(first, capture(first, "different lesson", "different reference"))
    }

    @Test fun partialCompressionKeepsUncompressedMessageBytes() {
        val first = capture(listOf(user("old")))
        val tail = capture(first + user("retained"), "tail lesson", "tail reference")
        val checkpoint = user("summary").copy(isContextCheckpoint = true)
        val compacted = listOf(checkpoint) + tail.takeLast(1)
        val next = capture(compacted + user("next"), "next lesson", "next reference")
        assertEquals(rendered(compacted), rendered(next).take(compacted.size))
        assertEquals(rendered(tail).last(), rendered(next)[1])
    }

    @Test fun fullCompressionDoesNotAttachReferencesBeforeCheckpoint() {
        val history = listOf(user("old"), user("summary").copy(isContextCheckpoint = true))
        assertSame(history, capture(history))
        assertEquals(1, history.limitContext(0).size)
    }

    @Test fun emptySnapshotIsAlsoStable() {
        val first = capture(listOf(user("one")), "", "")
        assertTrue(TurnPromptReferences.captured(first))
        assertSame(first, capture(first, "late lesson", "late reference"))
        assertEquals("one", rendered(first).single())
    }

    @Test fun editingTextInvalidatesOldReferences() {
        val first = capture(listOf(user("one"))).single()
        val edited = first.copy(parts = listOf(UIMessagePart.Text("edited")))
        assertFalse(TurnPromptReferences.captured(listOf(edited)))
        assertEquals("edited", rendered(listOf(edited)).single())
        assertTrue(rendered(capture(listOf(edited), "new", "new")).single().contains("new"))
    }

    @Test fun expandDoesNotDuplicateReferences() {
        val expanded = TurnPromptReferences.expand(capture(listOf(user("one"))))
        assertEquals(rendered(expanded), expanded.map { it.toText() })
    }

    @Test fun syntheticReminderIsNotAnAnchor() {
        val real = capture(listOf(user("one")))
        val history = real + user("clock").copy(isSynthetic = true)
        assertSame(history, capture(history, "changed", "changed"))
    }

    @Test fun persistedSnapshotSurvivesRestartAndNextTurn() {
        val original = capture(listOf(user("one")))
        val restored = Json.decodeFromString<List<UIMessage>>(Json.encodeToString(original))
        assertEquals(rendered(original), rendered(restored))
        val next = capture(restored + user("two"), "changed", "changed")
        assertEquals(rendered(original), rendered(next).take(original.size))
    }
}
