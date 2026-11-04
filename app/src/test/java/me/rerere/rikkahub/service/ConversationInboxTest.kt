package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

/** 只关心用户输入的测试用：下一轮是发送用户输入时返回它 */
internal fun ConversationInbox.nextMessage(): QueuedMessage? = (takeNext() as? InboxTurn.Message)?.message

internal fun ConversationInbox.removeMessage(id: Uuid): QueuedMessage? = remove(id) as QueuedMessage?

/** 只存最近一次写入的内存持久层；[failWrites] 模拟磁盘满/权限失败 */
internal class MemoryInboxStorage : ConversationInboxStorage {
    var state: InboxState? = null
    var writes: Int = 0
    var failWrites: Boolean = false

    override fun read(): InboxState? = state

    override fun write(state: InboxState) {
        if (failWrites) throw IOException("disk full")
        this.state = state
        writes++
    }
}

private fun List<InboxItem>.texts(): List<String> =
    map { (it as QueuedMessage).parts.single() as UIMessagePart.Text }.map { it.text }

class ConversationInboxTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    private fun notice(name: String) = UIMessagePart.Notice(title = name, text = "<notice>$name</notice>")

    @Test
    fun `editing a voice message preserves its reply observer and queue position`() {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("original"), reply = reply)
        queue.enqueue(text("later"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        assertNull(queue.nextMessage())
        queue.finishEdit(id, text("edited"))
        val dispatched = queue.nextMessage()!!
        assertEquals(text("edited"), dispatched.parts)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("later"), queue.nextMessage()!!.parts)
    }

    @Test
    fun `pausing resolves voice observers but retains queued content for manual resume`() = runBlocking {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("keep me"), reply = reply)
        queue.pause()
        assertTrue(reply.isCompleted)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)
        queue.resume()
        assertEquals(text("keep me"), queue.nextMessage()!!.parts)
    }

    @Test
    fun `tool approval can release reply observers without removing or resuming messages`() = runBlocking {
        val queue = ConversationInbox()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = reply)
        queue.failReplyWaiters("approval required")
        assertEquals("approval required", runCatching { reply.await() }.exceptionOrNull()?.message)
        assertEquals(1, queue.state.value.messages.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun `withdrawing a queued message reports that it was never persisted`() = runBlocking {
        val queue = ConversationInbox()
        val persisted = CompletableDeferred<Boolean>()
        queue.enqueue(text("withdrawn"), persisted = persisted)
        val id = queue.state.value.messages.single().id

        assertTrue(queue.remove(id) != null)
        assertTrue(persisted.isCompleted)
        assertFalse(persisted.await())
    }

    @Test
    fun `pausing and approval keep persistence receipts alive until resume or removal`() = runBlocking {
        val queue = ConversationInbox()
        val pausedReceipt = CompletableDeferred<Boolean>()
        queue.enqueue(text("paused"), persisted = pausedReceipt)
        queue.pause()
        assertFalse(pausedReceipt.isCompleted)

        val failedReceipt = CompletableDeferred<Boolean>()
        queue.enqueue(text("failed"), persisted = failedReceipt)
        queue.failReplyWaiters("approval required")
        assertFalse(failedReceipt.isCompleted)
        assertEquals(2, queue.state.value.messages.size)
        queue.resume()
        val first = queue.nextMessage()!!
        first.persisted?.complete(true)
        assertTrue(pausedReceipt.await())
        queue.remove(queue.state.value.messages.single().id)
        assertFalse(failedReceipt.await())
    }

    @Test
    fun `empty input is rejected without leaving a pending receipt`() = runBlocking {
        val queue = ConversationInbox()
        val persisted = CompletableDeferred<Boolean>()
        queue.enqueue(emptyList(), persisted = persisted)

        assertTrue(persisted.isCompleted)
        assertFalse(persisted.await())
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.nextMessage()!!.parts)
        val second = queue.nextMessage()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.nextMessage())
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        queue.beginEdit(id)
        assertNull(queue.nextMessage())
        queue.finishEdit(id, text("edited"))

        val first = queue.nextMessage()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.nextMessage()!!.parts)
    }

    @Test
    fun `editing a later message does not block earlier messages`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.beginEdit(queue.state.value.messages.last().id)

        assertEquals(text("first"), queue.nextMessage()!!.parts)
        assertNull(queue.nextMessage())
    }

    @Test
    fun `cancelling an edit restores the original input and attachments`() {
        val queue = ConversationInbox()
        val parts = text("question") + UIMessagePart.Image("file:///test.png")
        queue.enqueue(parts)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id)

        assertEquals(parts, queue.nextMessage()!!.parts)
    }

    @Test
    fun `removing a message skips it without changing following input`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.removeMessage(queue.state.value.messages.first().id)

        assertEquals(text("second"), queue.nextMessage()!!.parts)
        assertNull(queue.nextMessage())
    }

    @Test
    fun `new input and edits cannot silently resume a paused queue`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        queue.pause()
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))

        assertTrue(queue.state.value.paused)
        assertNull(queue.nextMessage())
        queue.resume()
        assertEquals(text("edited"), queue.nextMessage()!!.parts)
        assertEquals(text("second"), queue.nextMessage()!!.parts)
    }

    @Test
    fun `late edit cannot recreate a removed or dispatched message`() {
        val queue = ConversationInbox()
        queue.enqueue(text("first"))
        val id = queue.nextMessage()!!.id

        assertNull(queue.beginEdit(id))
        queue.finishEdit(id, text("late"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rejects empty input but accepts attachment only input`() {
        val queue = ConversationInbox()
        queue.enqueue(text("  "))
        queue.enqueue(emptyList())
        assertNull(queue.nextMessage())

        val parts = listOf(UIMessagePart.Image("file:///test.png"))
        queue.enqueue(parts)
        assertEquals(parts, queue.nextMessage()!!.parts)
    }

    @Test
    fun `submission snapshots caller owned list`() {
        val queue = ConversationInbox()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts)
        parts.clear()

        assertEquals(text("original"), queue.nextMessage()!!.parts)
    }

    @Test
    fun `queues are isolated per conversation`() {
        val first = ConversationInbox()
        val second = ConversationInbox()
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertNull(first.nextMessage())
        assertEquals(text("second"), second.nextMessage()!!.parts)
    }

    @Test
    fun `notices start a turn only when no user input is waiting`() {
        val inbox = ConversationInbox()
        inbox.post("a", notice("a"))
        inbox.enqueue(text("question"))

        assertEquals(text("question"), (inbox.takeNext() as InboxTurn.Message).message.parts)
        assertEquals(InboxTurn.Notices, inbox.takeNext())
        // 开始一轮不会取走通知，由那一轮自己取
        assertEquals(listOf(notice("a")), inbox.takeNotices())
        assertNull(inbox.takeNext())
    }

    @Test
    fun `pausing holds back notice turns but a running turn can still take them`() {
        val inbox = ConversationInbox()
        inbox.post("a", notice("a"))
        inbox.pause()

        assertNull(inbox.takeNext())
        assertEquals(listOf(notice("a")), inbox.takeNotices())
        assertTrue(inbox.state.value.items.isEmpty())
    }

    @Test
    fun `an edited head blocks later input but not notices`() {
        val inbox = ConversationInbox()
        inbox.enqueue(text("first"))
        inbox.beginEdit(inbox.state.value.messages.single().id)
        inbox.post("a", notice("a"))

        assertEquals(InboxTurn.Notices, inbox.takeNext())
    }

    @Test
    fun `withdrawing removes only the unsent notice with that key`() {
        val inbox = ConversationInbox()
        inbox.post("a", notice("a"))
        inbox.post("b", notice("b"))
        inbox.withdraw("a")
        inbox.withdraw("missing")

        assertEquals(listOf(notice("b")), inbox.takeNotices())
    }

    @Test
    fun `posting the same key again replaces the notice in place`() {
        val inbox = ConversationInbox()
        inbox.post("a", notice("old"))
        inbox.enqueue(text("question"))
        inbox.post("a", notice("new"))

        assertEquals(listOf("a", null), inbox.state.value.items.map { (it as? QueuedNotice)?.key })
        assertEquals(listOf(notice("new")), inbox.takeNotices())
    }

    @Test
    fun `removing a notice drops it`() {
        val inbox = ConversationInbox()
        inbox.post("a", notice("a"))
        val removed = inbox.remove(inbox.state.value.items.single().id)

        assertTrue(removed is QueuedNotice)
        assertTrue(inbox.takeNotices().isEmpty())
    }

    // ---- 不传 storage：保持旧行为 ----

    @Test
    fun `inboxes without storage keep the legacy dispatch semantics`() {
        val inbox = ConversationInbox()
        inbox.enqueue(text("first"))
        inbox.enqueue(text("second"))

        assertEquals(text("first"), inbox.nextMessage()!!.parts)
        assertNull(inbox.state.value.inFlight)
        assertEquals(text("second"), inbox.nextMessage()!!.parts)
        assertNull(inbox.state.value.inFlight)
        assertNull(inbox.nextMessage())
    }

    @Test
    fun `restore without storage changes nothing`() {
        val inbox = ConversationInbox()
        inbox.enqueue(text("queued"))

        assertNull(inbox.restore())
        assertEquals(1, inbox.state.value.messages.size)
    }

    @Test
    fun `enqueue returns the accepted message and null for empty input`() = runBlocking {
        val inbox = ConversationInbox()
        val accepted = inbox.enqueue(text("hello"))
        assertNotNull(accepted)
        assertEquals(accepted!!.id, inbox.state.value.messages.single().id)

        val rejected = inbox.enqueue(emptyList())
        assertNull(rejected)
        assertEquals(1, inbox.state.value.messages.size)
    }

    // ---- 持久化：受理即落盘 ----

    @Test
    fun `accepted input is written to storage before it becomes visible`() {
        val storage = MemoryInboxStorage()
        val inbox = ConversationInbox(storage)

        val accepted = inbox.enqueue(text("hello"))!!

        assertEquals(1, storage.writes)
        assertEquals(listOf(accepted.id), storage.state!!.messages.map { it.id })
        assertEquals(text("hello"), storage.state!!.messages.single().parts)
    }

    @Test
    fun `enqueue with a stable id does not duplicate the queue after restart`() {
        val storage = MemoryInboxStorage()
        val id = Uuid.random()
        val first = ConversationInbox(storage)
        assertNotNull(first.enqueue(text("stable"), id = id))

        val restarted = ConversationInbox(storage)
        restarted.restore()
        assertNotNull(restarted.enqueue(text("stable"), id = id))
        assertEquals(1, restarted.state.value.messages.size)

        // 正在发送的那条同样不能被同一个 id 再塞一遍
        val dispatched = restarted.nextMessage()!!
        assertEquals(id, dispatched.id)
        assertNotNull(restarted.enqueue(text("stable"), id = id))
        assertTrue(restarted.state.value.messages.isEmpty())
    }

    @Test
    fun `restart restores queued order and requeues the in-flight message`() {
        val storage = MemoryInboxStorage()
        val first = ConversationInbox(storage)
        first.enqueue(text("one"))
        first.enqueue(text("two"))
        first.enqueue(text("three"))
        assertEquals(text("one"), first.nextMessage()!!.parts)
        assertEquals(text("one"), storage.state!!.inFlight!!.parts)

        val restarted = ConversationInbox(storage)
        restarted.restore()
        assertEquals(listOf("one", "two", "three"), restarted.state.value.messages.texts())

        val resumed = restarted.nextMessage()!!
        assertEquals(text("one"), resumed.parts)
        assertFalse(resumed.isEditing)
        assertTrue(restarted.acknowledge(resumed.id))
        assertEquals(text("two"), restarted.nextMessage()!!.parts)
    }

    @Test
    fun `restart clears editing placeholders so the queue cannot stay stuck`() {
        val storage = MemoryInboxStorage()
        val first = ConversationInbox(storage)
        first.enqueue(text("editing"))
        first.beginEdit(first.state.value.messages.single().id)
        assertNull(first.nextMessage())

        val restarted = ConversationInbox(storage)
        restarted.restore()
        assertEquals(InboxPauseReason.USER, restarted.state.value.pauseReason)
        assertNull(restarted.nextMessage())
        assertFalse(restarted.resumeAfterNetwork())
        restarted.resume()
        assertEquals(text("editing"), restarted.nextMessage()!!.parts)
    }

    @Test
    fun `restart keeps the pause reason so only the right action resumes`() {
        val storage = MemoryInboxStorage()
        val first = ConversationInbox(storage)
        first.enqueue(text("queued"))
        first.pause(InboxPauseReason.NETWORK)

        val restarted = ConversationInbox(storage)
        restarted.restore()
        assertTrue(restarted.state.value.paused)
        assertEquals(InboxPauseReason.NETWORK, restarted.state.value.pauseReason)
        assertTrue(restarted.resumeAfterNetwork())
        assertEquals(text("queued"), restarted.nextMessage()!!.parts)
    }

    @Test
    fun `notices are persisted and restored`() {
        val storage = MemoryInboxStorage()
        val first = ConversationInbox(storage)
        first.post("a", notice("a"))
        first.post("b", notice("b"))
        assertEquals(2, storage.state!!.notices.size)

        val restarted = ConversationInbox(storage)
        restarted.restore()
        assertEquals(listOf(notice("a"), notice("b")), restarted.takeNotices())
        assertTrue(storage.state!!.items.isEmpty())
    }

    // ---- 写失败：不改内存、不发假回执 ----

    @Test
    fun `failed write rejects the submission instead of faking acceptance`() = runBlocking {
        val storage = MemoryInboxStorage()
        val inbox = ConversationInbox(storage)
        assertNotNull(inbox.enqueue(text("kept")))
        val before = inbox.state.value
        val writesBefore = storage.writes
        storage.failWrites = true

        val receipt = CompletableDeferred<Boolean>()
        val reply = CompletableDeferred<String?>()
        val error = runCatching {
            inbox.enqueue(text("lost"), reply = reply, persisted = receipt)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(before, inbox.state.value)
        assertEquals(writesBefore, storage.writes)
        assertTrue(receipt.isCompleted)
        assertFalse(receipt.await())
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IOException)
    }

    @Test
    fun `failed dispatch keeps the message at the head`() {
        val storage = MemoryInboxStorage()
        val inbox = ConversationInbox(storage)
        inbox.enqueue(text("keep"))
        storage.failWrites = true

        assertTrue(runCatching { inbox.nextMessage() }.exceptionOrNull() is IOException)
        assertEquals(1, inbox.state.value.messages.size)
        assertNull(inbox.state.value.inFlight)

        storage.failWrites = false
        assertEquals(text("keep"), inbox.nextMessage()!!.parts)
    }

    @Test
    fun `failed removal keeps the message and leaves the draft receipt pending`() {
        val storage = MemoryInboxStorage()
        val inbox = ConversationInbox(storage)
        val receipt = CompletableDeferred<Boolean>()
        inbox.enqueue(text("keep"), persisted = receipt)
        val id = inbox.state.value.messages.single().id
        storage.failWrites = true

        assertTrue(runCatching { inbox.remove(id) }.exceptionOrNull() is IOException)
        assertEquals(1, inbox.state.value.messages.size)
        assertFalse(receipt.isCompleted)
    }

    // ---- 暂停原因 ----

    @Test
    fun `manual pause is not cleared by network recovery`() {
        val inbox = ConversationInbox(MemoryInboxStorage())
        inbox.enqueue(text("queued"))
        inbox.pause()

        assertFalse(inbox.resumeAfterNetwork())
        assertTrue(inbox.state.value.paused)
        assertNull(inbox.nextMessage())

        inbox.resume()
        assertEquals(text("queued"), inbox.nextMessage()!!.parts)
    }

    @Test
    fun `only a network pause is resumed by network recovery`() {
        val storage = MemoryInboxStorage()
        val inbox = ConversationInbox(storage)
        inbox.enqueue(text("queued"))

        inbox.pause(InboxPauseReason.ERROR)
        assertEquals(InboxPauseReason.ERROR, storage.state!!.pauseReason)
        assertFalse(inbox.resumeAfterNetwork())
        assertTrue(inbox.state.value.paused)

        inbox.resume()
        assertFalse(inbox.state.value.paused)
        assertEquals(InboxPauseReason.NONE, storage.state!!.pauseReason)

        inbox.pause(InboxPauseReason.NETWORK)
        assertTrue(inbox.resumeAfterNetwork())
        assertFalse(inbox.state.value.paused)
        assertEquals(text("queued"), inbox.nextMessage()!!.parts)
    }

    @Test
    fun `network pause cannot downgrade a manual stop`() {
        val inbox = ConversationInbox(MemoryInboxStorage())
        inbox.enqueue(text("queued"))

        inbox.pause(InboxPauseReason.NETWORK)
        assertTrue(inbox.resumeAfterNetwork())

        // 网络暂停期间又出错：升级成 ERROR，网络恢复不能再自动放行
        inbox.pause(InboxPauseReason.NETWORK)
        inbox.pause(InboxPauseReason.ERROR)
        assertEquals(InboxPauseReason.ERROR, inbox.state.value.pauseReason)
        assertFalse(inbox.resumeAfterNetwork())

        // 人工停止最硬：后面的网络中断不能把它降级成可自动恢复
        inbox.pause(InboxPauseReason.USER)
        inbox.pause(InboxPauseReason.NETWORK)
        inbox.pause(InboxPauseReason.ERROR)
        assertEquals(InboxPauseReason.USER, inbox.state.value.pauseReason)
        assertFalse(inbox.resumeAfterNetwork())
        assertTrue(inbox.state.value.paused)
    }

    // ---- inFlight 事务 ----

    @Test
    fun `dispatch waits for acknowledgement before taking the next input`() {
        val storage = MemoryInboxStorage()
        val inbox = ConversationInbox(storage)
        inbox.enqueue(text("one"))
        inbox.enqueue(text("two"))
        val first = inbox.nextMessage()!!

        // 上一条还没 ack：不能覆盖 inFlight，否则那条输入就丢了
        assertNull(inbox.nextMessage())
        assertTrue(inbox.acknowledge(first.id))
        assertEquals(text("two"), inbox.nextMessage()!!.parts)
        assertFalse(inbox.acknowledge(first.id))
    }

    @Test
    fun `failed send puts the message back at the head for retry`() {
        val storage = MemoryInboxStorage()
        val inbox = ConversationInbox(storage)
        inbox.enqueue(text("one"))
        inbox.enqueue(text("two"))
        val dispatched = inbox.nextMessage()!!

        val requeued = inbox.requeueInFlight("network")!!
        assertEquals(dispatched.id, requeued.id)
        assertNull(storage.state!!.inFlight)
        assertEquals(listOf("one", "two"), inbox.state.value.messages.texts())

        val one = inbox.nextMessage()!!
        assertTrue(inbox.acknowledge(one.id))
        assertEquals(text("two"), inbox.nextMessage()!!.parts)
    }
}
