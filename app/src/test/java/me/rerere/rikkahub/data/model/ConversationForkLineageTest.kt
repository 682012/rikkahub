package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * fork 谱系：分叉挂在同一家族根上，根自己不用回写。
 *
 * 折叠列表按「同助手 + 同文件夹 + 同根」合卡，所以 fork 之后 `lineageId` 必须指回源会话的根；
 * 源会话本身是旧数据（lineageId 为空）时，源就是根，也不需要为了记根去改源。
 */
class ConversationForkLineageTest {

    private fun source(
        lineageId: Uuid? = null,
        locked: Boolean = false,
    ): Pair<Conversation, UIMessage> {
        val message = UIMessage.user("hello")
        return Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = listOf(message.toMessageNode()),
            folderId = Uuid.random(),
            lineageId = lineageId,
            folderAssignmentLocked = locked,
        ) to message
    }

    @Test
    fun `fork of a root conversation hangs under the source id`() {
        val (source, message) = source()

        val fork = source.fork(message.id, emptySet()) { it }!!

        assertEquals(source.id, fork.lineageId)
        assertEquals(source.id, fork.lineageRootId)
        // 源自己保持「没记根」，不为一条分叉去改旧会话的元信息
        assertNull(source.lineageId)
        assertEquals(source.id, source.lineageRootId)
    }

    @Test
    fun `fork of a fork keeps the original root`() {
        val root = Uuid.random()
        val (source, message) = source(lineageId = root)

        val fork = source.fork(message.id, emptySet()) { it }!!
        val forkMessage = fork.messageNodes.first().messages.first()
        val forkOfFork = fork.fork(forkMessage.id, setOf(fork.title)) { it }!!

        assertEquals(root, fork.lineageId)
        assertEquals(root, forkOfFork.lineageId)
        assertEquals(root, forkOfFork.lineageRootId)
    }

    @Test
    fun `fork keeps message ids and only renews node ids`() {
        val first = UIMessage.user("question")
        val answer = UIMessage.assistant("answer")
        val source = Conversation.ofId(
            Uuid.random(),
            messages = listOf(first.toMessageNode(), answer.toMessageNode()),
        )

        val fork = source.fork(answer.id, emptySet()) { it }!!

        assertEquals(listOf(first.id, answer.id), fork.messageNodes.map { it.messages.first().id })
        assertTrue(source.messageNodes.map { it.id }.none { it in fork.messageNodes.map { node -> node.id } })
    }

    @Test
    fun `fork keeps local attachments through the copy callback`() {
        val image = UIMessagePart.Image(url = "file:///old/a.png")
        val message = UIMessage.user("look").copy(parts = listOf(image))
        val source = Conversation.ofId(Uuid.random(), messages = listOf(message.toMessageNode()))

        val fork = source.fork(message.id, emptySet()) { url -> url.replace("/old/", "/new/") }!!

        val part = fork.messageNodes.first().messages.first().parts.filterIsInstance<UIMessagePart.Image>().first()
        assertEquals("file:///new/a.png", part.url)
        assertEquals("file:///old/a.png", image.url)
    }

    @Test
    fun `fork does not inherit the folder assignment lock`() {
        val (source, message) = source(locked = true)

        val fork = source.fork(message.id, emptySet()) { it }!!

        assertTrue(source.folderAssignmentLocked)
        assertFalse("分叉是新的一条会话，归到哪儿由它自己重新决定", fork.folderAssignmentLocked)
        assertEquals("单条会话的家族数就是 1", 1, fork.familySize)
        assertEquals("分叉落到同一个文件夹", source.folderId, fork.folderId)
    }
}
