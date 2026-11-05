package me.rerere.rikkahub.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val INBOX_NOTICE_ID = "r21.inboxNoticeId"

/** A durable delivery marker; the queue's original value is never mutated. */
internal fun QueuedNotice.toDeliveryPart(): UIMessagePart.Notice = notice.copy(
    metadata = buildJsonObject {
        notice.metadata?.forEach { (key, value) -> put(key, value) }
        put(INBOX_NOTICE_ID, id.toString())
    },
)

/**
 * A crash after the history commit but before queue acknowledgement must not append it twice.
 * A later update to the same queue item still gets delivered because its content differs.
 */
internal fun QueuedNotice.isDeliveredIn(messages: List<UIMessage>): Boolean = messages.any { message ->
    message.parts.any { part ->
        part is UIMessagePart.Notice && part.title == notice.title && part.text == notice.text &&
            (part.metadata?.get(INBOX_NOTICE_ID) as? JsonPrimitive)?.contentOrNull == id.toString()
    }
}
