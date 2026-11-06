package me.rerere.rikkahub.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeChunkerTest {
    private fun longChineseText(sentences: Int): String {
        val sb = StringBuilder()
        repeat(sentences) { i ->
            sb.append("这是第").append(i).append("句用于测试分段的内容，包含足够的长度。")
        }
        return sb.toString()
    }

    @Test
    fun `short text becomes one chunk with exact offsets`() {
        val text = "知识库很短。"
        val chunks = KnowledgeChunker.split(text)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].start)
        assertEquals(text.length, chunks[0].end)
        assertEquals(text, chunks[0].text)
    }

    @Test
    fun `every character is covered and no chunk exceeds the hard cap`() {
        val text = longChineseText(300)
        val chunks = KnowledgeChunker.split(text)
        assertTrue(chunks.size > 1)
        val covered = BooleanArray(text.length)
        for (chunk in chunks) {
            assertTrue(chunk.text.length <= KnowledgeLimits.CHUNK_HARD_MAX_CHARS)
            assertEquals(text.substring(chunk.start, chunk.end), chunk.text)
            for (i in chunk.start until chunk.end) covered[i] = true
        }
        assertTrue("all characters must belong to a chunk", covered.all { it })
    }

    @Test
    fun `neighbouring chunks overlap and always advance`() {
        val text = longChineseText(200)
        val chunks = KnowledgeChunker.split(text)
        for (i in 1 until chunks.size) {
            assertTrue(chunks[i].start > chunks[i - 1].start)
            assertTrue(chunks[i].start < chunks[i - 1].end)
            assertTrue(chunks[i].end > chunks[i].start)
        }
    }

    @Test
    fun `chunking is deterministic`() {
        val text = longChineseText(120)
        assertEquals(KnowledgeChunker.split(text), KnowledgeChunker.split(text))
    }

    @Test
    fun `a single huge paragraph without newlines still splits`() {
        val text = "没有标点的超长正文".repeat(2_000)
        val chunks = KnowledgeChunker.split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= KnowledgeLimits.CHUNK_HARD_MAX_CHARS })
    }

    @Test
    fun `emoji is never cut in half`() {
        val text = "啊".repeat(1_190) + "🙂" + "啊".repeat(1_200)
        val chunks = KnowledgeChunker.split(text)
        for (chunk in chunks) {
            if (chunk.text.isEmpty()) continue
            assertTrue(!chunk.text.first().isLowSurrogate())
            assertTrue(!chunk.text.last().isHighSurrogate())
        }
    }

    @Test
    fun `zero overlap is allowed`() {
        val text = longChineseText(80)
        val chunks = KnowledgeChunker.split(text, overlapChars = 0)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= KnowledgeLimits.CHUNK_TARGET_CHARS })
    }

    @Test
    fun `offsets stay valid after normalization`() {
        val normalized = KnowledgeTextNormalizer.normalize("第一段。\r\n\r\n第二段，内容更长一些。")
        val chunks = KnowledgeChunker.split(normalized)
        assertTrue(chunks.all { normalized.substring(it.start, it.end) == it.text })
    }
}
