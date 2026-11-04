package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** Small input snapshot. Attachments remain references to the existing local files. */
@Serializable
data class ChatDraft(
    val text: String = "",
    val parts: List<UIMessagePart> = emptyList(),
    val editingMessage: Uuid? = null,
    val originalParts: List<UIMessagePart>? = null,
    val reasoning: String? = null,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
) {
    val isEmpty: Boolean get() = text.isEmpty() && parts.isEmpty() && editingMessage == null

    /**
     * 内容是否相同（不看光标位置）。
     * 光标每次移动都会产生新快照，判断「输入还是不是发出去的那份」「要不要重写文件」只能看内容。
     */
    fun sameContent(other: ChatDraft): Boolean =
        text == other.text &&
            parts == other.parts &&
            editingMessage == other.editingMessage &&
            originalParts == other.originalParts &&
            reasoning == other.reasoning
}

/** New-chat input belongs to its assistant, existing-chat input to the conversation. */
fun chatDraftKey(conversationId: Uuid, assistantId: Uuid, hasMessages: Boolean): String =
    if (hasMessages) "conversation:$conversationId" else "assistant:$assistantId"
