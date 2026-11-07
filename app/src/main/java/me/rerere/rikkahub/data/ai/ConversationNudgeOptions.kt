package me.rerere.rikkahub.data.ai

import me.rerere.ai.agent.ConversationNudge
import me.rerere.ai.agent.NudgePrompt
import me.rerere.ai.agent.NudgeRequest
import me.rerere.ai.agent.NudgeTone
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationNudgeSettings
import me.rerere.rikkahub.data.model.runtime

/**
 * m3「会话鞭策」在请求侧的开关与口径。
 *
 * 只要会话开关关着，[ChatRequestFactory.createAgent] 拿到的就是 null，`AgentHooks.completionNudge`
 * 保持默认实现（返回 null）：不追加任何 part、不多打任何模型请求，路径与旧版本完全一致。
 *
 * 判断逻辑本身在 `ai` 模块的 [ConversationNudge]（纯函数、可单测）；这里只负责把
 * "会话里已有的消息"翻译成 [NudgeRequest]，以及找出本轮的用户请求。
 */
data class ConversationNudgeOptions(
    val maxAttempts: Int,
    val tone: NudgeTone,
    val customInstruction: String? = null,
) {
    companion object {
        /** 会话开关关闭时返回 null（= 不启用） */
        fun from(settings: ConversationNudgeSettings): ConversationNudgeOptions? =
            if (!settings.enabled) {
                null
            } else {
                ConversationNudgeOptions(
                    maxAttempts = ConversationNudge.clampAttempts(settings.maxAttempts),
                    tone = settings.tone,
                    customInstruction = settings.customInstruction?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
    }
}

/** 会话 runtime 直接转成请求侧配置；开关关着就是 null。 */
internal fun Conversation.nudgeOptions(): ConversationNudgeOptions? =
    ConversationNudgeOptions.from(runtime.nudge)

/**
 * 从这次运行能看到的消息里找出**本轮用户请求**：最后一条 user 消息。
 *
 * 找不到（比如只是一次续写运行）就返回空串，[ConversationNudge.classify] 会把它当闲聊，
 * 也就是这种情况一次都不会推。
 */
internal fun nudgeUserRequest(messages: List<UIMessage>): String =
    messages.lastOrNull { it.role == MessageRole.USER }?.toText().orEmpty()

/** 助手消息的纯文本部分：只用于识别"只说了计划"和重复计划 */
private fun UIMessage.plainText(): String =
    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

/**
 * 把（本轮消息 + 当前的助手消息）翻译成一次判断输入。只读已经存在的 part，不再发请求。
 *
 * [options] 为 null（开关关闭）时直接返回 null，调用方据此完全不介入。
 */
internal fun nudgeRequestFor(
    messages: List<UIMessage>,
    message: UIMessage,
    options: ConversationNudgeOptions?,
): NudgeRequest? {
    if (options == null) return null
    val tools = message.getTools()
    return NudgeRequest(
        userRequest = nudgeUserRequest(messages),
        replyText = message.plainText(),
        toolCalls = tools.size,
        executedToolCalls = tools.count { it.isExecuted },
        noticeMarkers = ConversationNudge.markersOf(message),
        maxAttempts = options.maxAttempts,
        tone = options.tone,
        customInstruction = options.customInstruction,
    )
}

/**
 * 判断这次要不要推；开关关闭或守卫不满足时返回 null。
 *
 * 注意调用时机由 AgentLoop 决定（正常结束前、无待批/无半截/无未执行工具），这里只做内容判断。
 */
internal fun completionNudgePrompt(
    messages: List<UIMessage>,
    message: UIMessage,
    options: ConversationNudgeOptions?,
): NudgePrompt? {
    val request = nudgeRequestFor(messages, message, options) ?: return null
    return ConversationNudge.decide(request).prompt
}
