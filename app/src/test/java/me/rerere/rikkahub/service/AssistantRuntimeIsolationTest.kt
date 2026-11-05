package me.rerere.rikkahub.service

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.uuid.Uuid

class AssistantRuntimeIsolationTest {
    private fun notice() = UIMessagePart.Notice("done", "background result")

    @Test fun `passive completion survives restart without starting a paid turn`() {
        val dir = Files.createTempDirectory("passive-inbox").toFile()
        try {
            val id = Uuid.random()
            val first = ConversationInbox(FileConversationInboxStorage(dir, id))
            first.post("background_tool:one", notice(), wakeOnArrival = false)
            first.post("background_tool:one", notice(), wakeOnArrival = false)
            val restored = ConversationInbox(FileConversationInboxStorage(dir, id))
            restored.restore()
            assertEquals(1, restored.state.value.notices.size)
            assertNull(restored.takeNext())
            assertEquals(1, restored.takeNotices().size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `explicit ownership transfer drops old notices and rejects late delivery`() {
        val inbox = ConversationInbox()
        val target = Uuid.random()
        inbox.bindOwner(Uuid.random())
        inbox.post("subagent:old", notice(), wakeOnArrival = false)
        inbox.migrateOwner(target, setOf("background_tool:old"))
        assertEquals(target, inbox.state.value.ownerAssistantId)
        assertTrue(inbox.state.value.items.isEmpty())
        assertNull(inbox.post("subagent:old", notice()))
        assertNull(inbox.post("background_tool:old", notice()))
        assertNotNull(inbox.post("subagent:new", notice(), wakeOnArrival = false))
        assertNull(inbox.takeNext())
    }

    @Test fun `ownership transfer cannot discard pending user messages`() {
        val inbox = ConversationInbox()
        val owner = Uuid.random()
        inbox.bindOwner(owner)
        inbox.enqueue(listOf(UIMessagePart.Text("keep me")))
        try { inbox.migrateOwner(Uuid.random()); fail("must refuse") }
        catch (_: IllegalStateException) { }
        assertEquals(owner, inbox.state.value.ownerAssistantId)
        assertEquals(1, inbox.state.value.messages.size)
    }

    @Test fun `unreferenced observer session is evicted but pending work is retained`() = runTest {
        val manager = ConversationSessionManager(
            scope = this, store = FakeConversationStore(),
            createInitialConversation = { Conversation.ofId(it) },
            onGenerationFinished = { _, _ -> },
        )
        val empty = manager.getOrCreate(Uuid.random())
        val passive = manager.getOrCreate(Uuid.random())
        passive.inbox.post("done", notice(), wakeOnArrival = false)
        val queued = manager.getOrCreate(Uuid.random())
        queued.inbox.enqueue(listOf(UIMessagePart.Text("pending")))
        advanceTimeBy(2_001)
        runCurrent()
        assertNull(manager.get(empty.id))
        assertNull(manager.get(passive.id))
        assertSame(queued, manager.get(queued.id))
        manager.cleanup()
    }
}
