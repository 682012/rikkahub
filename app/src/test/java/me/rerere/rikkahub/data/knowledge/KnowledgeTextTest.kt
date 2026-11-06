package me.rerere.rikkahub.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeTextTest {
    @Test
    fun `chinese runs are indexed as deterministic bigrams`() {
        assertEquals(listOf("知识", "识库"), KnowledgeTokenizer.indexTokens("知识库"))
    }

    @Test
    fun `single chinese character still produces a token`() {
        assertEquals(listOf("库"), KnowledgeTokenizer.indexTokens("库"))
    }

    @Test
    fun `mixed latin and chinese split on punctuation and case`() {
        val tokens = KnowledgeTokenizer.indexTokens("GPT-4 支持 知识库")
        assertTrue(tokens.contains("gpt"))
        assertTrue(tokens.contains("4"))
        assertTrue(tokens.contains("知识"))
        assertTrue(tokens.contains("识库"))
        assertFalse(tokens.any { it.contains("-") })
    }

    @Test
    fun `punctuation only query has no tokens and no match expression`() {
        assertTrue(KnowledgeTokenizer.queryTokens("，。！？ ; …").isEmpty())
        assertNull(KnowledgeTokenizer.ftsMatchQuery("，。！"))
    }

    @Test
    fun `query tokens are deduped and fts expression is quoted OR`() {
        val tokens = KnowledgeTokenizer.queryTokens("知识库知识")
        assertEquals(tokens.distinct(), tokens)
        val match = KnowledgeTokenizer.ftsMatchQuery("知识库")
        assertNotNull(match)
        assertTrue(match!!.contains(" OR "))
        assertTrue(match.contains("\"知识\""))
        assertEquals(4, match.count { it == '"' })
    }

    @Test
    fun `query token count is bounded`() {
        val query = "知识库".repeat(100)
        assertTrue(KnowledgeTokenizer.queryTokens(query).size <= KnowledgeLimits.MAX_QUERY_TOKENS)
    }

    @Test
    fun `normalization is stable and idempotent`() {
        val once = KnowledgeTextNormalizer.normalize("\uFEFFa\r\nb\t\t c   d\r\n\r\n\r\ne\u0000f")
        assertEquals("a\nb c d\n\nef", once)
        assertEquals(once, KnowledgeTextNormalizer.normalize(once))
    }

    @Test
    fun `sha256 matches the known digest of abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            KnowledgeHash.sha256Hex("abc"),
        )
    }

    @Test
    fun `same content from different names hashes the same`() {
        val a = KnowledgeTextNormalizer.normalize("知识库内容\r\n第二行")
        val b = KnowledgeTextNormalizer.normalize("知识库内容\n第二行")
        assertEquals(KnowledgeHash.sha256Hex(a), KnowledgeHash.sha256Hex(b))
        assertFalse(a.isEmpty())
    }
}
