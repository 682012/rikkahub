package me.rerere.rikkahub.service

import kotlin.uuid.Uuid

// Foreground services use untagged notifications. Keep the workspace service distinct
// from media creation (2003) and memory consolidation (2004).
internal const val WORKSPACE_JOB_NOTIFICATION_ID = 2005
internal const val CHAT_LIVE_UPDATE_NOTIFICATION_ID = 2006

/** Five minutes without progress removes the advisory card, not its foreground service. */
internal const val CHAT_LIVE_UPDATE_NOTIFICATION_TIMEOUT_MS = 5 * 60 * 1000L

/** 所有 Live Update 通知共享的 tag 前缀，用于按前缀清理而不碰其它通知 */
internal const val CHAT_LIVE_UPDATE_TAG_PREFIX = "chat-live-update:"

/** 生成完成通知的 tag 前缀：和 Live Update 前缀互不重叠，清理时不会互相误伤 */
internal const val CHAT_DONE_TAG_PREFIX = "chat-done:"

/** Android identifies a notification by (tag, id); UUID tags avoid hash collisions. */
internal fun chatLiveUpdateNotificationTag(conversationId: Uuid): String =
    "$CHAT_LIVE_UPDATE_TAG_PREFIX$conversationId"

/**
 * 生成完成通知的 tag。
 *
 * 完成通知以前是「id=1 + 无 tag」，同一台设备上不同对话的完成卡会按老 id 互相顶掉，
 * 也无法和别的无 tag 通知区分；带上按会话生成的 tag 后每条完成通知都有独立身份。
 */
internal fun chatDoneTag(conversationId: Uuid): String =
    "$CHAT_DONE_TAG_PREFIX$conversationId"

// ---- 手动压缩的通知身份 --------------------------------------------------------
// 通知身份是 (id, tag)，下面是聊天相关通知的全部分组，任何两组都不能同时相等：
//   1        + chat-done:<conv>          生成完成
//   2006     + chat-live-update:<conv>   生成进度（Live Update）
//   2007/2008+ chat-compression:<conv>   手动压缩进度 / 结果
//   2009/2010+ chat-recovery:<conv>      自动恢复进度 / 终态
//   2001..2005 且无 tag                   各前台服务
// 进度、结果各占一个 id，且不带上 chat-live-update: 前缀，Live Update 的前缀清理扫不到它们。

internal const val CHAT_COMPRESSION_PROGRESS_NOTIFICATION_ID = 2007
internal const val CHAT_COMPRESSION_RESULT_NOTIFICATION_ID = 2008
internal const val CHAT_COMPRESSION_TAG_PREFIX = "chat-compression:"

internal fun chatCompressionNotificationTag(conversationId: Uuid): String =
    "$CHAT_COMPRESSION_TAG_PREFIX$conversationId"

// ---- 自动恢复的通知身份 --------------------------------------------------------
// 自动恢复用自己的一组通知身份，不和生成完成（1）、Live Update（2006）、压缩（2007/2008）互相覆盖。
// 前缀刻意不叫 chat-live-update:，否则 Live Update 的前缀清理会把这两条一起扫掉。
internal const val CHAT_RECOVERY_PROGRESS_NOTIFICATION_ID = 2009
internal const val CHAT_RECOVERY_RESULT_NOTIFICATION_ID = 2010
internal const val CHAT_RECOVERY_TAG_PREFIX = "chat-recovery:"

/** 进度条没人来撤时由系统收走（进程被杀、事件丢失），结果提醒另有身份 */
internal const val CHAT_RECOVERY_PROGRESS_TIMEOUT_MS = 30 * 60 * 1000L

internal fun chatRecoveryNotificationTag(conversationId: Uuid): String =
    "$CHAT_RECOVERY_TAG_PREFIX$conversationId"

// ---- 可点通知的用途：同一段对话会有多条可点通知，data URI 必须按用途分开 ----------

internal const val PENDING_INTENT_PURPOSE_CHAT_DONE = "chat-done"
internal const val PENDING_INTENT_PURPOSE_LIVE_UPDATE = "chat-live-update"
internal const val PENDING_INTENT_PURPOSE_COMPRESSION = "chat-compression"
internal const val PENDING_INTENT_PURPOSE_RECOVERY = "chat-recovery"

/**
 * 通知点击用的 PendingIntent data URI。
 *
 * PendingIntent 的身份是 (requestCode, Intent 的 action/data/type/component)，会话 id 的 hashCode 会
 * 碰撞；同一个 hashCode 的两次 getActivity 会被系统当成同一个 PendingIntent（FLAG_UPDATE_CURRENT
 * 还会互相覆盖 intent），点错对话就是这么来的。给每条通知加一个按用途+会话唯一的 data URI 即可分开。
 */
internal fun chatNotificationPendingIntentUri(purpose: String, conversationId: Uuid): String =
    "rikkahub://notification/$purpose/$conversationId"

/**
 * 判断系统里一条活跃通知是否属于本 App 的 Live Update 进度通知。
 *
 * id 与 tag 前缀必须同时匹配：2006 是 Live Update 专用 id，foreground service 通知（2002/2003/
 * 2004/2005）都不带 tag，完成通知用的是别的 channel 且 tag 不带这个前缀，都不会被误伤。
 */
internal fun isChatLiveUpdateNotification(notificationId: Int, tag: String?): Boolean =
    notificationId == CHAT_LIVE_UPDATE_NOTIFICATION_ID &&
        tag != null &&
        tag.startsWith(CHAT_LIVE_UPDATE_TAG_PREFIX) &&
        tag.length > CHAT_LIVE_UPDATE_TAG_PREFIX.length
