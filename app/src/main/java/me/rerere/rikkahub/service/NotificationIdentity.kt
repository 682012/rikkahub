package me.rerere.rikkahub.service

import kotlin.uuid.Uuid

// Foreground services use untagged notifications. Keep the workspace service distinct
// from media creation (2003) and memory consolidation (2004).
internal const val WORKSPACE_JOB_NOTIFICATION_ID = 2005
internal const val CHAT_LIVE_UPDATE_NOTIFICATION_ID = 2006

/** Android identifies a notification by (tag, id); UUID tags avoid hash collisions. */
internal fun chatLiveUpdateNotificationTag(conversationId: Uuid): String =
    "chat-live-update:$conversationId"
