package me.rerere.rikkahub.data.organization.grouping

import me.rerere.rikkahub.data.model.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 手动分组（多选归组 / 展开收起 / 移出组）这一层的纯判定。
 *
 * 这里钉住的都是「不做会出错」的行为：
 * - 跨助手的会话永远不会被搬进当前助手的组（禁止混组）；
 * - 目标组不属于当前助手时一句都不搬；
 * - 组名清洗后为空不建组；
 * - 展开状态是不可变集合、删组后自动摘掉；
 * - 组只影响列表展示，不改 lineage / 消息（[GroupSummary] 里根本没有这两样东西）。
 */
class ConversationGroupingTest {

    private val assistantA = Uuid.random()
    private val assistantB = Uuid.random()

    private fun folder(assistantId: Uuid, name: String = "g") = Folder(
        assistantId = assistantId,
        name = name,
    )

    @Test
    fun `selection keeps only conversations of the current assistant`() {
        val mine = Uuid.random()
        val alsoMine = Uuid.random()
        val theirs = Uuid.random()
        val owners = mapOf(mine to assistantA, alsoMine to assistantA, theirs to assistantB)

        val plan = selectionWithinAssistant(
            selected = listOf(mine, theirs, alsoMine),
            assistantId = assistantA,
            assistantOf = { owners[it] },
        )

        assertEquals(listOf(mine, alsoMine), plan.accepted)
        assertEquals(listOf(theirs), plan.rejected)
    }

    @Test
    fun `a conversation that no longer exists is rejected instead of moved`() {
        val plan = selectionWithinAssistant(
            selected = listOf(Uuid.random()),
            assistantId = assistantA,
            assistantOf = { null },
        )

        assertTrue(plan.accepted.isEmpty())
        assertEquals(1, plan.rejected.size)
    }

    @Test
    fun `duplicate ids collapse to one`() {
        val id = Uuid.random()
        val plan = selectionWithinAssistant(
            selected = listOf(id, id, id),
            assistantId = assistantA,
            assistantOf = { assistantA },
        )

        assertEquals(listOf(id), plan.accepted)
        assertTrue(plan.rejected.isEmpty())
    }

    @Test
    fun `a group of another assistant cannot receive the selection`() {
        val otherGroup = folder(assistantB)

        assertFalse(canAssignToGroup(otherGroup, assistantA))
        assertFalse(canAssignToGroup(null, assistantA))
        assertTrue(canAssignToGroup(folder(assistantA), assistantA))
    }

    @Test
    fun `group names are trimmed, blank rejected and long names truncated`() {
        assertNull(sanitizeGroupName("   "))
        assertNull(sanitizeGroupName("\n\t"))
        assertEquals("旅行", sanitizeGroupName("  旅行  "))
        assertEquals("ab", sanitizeGroupName("a\nb"))
        assertEquals(MAX_GROUP_NAME_LENGTH, sanitizeGroupName("x".repeat(MAX_GROUP_NAME_LENGTH + 20))!!.length)
    }

    @Test
    fun `expanded state toggles without mutating the original set`() {
        val id = Uuid.random()
        val empty = emptySet<Uuid>()

        val opened = toggleExpandedGroup(empty, id)
        assertTrue(empty.isEmpty())
        assertTrue(id in opened)

        val closed = toggleExpandedGroup(opened, id)
        assertFalse(id in closed)
    }

    @Test
    fun `expanded state survives a saved state round trip and drops junk`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val encoded = encodeExpandedGroups(listOf(a, b))

