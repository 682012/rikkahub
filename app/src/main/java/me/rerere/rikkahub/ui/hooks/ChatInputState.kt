package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ChatDraft
import kotlin.uuid.Uuid

class ChatInputState {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)

    /** 编辑中的消息允许改思考内容时不为 null，见 [setContents] */
    var editingReasoning by mutableStateOf<TextFieldState?>(null)
        private set
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        editingReasoning = null
        editingParts = null
        editingAttachmentUrls = emptySet()
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        val lastTextIndex = contents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (contents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        val reasoning = contents.editableReasoningOrNull()
        editingReasoning = reasoning?.let { TextFieldState(it.reasoning) }
        messageContent = contents.filter { it !is UIMessagePart.Text && (reasoning == null || it !is UIMessagePart.Reasoning) }
        editingParts = contents
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    fun getContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        if (isEditing()) {
            val originalParts = editingParts
            if (originalParts != null) {
                val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
                val remainingAttachments = messageContent.toMutableList()
                val merged = mutableListOf<UIMessagePart>()

                originalParts.forEachIndexed { index, part ->
                    when {
                        index == editedTextIndex -> {
                            if (text.isNotBlank()) {
                                merged.add(UIMessagePart.Text(text))
                            }
                        }

                        part is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                merged.add(part)
                            }
                        }

                        part is UIMessagePart.Reasoning && editingReasoning != null -> {
                            val reasoning = editingReasoning!!.text.toString()
                            if (reasoning.isNotBlank()) {
                                merged.add(part.copy(reasoning = reasoning))
                            }
                        }

                        else -> {
                            val currentIndex = remainingAttachments.indexOf(part)
                            if (currentIndex >= 0) {
                                merged.add(remainingAttachments.removeAt(currentIndex))
                            }
                        }
                    }
                }
                if (editedTextIndex < 0 && text.isNotBlank()) {
                    merged.add(0, UIMessagePart.Text(text))
                }
                // Newly added attachments are appended in insertion order.
                merged.addAll(remainingAttachments)
                return merged
            }
            return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
        }
        return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return textContent.text.isBlank() && messageContent.isEmpty()
    }

    /**
     * 当前输入的可存草稿。文字和附件都只是引用，草稿存的是「输入框里现在有什么」，
     * 编辑历史消息时额外带上原消息的 parts，恢复后才能继续按编辑发送。
     */
    fun snapshotDraft(): ChatDraft = ChatDraft(
        text = textContent.text.toString(),
        parts = messageContent,
        editingMessage = editingMessage,
        originalParts = editingParts.takeIf { editingMessage != null },
        reasoning = editingReasoning?.text?.toString(),
        selectionStart = textContent.selection.start,
        selectionEnd = textContent.selection.end,
    )

    /**
     * 恢复草稿。[restoreEditing] 为 false 时只恢复文字和附件，不进入编辑状态
     * （原消息已经不在时用），附件按新附件处理，不会被当成历史消息的附件保护起来。
     */
    fun applyDraft(draft: ChatDraft, restoreEditing: Boolean = true) {
        val editing = draft.editingMessage.takeIf { restoreEditing }
        val originals = draft.originalParts.takeIf { editing != null }
        editingMessage = editing
        editingParts = originals
        editingReasoning = draft.reasoning
            ?.takeIf { originals?.any { part -> part is UIMessagePart.Reasoning } == true }
            ?.let { TextFieldState(it) }
        messageContent = draft.parts
        editingAttachmentUrls = originals.orEmpty().mapNotNull { it.attachmentUrlOrNull() }.toSet()
        val text = draft.text
        textContent.edit {
            replace(0, length, text)
            val start = draft.selectionStart.coerceIn(0, text.length)
            val end = draft.selectionEnd.coerceIn(start, text.length)
            selection = TextRange(start, end)
        }
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    /**
     * 只有一段思考加正文的消息才允许改思考内容。
     * 带 metadata 的思考（Claude/Gemini 签名、OpenAI 加密内容等）回传时要和原文对得上，改了会失效或被拒，不允许编辑。
     */
    private fun List<UIMessagePart>.editableReasoningOrNull(): UIMessagePart.Reasoning? {
        if (!all { it is UIMessagePart.Text || it is UIMessagePart.Reasoning }) return null
        val reasoning = filterIsInstance<UIMessagePart.Reasoning>().singleOrNull() ?: return null
        return reasoning.takeIf { it.metadata.isNullOrEmpty() }
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}
