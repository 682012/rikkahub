package me.rerere.rikkahub.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeScoringTest {
    private val tokens = KnowledgeTokenizer.queryTokens("知识库导入")

    @Test
    fun `title hit outranks an identical body without the title hit`() {
        val text = "本文介绍知识库导入的完整步骤与注意事项。"
        val withTitle = KnowledgeScoring.score("知识库导入指南", text, tokens, "知识库导入")
        val withoutTitle = KnowledgeScoring.score("随手记", text, tokens, "知识库导入")
        assertTrue(withTitle > withoutTitle)
        // 标题权重独立项：差值应接近 0.30，不会被其它项吃掉
        assertEquals(0.30, withTitle - withoutTitle, 0.0001)
    }

    @Test
    fun `higher token coverage scores higher`() {
        val both = KnowledgeScoring.score("资料", "知识库导入方法", tokens)
        val one = KnowledgeScoring.score("资料", "只有知识库三个字", tokens)
        assertTrue(both > one)
    }

    @Test
    fun `contiguous phrase adds a bounded bonus`() {
        val contiguous = KnowledgeScoring.score("资料", "如何做知识库导入", tokens, "知识库导入")
        val separated = KnowledgeScoring.score("资料", "知识 库 导入", tokens, "知识库导入")
        assertTrue(contiguous > separated)
        assertTrue(contiguous <= 1.30 + 0.0001)
    }

    @Test
    fun `unrelated text scores zero`() {
        assertEquals(0.0, KnowledgeScoring.score("菜谱", "红烧肉的做法", tokens), 0.0)
    }

    @Test
    fun `length normalization is bounded`() {
        assertTrue(KnowledgeScoring.lengthNormalization(0) == 0.0)
        assertTrue(KnowledgeScoring.lengthNormalization(10) >= 0.35)
        assertEquals(1.0, KnowledgeScoring.lengthNormalization(900), 0.0001)
        assertTrue(KnowledgeScoring.lengthNormalization(100_000) >= 0.35)
    }

    @Test
    fun `snippet is centered on the hit and bounded`() {
        val text = "前".repeat(400) + "知识库导入的步骤" + "后".repeat(400)
        val snippet = KnowledgeScoring.snippet(text, tokens, maxChars = 200)
        assertTrue(snippet.contains("知识"))
        assertTrue(snippet.length <= 202)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `snippet without a hit returns the head instead of crashing`() {
        val snippet = KnowledgeScoring.snippet("完全无关的一段文字", tokens, maxChars = 100)
        assertTrue(snippet.isNotEmpty())
        assertTrue(snippet.length <= 102)
    }

    @Test
    fun `snippet collapses newlines`() {
        val snippet = KnowledgeScoring.snippet("第一行\n\n第二行 知识库", tokens, maxChars = 100)
        assertTrue(!snippet.contains("\n"))
    }

    @Test
    fun `occurrence counting does not overlap`() {
        assertEquals(2, KnowledgeScoring.countOccurrences("知识知识库", "知识"))
        assertEquals(0, KnowledgeScoring.countOccurrences("知识库", "数据库"))
    }
}
