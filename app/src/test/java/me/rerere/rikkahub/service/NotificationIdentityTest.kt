package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class NotificationIdentityTest {
    @Test
    fun `foreground service identities are distinct`() {
        val ids = listOf(
            WebServerService.NOTIFICATION_ID,
            ChatGenerationForegroundService.NOTIFICATION_ID,
            MediaCreationForegroundService.NOTIFICATION_ID,
            MemoryDreamForegroundService.NOTIFICATION_ID,
            WORKSPACE_JOB_NOTIFICATION_ID,
            CHAT_LIVE_UPDATE_NOTIFICATION_ID,
        )
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `one conversation cancellation cannot identify another notification`() {
        val a = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val b = Uuid.parse("00000000-0000-0000-0000-000000000002")
        assertNotEquals(chatLiveUpdateNotificationTag(a), chatLiveUpdateNotificationTag(b))
        assertEquals(chatLiveUpdateNotificationTag(a), chatLiveUpdateNotificationTag(a))
    }

    @Test
    fun `UUID tags distinguish conversations even with identical hash codes`() {
        val a = Uuid.parse("00000000-0000-0001-0000-000000000000")
        val b = Uuid.parse("00000000-0000-0000-0000-000000000001")
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(chatLiveUpdateNotificationTag(a), chatLiveUpdateNotificationTag(b))
    }

    // --- 清理范围：只能命中本 App 的 Live Update 通知 -----------------------------

    private val anyConversation = Uuid.parse("11111111-2222-3333-4444-555555555555")

    @Test
    fun `live update tag is recognised by its own prefix`() {
        val tag = chatLiveUpdateNotificationTag(anyConversation)
        assertTrue(tag.startsWith(CHAT_LIVE_UPDATE_TAG_PREFIX))
        assertTrue(isChatLiveUpdateNotification(CHAT_LIVE_UPDATE_NOTIFICATION_ID, tag))
    }

    @Test
    fun `foreground service notifications are never treated as live updates`() {
        // 这些 FGS 通知都不带 tag（tag == null），清理时必须放过
        listOf(
            WebServerService.NOTIFICATION_ID,
            ChatGenerationForegroundService.NOTIFICATION_ID,
            MediaCreationForegroundService.NOTIFICATION_ID,
            MemoryDreamForegroundService.NOTIFICATION_ID,
            WORKSPACE_JOB_NOTIFICATION_ID,
        ).forEach { id ->
            assertFalse("id=$id", isChatLiveUpdateNotification(id, null))
            assertFalse("id=$id", isChatLiveUpdateNotification(id, chatLiveUpdateNotificationTag(anyConversation)))
        }
    }

    @Test
    fun `other apps notifications that reuse id 2006 without our tag are untouched`() {
        assertFalse(isChatLiveUpdateNotification(CHAT_LIVE_UPDATE_NOTIFICATION_ID, null))
        assertFalse(isChatLiveUpdateNotification(CHAT_LIVE_UPDATE_NOTIFICATION_ID, "chat-completed"))
        assertFalse(isChatLiveUpdateNotification(CHAT_LIVE_UPDATE_NOTIFICATION_ID, "other:tag"))
        // 前缀本身（没有会话 id）也不算：防止有人误传空后缀把所有通知扫掉
        assertFalse(isChatLiveUpdateNotification(CHAT_LIVE_UPDATE_NOTIFICATION_ID, CHAT_LIVE_UPDATE_TAG_PREFIX))
        assertFalse(isChatLiveUpdateNotification(CHAT_LIVE_UPDATE_NOTIFICATION_ID, ""))
    }

    @Test
    fun `generation done notification id is not part of the live update identity`() {
        // 完成通知走 1 号 id + 无 tag；即使它出现在活跃通知列表里也不该被清理
        assertFalse(isChatLiveUpdateNotification(1, null))
    }

    // --- 进程死亡兜底 -------------------------------------------------------------

    @Test
    fun `timeout fallback stays within a few minutes`() {
        // 3~5 分钟区间：太短会在长工具调用时误清，太长就等于没有兜底
        assertTrue(CHAT_LIVE_UPDATE_NOTIFICATION_TIMEOUT_MS >= 3 * 60 * 1000L)
        assertTrue(CHAT_LIVE_UPDATE_NOTIFICATION_TIMEOUT_MS <= 5 * 60 * 1000L)
    }
}
