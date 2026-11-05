package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

class ConversationAutoFileMutationTest {
    private val store = object : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation = { it }
        override suspend fun write(previous: Conversation?, current: Conversation) = Unit
    }

    @Test fun `metadata-only filing does not initialize or erase a long history`() = runTest {
        val id = Uuid.random()
        val target = Uuid.random()
        val full = Conversation.ofId(id).copy(messageNodes = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("keep the history"))).toMessageNode(),
        ))
        var disk = full
        val session = ConversationSession(id, Conversation.ofId(id), this, store, {})
        assertTrue(session.updateOrganizationMetadata(
            transform = { error("an unopened conversation must not be replaced by a metadata shell") },
            persist = { error("must use the metadata-only path") }, predicate = { false },
            unloadedPersist = { disk = disk.copy(folderId = target); true },
        ))
        assertFalse(session.isInitialized)
        session.initialize(load = { disk })
        assertEquals(target, session.state.value.folderId)
        assertEquals(full.messageNodes, session.state.value.messageNodes)
    }

    @Test fun `a rejected or failed database commit never publishes the proposed folder`() = runTest {
        val full = Conversation.ofId(Uuid.random())
        val session = ConversationSession(full.id, full, this, store, {})
        session.initialize(load = { full })
        assertFalse(session.updateOrganizationMetadata({ it.copy(folderId = Uuid.random()) }, { false }, { true }))
        assertSame(full, session.state.value)
        try {
            session.updateOrganizationMetadata({ it.copy(folderId = Uuid.random()) }, { throw IOException("disk") }, { true })
            fail("the failure must be visible")
        } catch (_: IOException) { }
        assertSame(full, session.state.value)
        assertFalse(session.isAutoFiling)
    }

    @Test fun `another observer or queued input blocks automatic filing`() = runTest {
        val full = Conversation.ofId(Uuid.random())
        val session = ConversationSession(full.id, full, this, store, {})
        session.initialize(load = { full })
        session.acquire()
        session.acquire()
        assertFalse(session.updateOrganizationMetadata({ it }, { fail("must not write"); true }, { true }))
        session.release()
        session.release()
        session.inbox.enqueue(listOf(UIMessagePart.Text("a new message")))
        assertFalse(session.updateOrganizationMetadata({ it }, { fail("queued input must not move"); true }, { true }))
        session.cleanup()
    }

    @Test fun `an in-flight commit blocks stale mutations and releases the guard afterwards`() = runTest {
        val full = Conversation.ofId(Uuid.random())
        val target = Uuid.random()
        val session = ConversationSession(full.id, full, this, store, {})
        session.initialize(load = { full })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            session.updateOrganizationMetadata({ it.copy(folderId = target) }, {
                entered.complete(Unit); release.await(); true
            }, { true })
        }
        entered.await()
        assertTrue(session.isAutoFiling)
        assertNull(session.tryUpdate { it.copy(title = "a stale snapshot") })
        release.complete(Unit)
        job.join()
        runCurrent()
        assertFalse(session.isAutoFiling)
        assertEquals(target, session.state.value.folderId)
        assertEquals(full.title, session.state.value.title)
    }
}
