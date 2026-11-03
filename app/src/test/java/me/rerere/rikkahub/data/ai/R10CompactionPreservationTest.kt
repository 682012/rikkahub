package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R10CompactionPreservationTest {
    @Test fun ordinaryTextKeepsTheCacheStableRequestPath() {
        assertFalse(CompactionRequestMessages.requiresAdaptation(listOf(
            UIMessage.user("保留我的约束"), UIMessage.assistant("继续处理"))))
    }

    @Test fun toolNestedImageAlsoRequiresAdaptation() {
        val history = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool(toolCallId = "shot", toolName = "screenshot", input = "{}",
                output = listOf(UIMessagePart.Image("data:image/png;base64,DO_NOT_SEND"))))))
        assertTrue(CompactionRequestMessages.requiresAdaptation(history))
        assertFalse(CompactionRequestMessages.transcript(history).contains("DO_NOT_SEND"))
    }

    @Test fun largePreviousSummaryAndLateRequirementsAreNotCutBeforeSummarizing() {
        val summary = "previous-context-".repeat(3_000) + "KEEP_PREVIOUS_END"
        val requirement = "x".repeat(12_000) + "KEEP_MESSAGE_END"
        val text = CompactionRequestMessages.transcript(listOf(
            UIMessage.user(summary).copy(isContextCheckpoint = true), UIMessage.user(requirement)))
        assertTrue(text.contains(summary))
        assertTrue(text.contains(requirement))
    }

    @Test fun serverGeneratedImageJsonIsDescribedWithoutBinaryData() {
        val output = kotlinx.serialization.json.JsonObject(mapOf(
            "type" to kotlinx.serialization.json.JsonPrimitive("image_generation_call"),
            "result" to kotlinx.serialization.json.JsonPrimitive("SECRET_IMAGE_BYTES")))
        val part = UIMessagePart.ServerTool(toolCallId = "image_1", toolName = "image_generation",
            output = output, status = me.rerere.ai.ui.ServerToolStatus.COMPLETED)
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)))
        assertTrue(CompactionRequestMessages.requiresAdaptation(messages))
        val transcript = CompactionRequestMessages.transcript(messages)
        assertTrue(transcript.contains("image_generation"))
        assertTrue(transcript.contains("image bytes omitted"))
        assertFalse(transcript.contains("SECRET_IMAGE_BYTES"))
        assertTrue(part.output.toString().contains("SECRET_IMAGE_BYTES"))
    }
}
