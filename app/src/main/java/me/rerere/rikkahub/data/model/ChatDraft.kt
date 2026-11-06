package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
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
    /**
     * 这段对话里选过的思考档位，跟着输入草稿一起存。
     *
     * 还没发出第一条消息的新会话没有落库记录，会话自己那份配置只活在内存里；草稿是它唯一
     * 能活过页面回收的地方，重新打开时据此把档位还回这段会话（见 ChatVM 的 applyDraftReasoning）。
     * 它不是输入内容，不参与 [sameContent] 的比较。
     */
    val reasoningLevel: ReasoningLevel? = null,
) {
    val isEmpty: Boolean get() = text.isEmpty() && parts.isEmpty() && editingMessage == null

    /** 空输入也可能有本对话的档位选择；保存状态与可发送内容分开判断。 */
    val hasPersistableState: Boolean get() = !isEmpty || reasoningLevel != null

    /** 光标不触发写盘，但档位变化必须写盘；发送确认仍用 sameContent。 */
    fun sameStoredContent(other: ChatDraft): Boolean =
        sameContent(other) && reasoningLevel == other.reasoningLevel

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

/**
 * 草稿归属：已经开始的会话按会话存；还没发第一条的会话按「助手 + 会话」存。
 *
 * r16 修隔离：旧实现新会话只按助手存（`assistant:<id>`），同一个助手下的两个新会话共用一份草稿，
 * 在 A 打的字会出现在 B 的输入框，B 再点发送就等于把 A 的内容发进 B。加上会话 id 后每个会话的
 * 输入框各自独立；换助手仍然是另一个输入框（切回来还能恢复），冷启动按 `lastConversationId`
 * 回到同一个会话 id，草稿照常恢复，不丢。
 */
fun chatDraftKey(conversationId: Uuid, assistantId: Uuid, hasMessages: Boolean): String =
    if (hasMessages) "conversation:$conversationId" else "assistant:$assistantId:$conversationId"
