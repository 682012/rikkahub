package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * 真实文件持久层：格式、原子写、坏文件处理和收件箱恢复的组合行为。
 * 这里不 stub 序列化，[UIMessagePart] 走项目自己的 kotlinx.serialization。
 */
class FileConversationInboxStorageTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "inbox-store-${UUID.randomUUID()}")
            .apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test
    fun `missing file reads as an empty queue`() {
        assertNull(FileConversationInboxStorage(root, Uuid.random()).read())
    }

    @Test
    fun `creates the directory on first write`() {
        val storage = FileConversationInboxStorage(File(root, "nested/inbox"), Uuid.random())
        storage.write(InboxState(items = listOf(QueuedMessage(parts = text("hi")))))

        assertTrue(storage.file.isFile)
        assertNotNull(storage.read())
    }

    @Test
    fun `round trips the whole state with real part serialization`() {
        val conversationId = Uuid.random()
        val storage = FileConversationInboxStorage(root, conversationId)
        val message = QueuedMessage(
            parts = listOf(
                UIMessagePart.Text("hello"),
                UIMessagePart.Image("file:///upload/image.png"),
                UIMessagePart.Document("file:///upload/doc.pdf", "doc.pdf"),
            ),
            answer = false,
            isEditing = true,
        )
        val notice = QueuedNotice(
            key = "background",
            notice = UIMessagePart.Notice("title", "<notice>done</notice>"),
        )
        val inFlight = QueuedMessage(parts = text("sending"))
        storage.write(
            InboxState(
                items = listOf(message, notice),
                paused = true,
                pauseReason = InboxPauseReason.NETWORK,
                inFlight = inFlight,
            )
        )

        val loaded = storage.read()!!
        assertEquals(2, loaded.items.size)
        val loadedMessage = loaded.messages.single()
        assertEquals(message.id, loadedMessage.id)
        assertEquals(message.parts, loadedMessage.parts)
        assertFalse(loadedMessage.answer)
        assertTrue(loadedMessage.isEditing)
        // 内存观察者不能持久化，重启后也不会凭空出现
        assertNull(loadedMessage.reply)
        assertNull(loadedMessage.persisted)
        assertEquals(notice, loaded.notices.single())
        assertTrue(loaded.paused)
        assertEquals(InboxPauseReason.NETWORK, loaded.pauseReason)
        assertEquals(inFlight.id, loaded.inFlight!!.id)
        assertEquals(text("sending"), loaded.inFlight!!.parts)
        // tmp 文件不能残留
        assertEquals(listOf("$conversationId.json"), root.list()!!.toList())
    }

    @Test
    fun `writing again replaces the file without leaving temp files`() {
        val storage = FileConversationInboxStorage(root, Uuid.random())
        storage.write(InboxState(items = listOf(QueuedMessage(parts = text("old")))))
        storage.write(InboxState(items = listOf(QueuedMessage(parts = text("new")))))

        assertEquals(text("new"), storage.read()!!.messages.single().parts)
        assertEquals(1, root.list()!!.size)
    }

    @Test
    fun `corrupt file throws and is kept for recovery`() {
        val conversationId = Uuid.random()
        val storage = FileConversationInboxStorage(root, conversationId)
        val corrupt = """{"version":1,"conversationId":"$conversationId","state":{"items":["""
        storage.file.writeText(corrupt)

        assertTrue(runCatching { storage.read() }.exceptionOrNull() is IOException)
        assertTrue(storage.file.isFile)
        assertEquals(corrupt, storage.file.readText())

        val inbox = ConversationInbox(storage)
        assertTrue(runCatching { inbox.restore() }.exceptionOrNull() is IOException)
        assertTrue(inbox.state.value.items.isEmpty())
        assertTrue(storage.file.isFile)
    }

    @Test
    fun `oversized file is rejected instead of truncated`() {
        val storage = FileConversationInboxStorage(root, Uuid.random())
        val bytes = ByteArray((INBOX_FILE_MAX_BYTES + 1).toInt())
        storage.file.writeBytes(bytes)

        assertTrue(runCatching { storage.read() }.exceptionOrNull() is IOException)
        assertEquals(INBOX_FILE_MAX_BYTES + 1, storage.file.length())
    }

    @Test
    fun `inboxes are isolated per conversation`() {
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        val first = ConversationInbox(FileConversationInboxStorage(root, firstId))
        val second = ConversationInbox(FileConversationInboxStorage(root, secondId))
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertEquals(text("second"), second.nextMessage()!!.parts)

        val restored = ConversationInbox(FileConversationInboxStorage(root, firstId))
        restored.restore()
        assertTrue(restored.state.value.paused)
        assertNull(restored.nextMessage())
        assertEquals(text("first"), restored.state.value.messages.single().parts)
        assertEquals(setOf("$firstId.json", "$secondId.json"), root.list()!!.toSet())
    }

    @Test
    fun `restart over real files requeues the unacknowledged message`() {
        val conversationId = Uuid.random()
        val first = ConversationInbox(FileConversationInboxStorage(root, conversationId))
        first.enqueue(text("one"))
        first.enqueue(text("two"))
        val dispatched = first.nextMessage()!!
        assertEquals(text("one"), dispatched.parts)
        assertNotNull(FileConversationInboxStorage(root, conversationId).read()!!.inFlight)

        val restarted = ConversationInbox(FileConversationInboxStorage(root, conversationId))
        assertNotNull(restarted.restore())
        val resumed = restarted.nextMessage()!!
        assertEquals(text("one"), resumed.parts)
        assertTrue(restarted.acknowledge(resumed.id))

        // ack 以后磁盘上不再有 inFlight，再重启只剩没发的那条
        val third = ConversationInbox(FileConversationInboxStorage(root, conversationId))
        third.restore()
        assertEquals(text("two"), third.state.value.messages.single().parts)
        assertNull(third.state.value.inFlight)
    }
    @Test
    fun `empty acknowledged queues do not leave files to load at every startup`() {
        val storage = FileConversationInboxStorage(root, Uuid.random())
        storage.write(InboxState(items = listOf(QueuedMessage(parts = text("q")))))
        assertTrue(storage.file.isFile)
        storage.write(InboxState())
        assertFalse(storage.file.exists())
        assertNull(storage.read())
    }

}
