package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
