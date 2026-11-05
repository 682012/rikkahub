package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.*
import org.junit.Test

class StreamEmptyImageTest {
    private fun empty() = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
    @Test fun finishRemovesStartWithoutPayload() {
        val h = StreamChunkHandler()
        val started = h.handle(empty(), StreamChunk.ImageStart("a"))
        assertEquals(1, started.single().parts.size)
        val finished = h.handle(started, StreamChunk.Finish("error"))
        assertTrue(finished.single().parts.isEmpty())
        assertNotNull(finished.single().finishedAt)
    }
    @Test fun imageEndRemovesEmptyPartImmediately() {
        val h = StreamChunkHandler()
        val started = h.handle(empty(), StreamChunk.ImageStart("a"))
        assertTrue(h.handle(started, StreamChunk.ImageEnd("a")).single().parts.isEmpty())
    }
    @Test fun removalReindexesInterleavedParts() {
        val h = StreamChunkHandler()
        var m = h.handle(empty(), StreamChunk.ImageStart("empty"))
        m = h.handle(m, StreamChunk.TextStart("t"))
        m = h.handle(m, StreamChunk.ImageStart("success"))
        m = h.handle(m, StreamChunk.ReasoningStart("r"))
        m = h.handle(m, StreamChunk.ImageEnd("empty"))
        m = h.handle(m, StreamChunk.TextDelta("t", "retained text"))
        m = h.handle(m, StreamChunk.ImageDelta("success", "bytes"))
        m = h.handle(m, StreamChunk.ReasoningDelta("r", "retained reasoning"))
        val p = h.handle(m, StreamChunk.Finish()).single().parts
        assertEquals(3, p.size)
        assertEquals("retained text", (p[0] as UIMessagePart.Text).text)
        assertEquals("data:image/png;base64,bytes", (p[1] as UIMessagePart.Image).url)
        assertEquals("retained reasoning", (p[2] as UIMessagePart.Reasoning).reasoning)
    }
    @Test fun successfulImageAndTextSurviveCleanup() {
        val h = StreamChunkHandler()
        var m = h.handle(empty(), StreamChunk.ImageStart("ok"))
        m = h.handle(m, StreamChunk.ImageSnapshot("ok", "payload"))
        m = h.handle(m, StreamChunk.ImageStart("empty"))
        m = h.handle(m, StreamChunk.TextDelta("text", "generation failed for second image"))
        val p = h.handle(m, StreamChunk.Finish()).single().parts
        assertEquals(2, p.size)
        assertEquals("data:image/png;base64,payload", (p[0] as UIMessagePart.Image).url)
        assertTrue(p[1] is UIMessagePart.Text)
    }
    @Test fun terminalCleanupHandlesEofErrorAndCancellationWithoutFinishEvent() {
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("keep"), UIMessagePart.Image("data:image/png;base64, \n"),
            UIMessagePart.Image("https://example.com/image.png"), UIMessagePart.Image("data:image/png;base64,partial")))
        val clean = assistant.withoutEmptyGeneratedImages()
        assertEquals(3, clean.parts.size)
        assertEquals(assistant.id, clean.id)
        assertEquals(clean, clean.withoutEmptyGeneratedImages())
    }
    @Test fun userAttachmentsAreNeverDeleted() {
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("data:image/png;base64,")))
        assertSame(user, user.withoutEmptyGeneratedImages())
    }
}
