package me.rerere.rikkahub.data.event

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** 聊天生成过程中的流式更新，由 ChatNotificationManager 消费用于 Live Update 通知。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
    ) : AppEvent()

    /**
     * 聊天生成结束（完成、失败或取消）。
     * [contentPreview] 为 null 时仅取消 Live Update 通知，不发送完成通知。
     */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
    ) : AppEvent()

    /**
     * 手动压缩在后台开始。[title] 是对话标题（没有标题时用助手名），通知里用它指认是哪个对话。
     * 与生成事件分开，普通生成通知不会冒充压缩进度或结果。
     */
    data class ChatCompressionStarted(
        val conversationId: Uuid,
        val taskId: Uuid,
        val title: String,
    ) : AppEvent()

    /**
     * 手动压缩结束。[taskId] 用来去重，同一个任务只发一次通知；
     * [Cancelled] 只用于撤掉进度通知，不打扰用户。
     */
    data class ChatCompressionEnded(
        val conversationId: Uuid,
        val taskId: Uuid,
        val title: String,
        val outcome: CompressionOutcome,
        /** 失败原因或丢弃说明 */
        val detail: String? = null,
    ) : AppEvent()
}

/** 手动压缩的结果 */
enum class CompressionOutcome {
    /** 摘要已写入，原消息保留 */
    Succeeded,

    /** 请求失败，对话没有被修改 */
    Failed,

    /** 压缩期间对话被改动，旧摘要不安全，丢弃不写入 */
    Discarded,

    /** 用户主动取消 */
    Cancelled,
}
