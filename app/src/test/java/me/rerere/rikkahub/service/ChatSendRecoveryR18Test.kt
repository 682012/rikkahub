package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.draft.ChatSendDraftPolicy
import me.rerere.rikkahub.data.model.ChatDraft
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ChatSendRecoveryR18Test {
    private val key = "conversation:${Uuid.random()}"
    private fun text(value: String) = listOf(UIMessagePart.Text(value))
    private fun user(value: String) = UIMessage(role = MessageRole.USER, parts = text(value))

    @Test fun `busy model does not delay clearing durably accepted input`() {
        val storage = MemoryInboxStorage()
        val inbox = ConversationInbox(storage)
        val databaseReceipt = CompletableDeferred<Boolean>()
        val accepted = inbox.enqueue(text("排队的文字"), persisted = databaseReceipt)
        assertNotNull(accepted)
        assertFalse(databaseReceipt.isCompleted)
        assertEquals(text("排队的文字"), storage.state!!.messages.single().parts)
        assertEquals(ChatDraft(), ChatSendDraftPolicy.remainingInputAfterAcceptance(key, key, true,
            ChatDraft(text = "排队的文字"), ChatDraft(text = "排队的文字")))
    }

    @Test fun `acceptance removes submitted prefix but preserves new text and attachments`() {
        val a = UIMessagePart.Image("file:///a.png")
        val b = UIMessagePart.Image("file:///b.png")
        val sent = ChatDraft(text = "已发送", parts = listOf(a))
        val current = ChatDraft(text = "已发送新输入", parts = listOf(a, b), selectionStart = 6, selectionEnd = 6)
        val remaining = ChatSendDraftPolicy.remainingInputAfterAcceptance(key, key, true, current, sent)!!
        assertEquals("新输入", remaining.text)
        assertEquals(listOf(b), remaining.parts)
        assertNull(ChatSendDraftPolicy.remainingInputAfterAcceptance(key, "conversation:other", true, current, sent))
        assertNull(ChatSendDraftPolicy.remainingInputAfterAcceptance(key, key, false, current, sent))
    }

    @Test fun `editing existing submitted text is not interpreted as an appended suffix`() {
        assertNull(ChatSendDraftPolicy.remainingInputAfterAcceptance(key, key, true,
            ChatDraft(text = "重新改过的内容"), ChatDraft(text = "原内容")))
    }

    @Test fun `first mutation of an unopened inbox restores older queued input`() {
        val storage = MemoryInboxStorage()
        ConversationInbox(storage).enqueue(text("旧消息"))
        val reopened = ConversationInbox(storage)
        reopened.enqueue(text("新消息"))
        assertEquals(listOf("旧消息", "新消息"), reopened.state.value.messages.map { (it.parts.single() as UIMessagePart.Text).text })
        val sent = (reopened.takeNext() as InboxTurn.Message).message
        reopened.restore() // UI/another callback may ask for restoration again while dispatch is running.
        assertEquals(sent.id, reopened.state.value.inFlight!!.id)
        assertEquals(1, reopened.state.value.messages.size)
    }

    @Test fun `pending queue keeps original assistant and network cannot resume user stop`() {
        val storage = MemoryInboxStorage()
        val a = Uuid.random()
        val queue = ConversationInbox(storage)
        queue.bindOwner(a)
        queue.enqueue(text("任务"))
        assertTrue(runCatching { queue.bindOwner(Uuid.random()) }.isFailure)
        val reopened = ConversationInbox(storage)
        reopened.restore()
        assertEquals(a, reopened.state.value.ownerAssistantId)
        reopened.pause(InboxPauseReason.USER)
        assertFalse(reopened.resumeAfterNetwork())
        assertNull(reopened.takeNext())
    }

    @Test fun `half streamed text is not the checkpoint for a reconnect`() {
        val state = ChatReconnectCheckpoint()
        state.observe(UIMessage(role = MessageRole.ASSISTANT, parts = text("半截")))
        assertNull(state.message)
        assertTrue(state.canResume)
        val complete = UIMessage(role = MessageRole.ASSISTANT, parts = text("完成的模型步骤"),
            finishedAt = LocalDateTime(2026, 11, 5, 0, 0, 1))
        state.observe(complete)
        state.observe(complete.copy(parts = complete.parts + text("下一步的半截流")))
        assertEquals(complete, state.message)
    }

    @Test fun `started tool is never replayed but its finished result survives reconnect`() {
        val tool = UIMessagePart.Tool("id", "write_file", "{}", startedAt = Instant.fromEpochSeconds(1))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool),
            finishedAt = LocalDateTime(2026, 11, 5, 0, 0, 1))
        val state = ChatReconnectCheckpoint()
        state.observe(message)
        assertFalse(state.canResume)
        val done = message.copy(parts = listOf(tool.copy(output = text("已写入"))))
        state.observe(done)
        assertTrue(state.canResume)
        state.observe(done.copy(parts = done.parts + text("半截回复")))
        assertEquals(done, state.message)
    }

    @Test fun `only new route and unchanged conversation can resume a failed turn`() {
        val original = user("A")
        val ticket = ChatReconnectTicket(7, 2, original, Uuid.random(), null)
        assertTrue(ticket.canResume(8, true, 2, original))
        assertFalse(ticket.canResume(7, true, 2, original))
        assertFalse(ticket.canResume(8, false, 2, original))
        assertFalse(ticket.canResume(8, true, 3, original))
        assertFalse(ticket.canResume(8, true, 2, user("B")))
    }
}
