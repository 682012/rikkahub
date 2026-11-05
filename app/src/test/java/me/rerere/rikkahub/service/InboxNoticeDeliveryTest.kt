package me.rerere.rikkahub.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxNoticeDeliveryTest {
    private fun queued(text: String = "done") = QueuedNotice(
        key = "background:one",
        notice = UIMessagePart.Notice("task", text),
        wakeOnArrival = false,
    )

    private fun history(part: UIMessagePart) = listOf(
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
    )

    @Test fun `delivery marker survives history commit and prevents crash duplicate`() {
        val item = queued()
        val persisted = item.toDeliveryPart()
        assertTrue(item.isDeliveredIn(history(persisted)))
        assertFalse(item.isDeliveredIn(emptyList()))
        assertEquals(null, item.notice.metadata)
    }

    @Test fun `identical text from a different task is still delivered`() {
        val item = queued()
        assertFalse(queued().isDeliveredIn(history(item.toDeliveryPart())))
    }

    @Test fun `updated content with the same item identity is still delivered`() {
        val item = queued()
        val updated = item.copy(notice = item.notice.copy(text = "new result"))
        assertFalse(updated.isDeliveredIn(history(item.toDeliveryPart())))
        assertTrue(updated.isDeliveredIn(history(updated.toDeliveryPart())))
    }

    @Test fun `delivery metadata preserves original fields without mutating them`() {
        val metadata = buildJsonObject { put("source", "tool") }
        val item = queued().let { it.copy(notice = it.notice.copy(metadata = metadata)) }
        val delivered = item.toDeliveryPart()
        assertEquals(metadata["source"], delivered.metadata?.get("source"))
        assertEquals(metadata, item.notice.metadata)
        assertEquals(1, metadata.size)
        assertTrue(item.isDeliveredIn(history(delivered)))
    }
}
