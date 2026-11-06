package me.rerere.rikkahub.data.ai.inputassist

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 把**发出去的请求**里的那条用户消息换成优化版/加上自检尾巴。
 *
 * 边界：
 * - 只碰 [InputAssistTurnState.messageId] 这一条，且必须是 USER 角色；
 * - 附件（图片/文档/音频）原样保留，顺序不变——优化只管文字；
 * - 会话里存的那条消息不动，界面和数据库看到的始终是用户原话；
 * - 带代码块的消息：代码块在优化请求里是占位符，还原后是原样的代码，因此请求里也不会有半截代码；
 * - 指纹必须是**严格相等**才算同一条文本：没有指纹（不该再出现，但老状态可能缺）就不替换，
 *   宁可发原话，也不把一版可能对不上的改写塞进用户的请求；
 * - 多段文本夹着附件时不折叠（折叠会把文字挪到附件另一边），只保留自检尾巴。
 */
fun rewriteRequestMessages(
    messages: List<UIMessage>,
    state: InputAssistTurnState?,
): List<UIMessage> {
    if (state == null || !state.changesRequest) return messages
    var result = messages
    val optimized = state.optimizedText
    if (optimized != null) {
        result = result.map { message ->
            if (message.id == state.messageId && message.role == MessageRole.USER && state.matchesOriginal(message)) {
                message.copy(parts = replaceTextParts(message.parts, optimized))
            } else {
                message
            }
        }
    }
    if (state.selfCheck) {
        val tail = InputAssistRules.selfCheckTail()
        result = result.map { message ->
            if (message.id == state.messageId && message.role == MessageRole.USER) {
                message.copy(parts = appendToLastText(message.parts, tail))
            } else {
                message
            }
        }
    }
    return result
}

/** 这条消息里真正的文本（不含附件），与 `prepareTurn` 用的是同一个规范文本。 */
internal fun UIMessage.canonicalUserText(): String =
    InputAssistRules.canonicalUserText(parts.filterIsInstance<UIMessagePart.Text>().map { it.text })

/**
 * 这条消息的文本是否还是"定下这一轮时"那段。
 *
 * 指纹必须相等才算同一段文本：消息在这期间被改过时，旧优化版对应的就是另一段文字，
 * 套上去等于篡改用户消息。所以这里没有"没有指纹就放行"的旁路。
 */
private fun InputAssistTurnState.matchesOriginal(message: UIMessage): Boolean {
    val hash = originalHash ?: return false
    return InputAssistRules.inputHash(message.canonicalUserText().trim()) == hash
}

/**
 * 用一段文本替换消息里的全部文本 part：第一个 Text 的位置放新文本（保留它的 metadata），
 * 其余 Text 丢掉，非文本 part 原样留在原位置。
 *
 * 只有在文本 part 连成一段时才替换：`[文字, 图片, 文字]` 折叠后第二段文字会跑到图片前面，
 * 那是改用户消息的顺序，不是优化。这种情况原样返回，让这一轮继续用原话。
 */
private fun replaceTextParts(parts: List<UIMessagePart>, text: String): List<UIMessagePart> {
    val firstTextIndex = parts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex == -1) return parts
    if (!InputAssistRules.textPartsContiguous(parts.map { it is UIMessagePart.Text })) return parts
    val out = ArrayList<UIMessagePart>(parts.size)
    parts.forEachIndexed { index, part ->
        when {
            index == firstTextIndex -> out += (part as UIMessagePart.Text).copy(text = text)
            part is UIMessagePart.Text -> Unit
            else -> out += part
        }
    }
    return out
}

/** 把尾部提示接在最后一段文本后面；一条文本都没有时补一段。 */
private fun appendToLastText(parts: List<UIMessagePart>, tail: String): List<UIMessagePart> {
    val lastTextIndex = parts.indexOfLast { it is UIMessagePart.Text }
    if (lastTextIndex == -1) return parts + UIMessagePart.Text(tail)
    return parts.mapIndexed { index, part ->
        if (index == lastTextIndex) {
            val text = (part as UIMessagePart.Text).text.trimEnd()
            part.copy(text = if (text.isEmpty()) tail else "$text\n\n$tail")
        } else {
            part
        }
    }
}
