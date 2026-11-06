package me.rerere.rikkahub.data.knowledge

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeScopeTest {
    @Test
    fun `blank assistant id is rejected before any query runs`() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeScope.requireAssistant("   ")
        }
    }

    @Test
    fun `shared scope placeholder cannot be used as a real scope yet`() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeScope.requireAssistant(KnowledgeScope.SHARED_SCOPE_ID)
        }
    }

    @Test
    fun `cross-assistant access throws instead of returning data`() {
        assertThrows(KnowledgeScopeViolationException::class.java) {
            KnowledgeScope.requireOwned(assistantId = "assistant-a", ownerAssistantId = "assistant-b")
        }
        // 同一 scope 正常通过
        assertEquals("assistant-a", KnowledgeScope.requireAssistant("assistant-a"))
    }

    @Test
    fun `import result exposes the document only when it was imported or deduped`() {
        val doc = KnowledgeDocSummary(
            id = "doc-1",
            assistantId = "assistant-a",
            title = "手册",
            sourceType = KnowledgeSourceType.PASTED,
            sourceName = null,
            sourceUri = null,
            format = KnowledgeFormat.TEXT,
            contentHash = "hash",
            sizeBytes = 12,
            charCount = 4,
            chunkCount = 1,
            revision = 1,
            createdAt = Instant.parse("2025-11-06T00:00:00Z"),
            updatedAt = Instant.parse("2025-11-06T00:00:00Z"),
            truncated = false,
        )
        assertEquals(doc, KnowledgeImportResult.Imported(doc).docOrNull)
        assertEquals(doc, KnowledgeImportResult.Duplicate(doc).docOrNull)
        assertNull(
            KnowledgeImportResult.Rejected(KnowledgeRejectReason.EmptyText).docOrNull
        )
        assertTrue(doc.assistantId == "assistant-a")
    }
}
