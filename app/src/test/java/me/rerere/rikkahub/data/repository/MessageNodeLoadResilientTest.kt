package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MessageNodeMeta
import me.rerere.rikkahub.data.db.dao.MessageNodeSnapshot
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CONV = "conv-1"

/**
 * 假的行存储：模拟 SQLite 的真实行为 —— 一页里只要有一行超过 CursorWindow，整页查询就失败。
 * 分片读取走 `length()`（code point）与 `substr()` 语义，和 DAO 里的 SQL 一致。
 */
private class FakeNodeStore(
    private val rows: LinkedHashMap<String, String>,
    private val windowChars: Int = 4096,
) : NodePageSource {
    var metaReads = 0
    val sliceReads = mutableListOf<String>()
    var failSliceFor: String? = null

    override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> {
        val page = rows.entries.drop(offset).take(limit)
        if (page.any { it.value.length > windowChars }) {
            throw IllegalStateException("Row too big to fit into CursorWindow")
        }
        return page.mapIndexed { i, entry ->
            MessageNodeEntity(entry.key, CONV, offset + i, entry.value, 0)
        }
    }

    override suspend fun readMeta(): List<MessageNodeMeta> {
        metaReads++
        return rows.entries.mapIndexed { i, entry ->
            MessageNodeMeta(entry.key, CONV, i, 0, codepointLength(entry.value))
        }
    }

    override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int): String? {
        sliceReads += nodeId
        if (nodeId == failSliceFor) return null
        val text = rows[nodeId] ?: return null
        return sliceCodepoints(text, start, length)
    }
}

/** 与 SQLite `substr(text, start, length)` 相同：start 为 1 基，按 code point 计数。 */
internal fun sliceCodepoints(text: String, start: Int, length: Int): String {
    val begin = text.offsetByCodePoints(0, start - 1)
    val available = codepointLength(text) - (start - 1)
    val end = text.offsetByCodePoints(begin, minOf(length, available))
    return text.substring(begin, end)
}

private fun jsonOf(index: Int): String = "[\"node-$index-" + "x".repeat(8) + "\"]"

class MessageNodeLoadResilientTest {
    private fun bigRows(): LinkedHashMap<String, String> {
        val rows = LinkedHashMap<String, String>()
        for (i in 0 until 200) rows["n$i"] = jsonOf(i)
        rows["n130"] = "[" + "y".repeat(9000) + "]"
        return rows
    }

    @Test
    fun `big row in a page falls back to chunked read without losing any node`() = runBlocking {
        val rows = bigRows()
        val store = FakeNodeStore(rows)

        val entities = loadAllNodesResilient(pageSize = 64, chunkCodepoints = 1024, source = store)

        assertEquals(200, entities.size)
        assertEquals((0 until 200).map { "n$it" }, entities.map { it.id })
        assertEquals(rows["n130"], entities[130].messages)
        assertEquals(listOf(0, 1, 198, 199), entities.map { it.nodeIndex }.let {
            listOf(it.first(), it[1], it[198], it[199])
        })
        assertEquals(1, store.metaReads)
    }

    @Test
    fun `small conversation still uses the paging fast path`() = runBlocking {
        val rows = LinkedHashMap<String, String>()
        for (i in 0 until 10) rows["n$i"] = jsonOf(i)
        val store = FakeNodeStore(rows)

        val entities = loadAllNodesResilient(pageSize = 64, chunkCodepoints = 1024, source = store)

        assertEquals(10, entities.size)
        assertEquals(0, store.metaReads)
        assertTrue(store.sliceReads.isEmpty())
    }

    @Test
    fun `generic illegal state is not swallowed`() = runBlocking {
        val store = object : NodePageSource {
            override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> =
                throw IllegalStateException("database is locked")

            override suspend fun readMeta(): List<MessageNodeMeta> =
                throw AssertionError("must not fall back for a non-window error")

            override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int) = null
        }
        val error = runCatching {
            loadAllNodesResilient(pageSize = 64, chunkCodepoints = 1024, source = store)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("database is locked", error?.message)
    }

    @Test
    fun `cancellation passes through instead of returning a partial page`() = runBlocking {
        val store = object : NodePageSource {
            override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> =
                throw CancellationException("cancelled by user")

            override suspend fun readMeta(): List<MessageNodeMeta> = emptyList()

            override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int) = null
        }
        val error = runCatching {
            loadAllNodesResilient(pageSize = 64, chunkCodepoints = 1024, source = store)
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }

    @Test
    fun `a failed slice fails the whole load`() = runBlocking {
        val store = FakeNodeStore(bigRows())
        store.failSliceFor = "n130"

        val error = runCatching {
            loadAllNodesResilient(pageSize = 64, chunkCodepoints = 1024, source = store)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("n130"))
    }

    @Test
    fun `snapshot fallback keeps original messages so diffs stay truthful`() = runBlocking {
        val rows = LinkedHashMap<String, String>()
        rows["n0"] = jsonOf(0)
        rows["n1"] = "[" + "z".repeat(9000) + "]"
        val store = FakeNodeStore(rows)

        val snapshots = loadSnapshotsResilient(
            readSnapshots = { throw IllegalStateException("CursorWindow full") },
            fallback = {
                loadNodesChunked(store, chunkCodepoints = 1024).map { node ->
                    MessageNodeSnapshot(node.id, node.nodeIndex, node.messages, node.selectIndex)
                }
            },
        )

        assertEquals(listOf("n0", "n1"), snapshots.map { it.id })
        assertEquals(rows["n1"], snapshots[1].messages)
        assertEquals(listOf(0, 1), snapshots.map { it.nodeIndex })
    }

    @Test
    fun `snapshot read failure without window signal is rethrown`() = runBlocking {
        val error = runCatching {
            loadSnapshotsResilient(
                readSnapshots = { throw CancellationException("cancelled") },
                fallback = { throw AssertionError("must not fall back") },
            )
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }
}
