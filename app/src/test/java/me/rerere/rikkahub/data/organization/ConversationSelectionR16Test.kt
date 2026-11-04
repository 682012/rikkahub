package me.rerere.rikkahub.data.organization

import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r16 会话整理的纯逻辑：筛选看得见谁、全选跳过置顶、换筛选裁掉看不见的选择、最终删谁。
 * 不碰 Android 和数据库，删除本身仍然逐条走 ChatService。
 */
class ConversationSelectionR16Test {
    private val now = Instant.parse("2026-03-01T00:00:00Z")

    private fun conversation(
        title: String,
        daysAgo: Long,
        pinned: Boolean = false,
        id: Uuid = Uuid.random(),
    ) = Conversation(
        id = id,
        assistantId = Uuid.random(),
        title = title,
        messageNodes = emptyList(),
        isPinned = pinned,
        createAt = now.minus(Duration.ofDays(daysAgo)),
        updateAt = now.minus(Duration.ofDays(daysAgo)),
    )

    // ---- 筛选 ----

    @Test
    fun `title search ignores case and surrounding spaces`() {
        val target = conversation("Weekly Report", 3)
        val other = conversation("Hello", 3)

        val visible = filterConversations(
            conversations = listOf(target, other),
            filter = ConversationFilterState(query = "  report  "),
            now = now,
        )

        assertEquals(listOf(target), visible)
    }

    @Test
    fun `blank query keeps everything`() {
        val conversations = listOf(conversation("a", 1), conversation("b", 400))

        val visible = filterConversations(
            conversations = conversations,
            filter = ConversationFilterState(query = "   "),
            now = now,
        )

        assertEquals(conversations, visible)
    }

    @Test
    fun `age filter keeps only conversations unused longer than the window`() {
        val justUsed = conversation("today", 0)
        val onBoundary = conversation("exactly 7 days", 7)
        val stale = conversation("8 days", 8)

        val visible = filterConversations(
            conversations = listOf(justUsed, onBoundary, stale),
            filter = ConversationFilterState(age = ConversationAgeFilter.UNUSED_7_DAYS),
            now = now,
        )

        // 刚好卡在 7 天边界算「刚用过」，批量删掉刚聊过的比漏删几个代价大
        assertEquals(listOf(stale), visible)
    }

    @Test
    fun `age filter 90 days only keeps really old conversations`() {
        val old = conversation("half a year", 180)
        val recent = conversation("last month", 30)

        val visible = filterConversations(
            conversations = listOf(recent, old),
            filter = ConversationFilterState(age = ConversationAgeFilter.UNUSED_90_DAYS),
            now = now,
        )

        assertEquals(listOf(old), visible)
    }

    @Test
    fun `search and age filters stack`() {
        val conversations = listOf(
            conversation("Old project", 30),
            conversation("Old notes", 3),
            conversation("New project", 30),
        )

        val visible = filterConversations(
            conversations = conversations,
            filter = ConversationFilterState(
                query = "project",
                age = ConversationAgeFilter.UNUSED_7_DAYS,
            ),
            now = now,
        )

        assertEquals(listOf(conversations.first()), visible)
    }

    @Test
    fun `all age filter keeps pinned and everything else`() {
        val pinned = conversation("pinned", 400, pinned = true)
        val normal = conversation("normal", 400)

        val visible = filterConversations(
            conversations = listOf(pinned, normal),
            filter = ConversationFilterState(),
            now = now,
        )

        assertEquals(listOf(pinned, normal), visible)
    }

    // ---- 选择 ----

    @Test
    fun `toggle adds then removes the same id`() {
        val id = Uuid.random()

        val selected = ConversationSelection.toggle(emptySet(), id)
        assertEquals(setOf(id), selected)
        assertEquals(emptySet<Uuid>(), ConversationSelection.toggle(selected, id))
    }

    @Test
    fun `select all skips pinned conversations`() {
        val pinned = conversation("pinned", 30, pinned = true)
        val normal = conversation("normal", 30)
        val another = conversation("another", 60)

        val selected = ConversationSelection.selectAllVisible(
            selected = emptySet(),
            visible = listOf(pinned, normal, another),
        )

        assertEquals(setOf(normal.id, another.id), selected)
        assertFalse(pinned.id in selected)
    }

    @Test
    fun `select all keeps a pinned conversation the user picked by hand`() {
        val pinned = conversation("pinned", 30, pinned = true)
        val normal = conversation("normal", 30)

        val selected = ConversationSelection.selectAllVisible(
            selected = setOf(pinned.id),
            visible = listOf(pinned, normal),
        )

        assertEquals(setOf(pinned.id, normal.id), selected)
    }

    @Test
    fun `select all is idempotent`() {
        val normal = conversation("normal", 30)

        val once = ConversationSelection.selectAllVisible(emptySet(), listOf(normal))
        val twice = ConversationSelection.selectAllVisible(once, listOf(normal))

        assertEquals(once, twice)
    }

    @Test
    fun `switching filter drops selections that are no longer visible`() {
        val kept = conversation("kept", 30)
        val dropped = conversation("dropped", 1)
        val selected = setOf(kept.id, dropped.id)

        // 换成「7 天未用」之后，只有 kept 还在列表里
        val retained = ConversationSelection.retainVisible(selected, listOf(kept))

        assertEquals(setOf(kept.id), retained)
    }

    @Test
    fun `retain keeps the same instance when everything is still visible`() {
        val one = conversation("one", 30)
        val two = conversation("two", 40)
        val selected = setOf(one.id, two.id)

        val retained = ConversationSelection.retainVisible(selected, listOf(one, two))

        assertSame(selected, retained)
    }

    @Test
    fun `targets follow the visible order and ignore unknown ids`() {
        val first = conversation("first", 30)
        val second = conversation("second", 40)
        val hidden = Uuid.random()

        val targets = ConversationSelection.targets(
            selected = setOf(second.id, first.id, hidden),
            visible = listOf(first, second),
        )

        assertEquals(listOf(first, second), targets)
    }

    @Test
    fun `pinned count only counts visible selected pins`() {
        val pinned = conversation("pinned", 30, pinned = true)
        val normal = conversation("normal", 30)
        val unselectedPin = conversation("other pin", 30, pinned = true)

        val count = ConversationSelection.pinnedCount(
            selected = setOf(pinned.id, normal.id),
            visible = listOf(pinned, normal, unselectedPin),
        )

        assertEquals(1, count)
    }

    @Test
    fun `empty selection produces no targets`() {
        val normal = conversation("normal", 30)

        assertTrue(ConversationSelection.targets(emptySet(), listOf(normal)).isEmpty())
        assertEquals(0, ConversationSelection.pinnedCount(emptySet(), listOf(normal)))
    }

    @Test
    fun `delete report totals add up`() {
        val failed = conversation("failed", 30)
        val report = ConversationDeleteReport(deleted = 3, failed = listOf(failed))

        assertEquals(4, report.total)
        assertFalse(report.isComplete)
        assertTrue(ConversationDeleteReport(deleted = 2, failed = emptyList()).isComplete)
    }
}
