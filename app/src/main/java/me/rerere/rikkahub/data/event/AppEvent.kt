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
     * 自动恢复（断线续跑）的进度与结果。
     *
     * 同一个会话始终用 [conversationId] 作为通知身份：非终态只更新这一条安静提示，
     * 终态才升级成高重要度提醒，所以一次掉线不会在通知栏留下多条记录。
     *
     * [message] 已经由发送方按当前语言取好文案；为空表示这一轮不再需要恢复
     * （已成功、已停止或已取消），通知层只撤掉进度条，不再发新通知。
     *
     * [terminal] 为 true 表示不会再自动继续，需要用户处理；为 false 表示还在等网络或正在重连。
     */
    data class ChatRecoveryStatus(
        val conversationId: Uuid,
        val senderName: String,
        val message: String,
        val terminal: Boolean = false,
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
