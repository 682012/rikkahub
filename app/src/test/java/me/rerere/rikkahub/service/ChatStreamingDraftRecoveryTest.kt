package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatStreamingDraftRecoveryTest {
    private fun reply(input: String) = UIMessage(role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool("write1", "workspace_write_file", input)))
    private fun ticket(partial: UIMessage, checkpoint: UIMessage? = null) = ChatReconnectTicket(
        failedEpoch = 1, incarnation = 0, userMessage = null, replyNodeId = Uuid.random(),
        checkpoint = checkpoint, failedTail = partial, interruptedMessageId = partial.id,
    )

    @Test fun partialCodeSurvivesAutomaticRecoveryAsSeparateVariant() {
        val raw = "{\"path\":\"/workspace/demo.kt\",\"text\":\"fun main() {\\n"
        val partial = reply(raw)
        val archive = ticket(partial).restoreReplyVariantsPreservingDrafts(listOf(partial)).single()
        assertNotEquals(partial.id, archive.id)
        assertEquals(raw, archive.getTools().single().input)
        assertEquals(partial.parts, archive.parts)
    }

    @Test fun executableCheckpointAndArchivedDraftHaveDifferentIds() {
        val checkpoint = reply("{}")
        val partial = checkpoint.copy(parts = checkpoint.parts + UIMessagePart.Text("unfinished code"))
        val restored = ticket(partial, checkpoint).restoreReplyVariantsPreservingDrafts(listOf(partial))
        assertEquals(checkpoint, restored.first())
        assertEquals(partial.parts, restored.last().parts)
        assertEquals(2, restored.map { it.id }.distinct().size)
    }

    @Test fun otherVariantsAreNotReplacedOrMutated() {
        val old = reply("{\"text\":\"older\"}")
        val partial = reply("{\"text\":\"new half")
        val restored = ticket(partial).restoreReplyVariantsPreservingDrafts(listOf(old, partial))
        assertSame(old, restored.first())
        assertEquals(2, restored.size)
        assertEquals(partial.parts, restored.last().parts)
    }

    @Test fun noReceivedCodeDoesNotCreateAnEmptyArchive() {
        val empty = reply("")
        assertTrue(ticket(empty).restoreReplyVariantsPreservingDrafts(listOf(empty)).isEmpty())
    }

    @Test fun partialToolStartDoesNotBecomeAnExecutableCheckpoint() {
        val checkpoint = ChatReconnectCheckpoint()
        val partial = reply("{\"text\":\"half").let { message -> message.copy(parts = message.getTools().map {
            it.copy(inputState = me.rerere.ai.ui.ToolInputState.STREAMING)
        }) }
        checkpoint.observe(partial)
        assertNull(checkpoint.message)
        assertTrue(checkpoint.canResume)
    }

    @Test fun completedSideEffectSurvivesAlongsideNewPartialArguments() {
        val started = UIMessagePart.Tool("done", "write_file", "{}", startedAt = kotlin.time.Instant.fromEpochSeconds(1))
        val checkpoint = ChatReconnectCheckpoint()
        checkpoint.observe(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(started)))
        assertFalse(checkpoint.canResume)
        val done = started.copy(output = listOf(UIMessagePart.Text("saved")))
        val partial = UIMessagePart.Tool("next", "workspace_write_file", "{\"text\":\"half",
            inputState = me.rerere.ai.ui.ToolInputState.STREAMING)
        checkpoint.observe(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(done, partial)))
        assertEquals(listOf(done), checkpoint.message!!.getTools())
        assertTrue(checkpoint.canResume)
    }

    @Test fun unchangedCompletedCheckpointIsNotDuplicated() {
        val complete = reply("{}")
        assertEquals(listOf(complete), ticket(complete, complete)
            .restoreReplyVariantsPreservingDrafts(listOf(complete)))
    }
}
