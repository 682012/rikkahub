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

/** Android identifies a notification by (tag, id); UUID tags avoid hash collisions. */
internal fun chatLiveUpdateNotificationTag(conversationId: Uuid): String =
    "$CHAT_LIVE_UPDATE_TAG_PREFIX$conversationId"

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
