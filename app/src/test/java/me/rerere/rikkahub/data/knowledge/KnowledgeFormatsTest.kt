package me.rerere.rikkahub.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeFormatsTest {
    @Test
    fun `implemented text and document formats are supported`() {
        assertEquals(KnowledgeFormat.TEXT, KnowledgeFormats.formatOf("notes.txt", null))
        assertEquals(KnowledgeFormat.MARKDOWN, KnowledgeFormats.formatOf("readme.md", null))
        assertEquals(KnowledgeFormat.HTML, KnowledgeFormats.formatOf("page.html", null))
        assertEquals(KnowledgeFormat.PDF, KnowledgeFormats.formatOf("manual.pdf", null))
        assertEquals(KnowledgeFormat.DOCX, KnowledgeFormats.formatOf("spec.docx", null))
        assertEquals(KnowledgeFormat.PPTX, KnowledgeFormats.formatOf("deck.pptx", null))
        assertEquals(KnowledgeFormat.EPUB, KnowledgeFormats.formatOf("book.epub", null))
    }

    @Test
    fun `formats without a real reader are not advertised`() {
        assertNull(KnowledgeFormats.formatOf("sheet.xlsx", null))
        assertNull(KnowledgeFormats.formatOf("legacy.doc", null))
        assertNull(KnowledgeFormats.formatOf("photo.png", null))
        assertNull(KnowledgeFormats.formatOf("archive.zip", null))
        assertFalse(KnowledgeFormats.supportedExtensions.contains("xlsx"))
        assertFalse(KnowledgeFormats.supportedExtensions.contains("doc"))
        assertFalse(KnowledgeFormats.supportedExtensions.contains("png"))
    }

    @Test
    fun `extension matching is case insensitive and keeps only the last suffix`() {
        assertEquals(KnowledgeFormat.PDF, KnowledgeFormats.formatOf("Report.FINAL.PDF", null))
    }

    @Test
    fun `mime type is used when the file has no extension`() {
        assertEquals(KnowledgeFormat.PDF, KnowledgeFormats.formatOf("download", "application/pdf"))
        assertEquals(
            KnowledgeFormat.MARKDOWN,
            KnowledgeFormats.formatOf("download", "text/markdown"),
        )
        assertEquals(
            KnowledgeFormat.DOCX,
            KnowledgeFormats.formatOf(
                "download",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        )
        assertEquals(KnowledgeFormat.TEXT, KnowledgeFormats.formatOf("download", "text/plain; charset=utf-8"))
    }

    @Test
    fun `unsupported reason lists the real supported extensions`() {
        val reason = KnowledgeFormats.unsupportedReason() as KnowledgeRejectReason.UnsupportedFormat
        assertTrue(reason.supportedExtensions.contains("txt"))
        assertTrue(reason.supportedExtensions.contains("pdf"))
        assertTrue(reason.supportedExtensions.contains("docx"))
        assertFalse(reason.supportedExtensions.contains("xlsx"))
    }

    @Test
    fun `picker only offers readable mime types`() {
        val mimes = KnowledgeFormats.pickerMimeTypes.toList()
        assertTrue(mimes.contains("text/*"))
        assertTrue(mimes.contains("application/pdf"))
        assertFalse(mimes.any { it.contains("spreadsheet") })
    }

    @Test
    fun `limits document the caps that the repository enforces`() {
        assertEquals(10L * 1024 * 1024, KnowledgeLimits.MAX_SOURCE_FILE_BYTES)
        assertEquals(1_048_576, KnowledgeLimits.MAX_TEXT_CHARS)
        assertTrue(KnowledgeLimits.CHUNK_TARGET_CHARS < KnowledgeLimits.CHUNK_HARD_MAX_CHARS)
        assertTrue(KnowledgeLimits.CHUNK_OVERLAP_CHARS < KnowledgeLimits.CHUNK_TARGET_CHARS)
    }
}
