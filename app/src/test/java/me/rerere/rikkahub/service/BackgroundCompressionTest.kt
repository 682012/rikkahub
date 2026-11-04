package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.insertContextCheckpoint
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 后台压缩的提交校验：只比较压缩范围（含范围内最后一个节点）的节点/分支/消息身份。
 * 这里覆盖“尾部新增可以保留”和“范围内变了必须丢弃”两类情形。
 */
class BackgroundCompressionTest {
    private fun userNode(text: String) = UIMessage.user(text).toMessageNode()

    private fun assistantNode(text: String) = UIMessage.assistant(text).toMessageNode()

    private fun conversationOf(vararg nodes: MessageNode) =
        Conversation(assistantId = Uuid.random(), messageNodes = nodes.toList())

    @Test
    fun `editing text without changing the message id discards the stale summary`() {
        val node = userNode("before")
        val conversation = conversationOf(node)
        val snapshot = requireNotNull(conversation.compressionSnapshot(1, 0))
        val changed = conversation.copy(messageNodes = listOf(node.copy(messages = listOf(
            node.currentMessage.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("after"))),
        ))))
        assertEquals(CompressionCommit.Changed, changed.compressionCommit(snapshot, 0))
    }

    @Test
    fun `messages appended during compression stay after the checkpoint`() {
        val a = userNode("a")
        val b = assistantNode("b")
        val c = userNode("c")
        val original = conversationOf(a, b, c)
        val snapshot = requireNotNull(original.compressionSnapshot(endIndex = 3, incarnation = 0))

        // 压缩请求还没回来时用户又发了一条
        val appended = original.copy(messageNodes = original.messageNodes + userNode("d"))
        val commit = appended.compressionCommit(snapshot, currentIncarnation = 0)
        assertTrue(commit is CompressionCommit.Ready)

        val updated = requireNotNull(
            appended.insertContextCheckpoint((commit as CompressionCommit.Ready).afterNodeId, "summary")
        )
        assertEquals(5, updated.messageNodes.size)
        // 前三节点原样保留，摘要在范围末尾，新消息仍在摘要之后
        assertEquals(listOf(a.id, b.id, c.id), updated.messageNodes.take(3).map { it.id })
        assertTrue(updated.messageNodes[3].currentMessage.isContextCheckpoint)
        assertEquals(appended.messageNodes.last().id, updated.messageNodes[4].id)
    }

    @Test
    fun `tail beyond keep recent messages is preserved untouched`() {
        val a = userNode("a")
        val b = assistantNode("b")
        val c = userNode("c")
        val d = assistantNode("d")
        val conversation = conversationOf(a, b, c, d)
        // 保留最近两条：只压缩前两个节点
        val snapshot = requireNotNull(conversation.compressionSnapshot(endIndex = 2, incarnation = 0))
        val commit = conversation.compressionCommit(snapshot, currentIncarnation = 0)
        assertTrue(commit is CompressionCommit.Ready)

        val updated = requireNotNull(
            conversation.insertContextCheckpoint((commit as CompressionCommit.Ready).afterNodeId, "summary")
        )
        assertEquals(5, updated.messageNodes.size)
        assertEquals(b.id, updated.messageNodes[1].id)
        assertTrue(updated.messageNodes[2].currentMessage.isContextCheckpoint)
        assertEquals(listOf(c.id, d.id), updated.messageNodes.drop(3).map { it.id })
    }

    @Test
    fun `switching a branch inside the range drops the stale summary`() {
        val a = userNode("a")
        val firstReply = assistantNode("first")
        val secondReply = assistantNode("second")
        val branchNode = MessageNode(messages = listOf(firstReply.currentMessage, secondReply.currentMessage), selectIndex = 0)
        val conversation = conversationOf(a, branchNode)
        val snapshot = requireNotNull(conversation.compressionSnapshot(endIndex = 2, incarnation = 0))
        assertEquals(CompressionCommit.Ready(branchNode.id), conversation.compressionCommit(snapshot, 0))

        val switched = conversation.copy(
            messageNodes = listOf(a, branchNode.copy(selectIndex = 1)),
        )
        assertEquals(CompressionCommit.Changed, switched.compressionCommit(snapshot, 0))
    }

    @Test
    fun `adding a branch without switching keeps the summary valid`() {
        val a = userNode("a")
        val reply = assistantNode("reply")
        val branchNode = MessageNode(messages = listOf(reply.currentMessage), selectIndex = 0)
        val conversation = conversationOf(a, branchNode)
        val snapshot = requireNotNull(conversation.compressionSnapshot(endIndex = 2, incarnation = 0))

        // 只是多了一条没选中的分支，当前分支仍然是压缩时那条
        val extra = conversation.copy(
            messageNodes = listOf(a, branchNode.copy(messages = branchNode.messages + UIMessage.assistant("alt"))),
        )
        assertTrue(extra.compressionCommit(snapshot, 0) is CompressionCommit.Ready)
    }

    @Test
    fun `deleting a message inside the range drops the stale summary`() {
        val nodes = List(4) { index -> if (index % 2 == 0) userNode("u$index") else assistantNode("a$index") }
        val conversation = conversationOf(*nodes.toTypedArray())
        val snapshot = requireNotNull(conversation.compressionSnapshot(endIndex = 4, incarnation = 0))

        val shorter = conversation.copy(messageNodes = conversation.messageNodes.drop(1))
        assertEquals(CompressionCommit.Changed, shorter.compressionCommit(snapshot, 0))
    }

    @Test
    fun `replaced conversation incarnation drops the stale summary`() {
        val conversation = conversationOf(userNode("a"), assistantNode("b"))
        val snapshot = requireNotNull(conversation.compressionSnapshot(endIndex = 2, incarnation = 3))
        assertEquals(
            CompressionCommit.Changed,
            conversation.compressionCommit(snapshot, currentIncarnation = 4),
        )
    }

    @Test
    fun `a checkpoint already at the boundary is not compressed twice`() {
        val a = userNode("a")
        val b = assistantNode("b")
        val conversation = conversationOf(a, b)
        val snapshot = requireNotNull(conversation.compressionSnapshot(endIndex = 2, incarnation = 0))

        val once = requireNotNull(conversation.insertContextCheckpoint(b.id, "summary"))
        assertEquals(CompressionCommit.AlreadyCompressed, once.compressionCommit(snapshot, 0))
    }

    @Test
    fun `range ending on a checkpoint is not compressed again`() {
        val a = userNode("a")
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true).toMessageNode()
        val conversation = conversationOf(a, checkpoint)
        val snapshot = requireNotNull(conversation.compressionSnapshot(endIndex = 2, incarnation = 0))
        assertEquals(CompressionCommit.AlreadyCompressed, conversation.compressionCommit(snapshot, 0))
    }

    @Test
    fun `streaming message inside the range blocks the insert but a new tail turn does not`() {
        val a = userNode("a")
        val b = assistantNode("b")
        val conversation = conversationOf(a, b)
        val snapshot = requireNotNull(conversation.compressionSnapshot(endIndex = 2, incarnation = 0))

        // 没有生成在跑时 replyMessageId 可能还留着上一轮，调用方传 null，不算冲突
        assertTrue(!snapshot.coversStreamingMessage(null))
        // 正在流式写入的那条回复就在压缩范围里
        assertTrue(snapshot.coversStreamingMessage(b.currentMessage.id))
        // 压缩期间新加的一轮（范围之外）不受影响
        assertTrue(!snapshot.coversStreamingMessage(Uuid.random()))
    }

    @Test
    fun `snapshot rejects an out of range end index`() {
        val conversation = conversationOf(userNode("a"))
        assertNull(conversation.compressionSnapshot(endIndex = 0, incarnation = 0))
        assertNull(conversation.compressionSnapshot(endIndex = 2, incarnation = 0))
        assertTrue(conversation.compressionSnapshot(endIndex = 1, incarnation = 0) != null)
    }
}
