package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileSettings
import me.rerere.rikkahub.data.organization.autofile.ConversationAutoFileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 抽屉/面板这层新加的判断都是纯函数，这里按真实用法钉住：
 * - 家族角标只在折叠后同族 >1 条时出现，并且只从当前助手的行取根；
 * - 闲置时长滑杆的档位换算不越界、不抖动；
 * - 没开自动归库时手动整理要先确认；被中断的批（有 error）照样要能撤销；
 * - 运行中开关不被锁（面板没有把 enabled 传成 !busy，这里用「未开时仍可确认」和「撤销可用」两侧兜住）。
 */
class AutoFileDrawerUiLogicTest {

    private fun conversation(
        assistantId: Uuid,
        lineageId: Uuid? = null,
        familySize: Int = 1,
    ) = Conversation(
        assistantId = assistantId,
        messageNodes = emptyList(),
        lineageId = lineageId,
        familySize = familySize,
    )

    @Test
    fun `family badge only shows for a collapsed family with siblings`() {
        assertFalse(familyBadgeVisible(0))
        assertFalse(familyBadgeVisible(1))
        assertTrue(familyBadgeVisible(2))
        assertTrue(familyBadgeVisible(7))
    }

    @Test
    fun `family scope uses the lineage root of the row`() {
        val assistantId = Uuid.random()
        val row = conversation(assistantId, lineageId = Uuid.random())

        assertEquals(row.lineageRootId, familyScopeTarget(row, assistantId))
        assertEquals(row.lineageId, familyScopeTarget(row, assistantId))
    }

    @Test
    fun `a root conversation without lineage opens itself`() {
        val assistantId = Uuid.random()
        val row = conversation(assistantId, lineageId = null)

        assertEquals(row.id, familyScopeTarget(row, assistantId))
    }

    @Test
    fun `a row from another assistant never opens a family list`() {
        val row = conversation(Uuid.random(), lineageId = Uuid.random())

        // 切换助手时列表里可能还留着上一份分页数据，跨助手拼家族是明确禁止的
        assertNull(familyScopeTarget(row, Uuid.random()))
    }

    @Test
    fun `idle slider maps both ends and the default`() {
        assertEquals(ConversationAutoFileSettings.MIN_IDLE_HOURS, idleHoursAt(0))
        assertEquals(ConversationAutoFileSettings.MAX_IDLE_HOURS, idleHoursAt(idleStepCount()))
        assertEquals(36, idleHoursAt(idleIndexFor(36)))
        assertEquals(2, idleIndexFor(36))
    }

    @Test
    fun `idle slider clamps out of range values instead of overflowing`() {
        assertEquals(ConversationAutoFileSettings.MIN_IDLE_HOURS, idleHoursAt(-5))
        assertEquals(ConversationAutoFileSettings.MAX_IDLE_HOURS, idleHoursAt(idleStepCount() + 9))
        assertEquals(0, idleIndexFor(1))
        assertEquals(0, idleIndexFor(ConversationAutoFileSettings.MIN_IDLE_HOURS))
        assertEquals(idleStepCount(), idleIndexFor(ConversationAutoFileSettings.MAX_IDLE_HOURS))
        assertEquals(idleStepCount(), idleIndexFor(9_999))
    }

    @Test
    fun `every slider stop is a valid backend value and stays ordered`() {
        val hours = (0..idleStepCount()).map(::idleHoursAt)
        assertEquals(hours.sorted(), hours)
        assertEquals(hours.distinct(), hours)
        hours.forEach { hour ->
            assertTrue(hour in ConversationAutoFileSettings.MIN_IDLE_HOURS..ConversationAutoFileSettings.MAX_IDLE_HOURS)
            // 滑到哪一档就停在哪一档：换算回来还在同一个位置
            assertEquals(hour, idleHoursAt(idleIndexFor(hour)))
        }
    }

    @Test
    fun `manual run asks for confirmation only while auto-file is off`() {
        assertTrue(needsRunConfirmation(ConversationAutoFileSettings(enabled = false)))
        assertFalse(needsRunConfirmation(ConversationAutoFileSettings(enabled = true)))
    }

    @Test
    fun `an interrupted batch with an error still offers undo`() {
        val interrupted = ConversationAutoFileState(
            running = false,
            canUndo = true,
            error = "上一批整理被中断，请先撤销上一批再继续整理",
        )

        assertTrue(undoOffered(interrupted))
        assertFalse(undoOffered(ConversationAutoFileState(canUndo = false)))
    }

    @Test
    fun `collapse families is a display switch independent of the ai switch`() {
        // 默认值：AI 开关关、折叠开
        val defaults = ConversationAutoFileSettings()
        assertFalse(defaults.enabled)
        assertTrue(defaults.collapseFamilies)

        // 关掉 AI 不影响折叠口径，反之亦然
        assertTrue(ConversationAutoFileSettings(enabled = false, collapseFamilies = true).collapseFamilies)
        assertFalse(ConversationAutoFileSettings(enabled = true, collapseFamilies = false).collapseFamilies)
    }
}
