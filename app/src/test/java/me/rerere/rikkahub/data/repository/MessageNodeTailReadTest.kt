package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MessageNodeMeta
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNodeTailReadTest {
    private fun row(index: Int) = MessageNodeEntity(
        id = "n$index", conversationId = "c", nodeIndex = index,
        messages = "[\"tail-$index😀中文\"]", selectIndex = index % 2,
    )

    @Test
    fun `normal tail is chronological without metadata reads`() = runBlocking {
        val rows = listOf(row(199), row(198))
        val loaded = loadTailNodesResilient(
            limit = 2, chunkCodepoints = 3,
            readTail = { assertEquals(2, it); rows },
            readTailMeta = { error("normal tail must use the fast path") },
            readSlice = { _, _, _ -> error("normal tail must not read slices") },
        )
        assertEquals(rows.reversed(), loaded)
    }

    @Test
    fun `cancelled tail query never returns partial history`() = runBlocking {
        val error = runCatching {
            loadTailNodesResilient(
                limit = 2,
                chunkCodepoints = 3,
                readTail = { throw CancellationException("user cancelled") },
                readTailMeta = { throw AssertionError("Cancellation must not trigger fallback") },
                readSlice = { _, _, _ -> null },
            )
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    @Test
    fun `tail limits retain the established one to one hundred boundary`() = runBlocking {
        for ((requested, expected) in listOf(0 to 1, 1 to 1, 100 to 100, 999 to 100)) {
            loadTailNodesResilient(
                limit = requested,
                chunkCodepoints = 3,
                readTail = { limit -> assertEquals(expected, limit); emptyList() },
                readTailMeta = { throw AssertionError("Empty tail needs no fallback") },
                readSlice = { _, _, _ -> null },
            )
        }
    }

    @Test
    fun `oversized tail reads only requested recent nodes and preserves emoji`() = runBlocking {
        val rows = listOf(row(199), row(198))
        val loaded = loadTailNodesResilient(
            limit = 2, chunkCodepoints = 3,
            readTail = { throw IllegalStateException("Row too big to fit into CursorWindow") },
            readTailMeta = { limit ->
                assertEquals(2, limit)
                rows.map { MessageNodeMeta(it.id, it.conversationId, it.nodeIndex, it.selectIndex, codepointLength(it.messages)) }
            },
            readSlice = { id, start, size ->
                val value = rows.single { it.id == id }.messages
                sliceCodepoints(value, start, size)
            },
        )
        assertEquals(rows.reversed(), loaded)
        assertEquals(listOf(198, 199), loaded.map { it.nodeIndex })
    }
}
