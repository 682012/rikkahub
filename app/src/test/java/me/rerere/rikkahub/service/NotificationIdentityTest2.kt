package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 生成完成通知的身份（tag + 点击 PendingIntent）与 Live Update 通知必须分得开，
 * 且不能因为 UUID.hashCode() 碰撞而互相覆盖。
 */
class NotificationIdentityTest2 {
    private val a = Uuid.parse("00000000-0000-0001-0000-000000000000")
    private val b = Uuid.parse("00000000-0000-0000-0000-000000000001")

    @Test
    fun `done tag is per conversation and stable`() {
        assertNotEquals(chatDoneTag(a), chatDoneTag(b))
        assertEquals(chatDoneTag(a), chatDoneTag(a))
        assertTrue(chatDoneTag(a).startsWith(CHAT_DONE_TAG_PREFIX))
        assertTrue(chatDoneTag(a).length > CHAT_DONE_TAG_PREFIX.length)
    }

    @Test
    fun `done tag survives identical hash codes`() {
        // 这两个 UUID 的 hashCode 一样，只用 hashCode 做身份会撞车
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(chatDoneTag(a), chatDoneTag(b))
    }

    @Test
    fun `done tag and live update tag never collide`() {
        assertNotEquals(chatDoneTag(a), chatLiveUpdateNotificationTag(a))
        assertNotEquals(CHAT_DONE_TAG_PREFIX, CHAT_LIVE_UPDATE_TAG_PREFIX)
        assertFalse(CHAT_DONE_TAG_PREFIX.startsWith(CHAT_LIVE_UPDATE_TAG_PREFIX))
        assertFalse(CHAT_LIVE_UPDATE_TAG_PREFIX.startsWith(CHAT_DONE_TAG_PREFIX))
    }

    @Test
    fun `done notifications are never swept by live update cleanup`() {
        // 完成通知是 id=1；即便以后有人把它挪到 2006，tag 前缀也不同，清理时不能命中
        assertFalse(isChatLiveUpdateNotification(1, chatDoneTag(a)))
        assertFalse(isChatLiveUpdateNotification(CHAT_LIVE_UPDATE_NOTIFICATION_ID, chatDoneTag(a)))
        assertFalse(isChatLiveUpdateNotification(CHAT_LIVE_UPDATE_NOTIFICATION_ID, CHAT_DONE_TAG_PREFIX))
    }

    @Test
    fun `pending intent uri is unique per conversation`() {
        val purpose = "chat-done"
        assertEquals(
            chatNotificationPendingIntentUri(purpose, a),
            chatNotificationPendingIntentUri(purpose, a),
        )
        assertNotEquals(
            chatNotificationPendingIntentUri(purpose, a),
            chatNotificationPendingIntentUri(purpose, b),
        )
        assertTrue(chatNotificationPendingIntentUri(purpose, a).endsWith(a.toString()))
    }

    @Test
    fun `pending intent uri separates purposes of the same conversation`() {
        val done = chatNotificationPendingIntentUri(PENDING_INTENT_PURPOSE_CHAT_DONE, a)
        val live = chatNotificationPendingIntentUri(PENDING_INTENT_PURPOSE_LIVE_UPDATE, a)
        val compression = chatNotificationPendingIntentUri(PENDING_INTENT_PURPOSE_COMPRESSION, a)
        assertEquals(3, listOf(done, live, compression).toSet().size)
        // 是合法 URI，不会因为空格/中文让 Intent.setData 抛异常
        assertTrue(done.startsWith("rikkahub://notification/"))
        assertFalse(done.contains(" "))
    }

    // --- r21：自动恢复与手动压缩也要有自己的身份 --------------------------------

    @Test
    fun `every notification purpose has its own pending intent uri`() {
        val purposes = listOf(
            PENDING_INTENT_PURPOSE_CHAT_DONE,
            PENDING_INTENT_PURPOSE_LIVE_UPDATE,
            PENDING_INTENT_PURPOSE_COMPRESSION,
            PENDING_INTENT_PURPOSE_RECOVERY,
        )
        assertEquals(purposes.size, purposes.toSet().size)
        // 同一段对话的四种点法 × 两个 hashCode 相同的会话：8 个 URI 必须互不相同，
        // 否则后一条通知的 FLAG_UPDATE_CURRENT 会把前一条的 intent 顶掉，点开跳错对话
        val uris = purposes.flatMap { purpose ->
            listOf(a, b).map { conversationId -> chatNotificationPendingIntentUri(purpose, conversationId) }
        }
        assertEquals(uris.size, uris.toSet().size)
        uris.forEach { uri ->
            assertTrue(uri, uri.startsWith("rikkahub://notification/"))
            assertFalse(uri, uri.contains(" "))
        }
    }

