package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MessageNodeMeta
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片回退的字符边界：SQLite `length()` / `substr()` 按 Unicode code point 计数，
 * Kotlin 的 `String.length` 是 UTF-16 单元数。含 emoji（代理对）时两者不同 ——
 * 用 UTF-16 下标算偏移会切在代理对中间，拼回来的 JSON 直接乱码或丢字。
 */
class MessageNodeChunkCodepointsTest {
    private val emoji = "\uD83D\uDE00" // 😀 单个 code point、两个 UTF-16 单元

    @Test
    fun `codepoint length differs from utf16 length`() {
        val text = "a${emoji}b"
        assertEquals(3, codepointLength(text))
        assertEquals(4, text.length)
    }

    @Test
    fun `split by codepoints never breaks a surrogate pair`() {
        val text = emoji.repeat(5) + "尾"
        val chunks = splitByCodepoints(text, 2)

        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { codepointLength(it) <= 2 })
        assertTrue(chunks.all { it.isNotEmpty() })
        // 每片都是 2 个 code point 的 emoji，UTF-16 长度会是 4 —— 用 UTF-16 算长度就会错位
        assertEquals(4, chunks.first().length)
        assertEquals(2, codepointLength(chunks.first()))
    }

    @Test
    fun `chunk offsets follow sqlite codepoint indexing`() {
        val text = "[\"${emoji.repeat(3)}hello\"]"
        val total = codepointLength(text)
        val collected = StringBuilder()
        var start = 1
        while (start <= total) {
            val slice = sliceCodepoints(text, start, 3)
            collected.append(slice)
            start += codepointLength(slice)
        }

        assertEquals(text, collected.toString())
        assertEquals(total, codepointLength(collected.toString()))
    }

    @Test
    fun `chunked load reassembles emoji heavy json exactly`() = runBlocking {
        val messages = "[\"${emoji.repeat(40)}混合文本😀emoji\"]"
        val store = object : NodePageSource {
            override suspend fun readPage(limit: Int, offset: Int): List<MessageNodeEntity> =
                throw IllegalStateException("Row too big to fit into CursorWindow")

            override suspend fun readMeta(): List<MessageNodeMeta> = listOf(
                MessageNodeMeta("n0", "c1", 0, 0, codepointLength(messages)),
            )

            override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int): String? =
                sliceCodepoints(messages, start, length)
        }

        val entities = loadNodesChunked(store, chunkCodepoints = 7)

        assertEquals(messages, entities.single().messages)
        assertEquals(codepointLength(messages), codepointLength(entities.single().messages))
    }

    @Test
    fun `chunked load keeps node order and select index`() = runBlocking {
        val rows = linkedMapOf("b" to "[\"2\"]", "a" to "[\"1\"]")
        val store = object : NodePageSource {
            override suspend fun readPage(limit: Int, offset: Int) = emptyList<MessageNodeEntity>()

            override suspend fun readMeta(): List<MessageNodeMeta> = listOf(
                MessageNodeMeta("b", "c1", 1, 3, 5),
                MessageNodeMeta("a", "c1", 0, 2, 5),
            )

            override suspend fun readMessagesSlice(nodeId: String, start: Int, length: Int) =
                sliceCodepoints(rows.getValue(nodeId), start, length)
        }

        val entities = loadNodesChunked(store, chunkCodepoints = 64)

        assertEquals(listOf("a", "b"), entities.map { it.id })
        assertEquals(listOf(0, 1), entities.map { it.nodeIndex })
        assertEquals(listOf(2, 3), entities.map { it.selectIndex })
    }
}