        assertEquals(setOf(a, b), decodeExpandedGroups(encoded))
        assertEquals(setOf(a), decodeExpandedGroups("$a,not-a-uuid,, "))
        assertEquals(emptySet<Uuid>(), decodeExpandedGroups(null))
        assertEquals(emptySet<Uuid>(), decodeExpandedGroups(""))
    }

    @Test
    fun `deleted groups drop out of the expanded set`() {
        val kept = Uuid.random()
        val removed = Uuid.random()

        // 删除组时 ViewModel 直接把 id 从展开集合里减掉，这里钉住减法本身的行为
        assertEquals(setOf(kept), setOf(kept, removed) - removed)
        assertFalse(toggleExpandedGroup(setOf(kept, removed), removed).contains(removed))
    }

    @Test
    fun `group list follows folders and defaults missing counts to zero`() {
        val withItems = folder(assistantA, "有会话")
        val empty = folder(assistantA, "空组")

        val groups = visibleGroups(
            folders = listOf(withItems, empty),
            counts = mapOf(withItems.id to 3),
            expanded = setOf(withItems.id),
        )

        assertEquals(listOf(withItems.id, empty.id), groups.map { it.id })
        assertEquals(3, groups[0].count)
        assertTrue(groups[0].expanded)
        assertEquals(0, groups[1].count)
        assertFalse(groups[1].expanded)
    }

    @Test
    fun `groups only show in the outermost drawer scope`() {
        assertTrue(shouldShowGroups(null, null))
        assertFalse(shouldShowGroups(Uuid.random(), null))
        assertFalse(shouldShowGroups(null, Uuid.random()))
        assertFalse(shouldShowGroups(Uuid.random(), Uuid.random()))
    }

    @Test
    fun `count badge and inline limit`() {
        assertFalse(groupCountVisible(0))
        assertFalse(groupCountVisible(1))
        assertTrue(groupCountVisible(2))
        assertFalse(groupHasMoreThanInline(GROUP_INLINE_LIMIT))
        assertTrue(groupHasMoreThanInline(GROUP_INLINE_LIMIT + 1))
    }

    @Test
    fun `selection toggle`() {
        val a = Uuid.random()

        assertEquals(setOf(a), toggleSelectedId(emptySet(), a))
        assertEquals(emptySet<Uuid>(), toggleSelectedId(setOf(a), a))
    }

    @Test
    fun `row tap only toggles selection in selection mode`() {
        assertTrue(rowTapTogglesSelection(true))
        assertFalse(rowTapTogglesSelection(false))
    }

    @Test
    fun `collapsing families is switched off while selecting so every member is reachable`() {
        // 折叠开着时，同族的其它成员藏在「+N」后面，多选时看不到也选不上
        assertFalse(effectiveCollapseFamilies(collapseFamilies = true, selectionMode = true))
        // 没进多选就照用户设置
        assertTrue(effectiveCollapseFamilies(collapseFamilies = true, selectionMode = false))
        assertFalse(effectiveCollapseFamilies(collapseFamilies = false, selectionMode = false))
        assertFalse(effectiveCollapseFamilies(collapseFamilies = false, selectionMode = true))
    }

    @Test
    fun `expanded state is stored per assistant so switching assistants does not leak`() {
        assertEquals(expandedGroupsKeyName(assistantA), expandedGroupsKeyName(assistantA))
        assertNotEquals(expandedGroupsKeyName(assistantA), expandedGroupsKeyName(assistantB))
        // 键名带上助手 id，重启后同一个助手读回同一格
        assertTrue(expandedGroupsKeyName(assistantA).contains(assistantA.toString()))
    }

    @Test
    fun `only successfully moved conversations leave the selection`() {
        val moved = Uuid.random()
        val failed = Uuid.random()
        val rejected = Uuid.random()
        val selected = setOf(moved, failed, rejected)

        val outcome = GroupMoveOutcome(
            moved = listOf(moved),
            failed = listOf(failed),
            rejected = listOf(rejected),
        )

        // 失败和跨助手被挡下的留在选中里，用户能重试；清空选中就等于假装成功
        assertEquals(setOf(failed, rejected), remainingSelection(selected, outcome))
        assertEquals(2, outcome.problemCount)
        assertFalse(outcome.isComplete)
        assertTrue(GroupMoveOutcome(listOf(moved), emptyList(), emptyList()).isComplete)
    }

    @Test
    fun `a fully rejected batch moves nothing and keeps the whole selection`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val outcome = GroupMoveOutcome(moved = emptyList(), failed = emptyList(), rejected = listOf(a, b))

        assertEquals(setOf(a, b), remainingSelection(setOf(a, b), outcome))
        assertFalse(outcome.isComplete)
    }
}