    @Test
    fun `recovery tag is per conversation and survives identical hash codes`() {
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(chatRecoveryNotificationTag(a), chatRecoveryNotificationTag(b))
        assertTrue(chatRecoveryNotificationTag(a).startsWith(CHAT_RECOVERY_TAG_PREFIX))
        assertTrue(chatRecoveryNotificationTag(a).length > CHAT_RECOVERY_TAG_PREFIX.length)
    }

    @Test
    fun `chat notification tag prefixes never overlap`() {
        val prefixes = listOf(
            CHAT_LIVE_UPDATE_TAG_PREFIX,
            CHAT_DONE_TAG_PREFIX,
            CHAT_COMPRESSION_TAG_PREFIX,
            CHAT_RECOVERY_TAG_PREFIX,
        )
        assertEquals(prefixes.size, prefixes.toSet().size)
        prefixes.forEach { prefix ->
            prefixes.filter { it != prefix }.forEach { other ->
                // 前缀互相包含会让「按前缀清理」顺带扫掉别的通知
                assertFalse("$prefix 与 $other 重叠", prefix.startsWith(other))
                assertFalse("$prefix 与 $other 重叠", other.startsWith(prefix))
            }
        }
    }

    @Test
    fun `progress terminal live update and done notifications never share an identity`() {
        // 通知身份 = (tag, id)：同一段对话上同时存在的这些卡片必须两两不同，
        // 否则后发的那条会原地替换掉前一条（进度顶掉终态、终态顶掉完成）
        val identities = setOf(
            chatDoneTag(a) to 1,
            chatLiveUpdateNotificationTag(a) to CHAT_LIVE_UPDATE_NOTIFICATION_ID,
            chatCompressionNotificationTag(a) to CHAT_COMPRESSION_PROGRESS_NOTIFICATION_ID,
            chatCompressionNotificationTag(a) to CHAT_COMPRESSION_RESULT_NOTIFICATION_ID,
            chatRecoveryNotificationTag(a) to CHAT_RECOVERY_PROGRESS_NOTIFICATION_ID,
            chatRecoveryNotificationTag(a) to CHAT_RECOVERY_RESULT_NOTIFICATION_ID,
        )
        assertEquals(6, identities.size)
        // 自动恢复的进度与终态共用 tag，靠 id 分开；压缩与恢复的 tag 也必须不同
        assertNotEquals(chatRecoveryNotificationTag(a), chatCompressionNotificationTag(a))
        assertNotEquals(CHAT_RECOVERY_PROGRESS_NOTIFICATION_ID, CHAT_RECOVERY_RESULT_NOTIFICATION_ID)
    }

    @Test
    fun `recovery and compression notifications are never swept by live update cleanup`() {
        listOf(
            CHAT_RECOVERY_PROGRESS_NOTIFICATION_ID,
            CHAT_RECOVERY_RESULT_NOTIFICATION_ID,
            CHAT_COMPRESSION_PROGRESS_NOTIFICATION_ID,
            CHAT_COMPRESSION_RESULT_NOTIFICATION_ID,
        ).forEach { id ->
            assertFalse("id=$id", isChatLiveUpdateNotification(id, chatRecoveryNotificationTag(a)))
            assertFalse("id=$id", isChatLiveUpdateNotification(id, chatCompressionNotificationTag(a)))
            assertFalse("id=$id", isChatLiveUpdateNotification(id, chatDoneTag(a)))
            assertFalse("id=$id", isChatLiveUpdateNotification(id, null))
        }
    }

    @Test
    fun `all chat notification ids are unique`() {
        val ids = listOf(
            1, // 生成完成
            CHAT_LIVE_UPDATE_NOTIFICATION_ID,
            CHAT_COMPRESSION_PROGRESS_NOTIFICATION_ID,
            CHAT_COMPRESSION_RESULT_NOTIFICATION_ID,
            CHAT_RECOVERY_PROGRESS_NOTIFICATION_ID,
            CHAT_RECOVERY_RESULT_NOTIFICATION_ID,
            WebServerService.NOTIFICATION_ID,
            ChatGenerationForegroundService.NOTIFICATION_ID,
            MediaCreationForegroundService.NOTIFICATION_ID,
            MemoryDreamForegroundService.NOTIFICATION_ID,
            WORKSPACE_JOB_NOTIFICATION_ID,
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `recovery progress timeout outlives live update but stays bounded`() {
        // 必须比 Live Update 的兜底长（重连等待本身就可能几分钟），又不能长到等于没兜底
        assertTrue(
            CHAT_RECOVERY_PROGRESS_TIMEOUT_MS in
                (CHAT_LIVE_UPDATE_NOTIFICATION_TIMEOUT_MS + 1)..(60 * 60 * 1000L)
        )
    }
}
