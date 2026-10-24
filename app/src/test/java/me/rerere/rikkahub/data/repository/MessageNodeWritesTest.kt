package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.dao.MessageNodeSnapshot
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 流式生成期间每 1.5 秒落库一次（fork.26），updateConversation 不能每次都重写整段会话的节点。
 * 这里固定 messageNodeWrites 的差分语义：只有内容或顺序真的变了才写。
 */
class MessageNodeWritesTest {
    private fun node(text: String, selectIndex: Int = 0, branches: Int = 1): MessageNode = MessageNode(
        id = Uuid.random(),
        messages = (0 until branches).map {
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("$text-$it")))
        },
        selectIndex = selectIndex,
    )

    private fun snapshotOf(node: MessageNode, index: Int) = MessageNodeSnapshot(
        id = node.id.toString(),
        nodeIndex = index,
        messages = JsonInstant.encodeToString(node.messages),
        selectIndex = node.selectIndex,
    )

    @Test
    fun `unchanged conversation writes nothing`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val existing = nodes.mapIndexed { index, node -> snapshotOf(node, index) }

        val writes = messageNodeWrites(conversationId = "c1", nodes = nodes, existing = existing)

        assertTrue(writes.removedIds.isEmpty())
        assertTrue(writes.upserts.isEmpty())
    }

    @Test
    fun `only the streaming node is written`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val existing = nodes.mapIndexed { index, node -> snapshotOf(node, index) }
        val grownMessage = nodes.last().messages.single().copy(
            parts = listOf(UIMessagePart.Text("c-grown")),
        )
        val streaming = nodes.dropLast(1) + nodes.last().copy(messages = listOf(grownMessage))

        val writes = messageNodeWrites(conversationId = "c1", nodes = streaming, existing = existing)

        assertTrue(writes.removedIds.isEmpty())
        assertEquals(1, writes.upserts.size)
        assertEquals(streaming.last().id.toString(), writes.upserts.single().id)
        assertEquals(2, writes.upserts.single().nodeIndex)
    }

    @Test
    fun `removed nodes are deleted and reordered nodes rewritten`() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        val existing = listOf(snapshotOf(a, 0), snapshotOf(b, 1), snapshotOf(c, 2))

        val writes = messageNodeWrites(conversationId = "c1", nodes = listOf(b, a), existing = existing)

        assertEquals(listOf(c.id.toString()), writes.removedIds)
        assertEquals(listOf(b.id.toString(), a.id.toString()), writes.upserts.map { it.id })
        assertEquals(listOf(0, 1), writes.upserts.map { it.nodeIndex })
    }

    @Test
    fun `select index change rewrites the node`() {
        val branch = node("x", selectIndex = 0, branches = 2)
        val existing = listOf(snapshotOf(branch, 0))

        val writes = messageNodeWrites("c1", listOf(branch.copy(selectIndex = 1)), existing)

        assertEquals(1, writes.upserts.single().selectIndex)
        assertTrue(writes.removedIds.isEmpty())
    }

    @Test
    fun `new node is appended`() {
        val a = node("a")
        val existing = listOf(snapshotOf(a, 0))

        val writes = messageNodeWrites("c1", listOf(a, node("b")), existing)

        assertEquals(1, writes.upserts.size)
        assertEquals(1, writes.upserts.single().nodeIndex)
        assertTrue(writes.removedIds.isEmpty())
    }
}
