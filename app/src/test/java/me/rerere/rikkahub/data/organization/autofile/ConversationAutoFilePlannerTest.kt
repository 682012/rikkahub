package me.rerere.rikkahub.data.organization.autofile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationAutoFilePlannerTest {

    private val now = 10_000_000_000L
    private val hour = 60L * 60L * 1000L

    private fun candidate(
        title: String = "会话",
        updateAt: Long = now - 48 * hour,
        createAt: Long = now - 72 * hour,
        folderId: Uuid? = null,
        pinned: Boolean = false,
        locked: Boolean = false,
        familyKey: Uuid = Uuid.random(),
        id: Uuid = Uuid.random(),
    ) = AutoFileCandidate(
        id = id,
        title = title,
        createAt = createAt,
        updateAt = updateAt,
        folderId = folderId,
        pinned = pinned,
        locked = locked,
        familyKey = familyKey,
    )

    @Test
    fun `eligible drops pinned locked filed and fresh conversations`() {
        val settings = ConversationAutoFileSettings(enabled = true, idleHours = 24)
        val keep = candidate(title = "可整理")
        val result = ConversationAutoFilePlanner.eligible(
            listOf(
                keep,
                candidate(title = "置顶", pinned = true),
                candidate(title = "锁了", locked = true),
                candidate(title = "已在库里", folderId = Uuid.random()),
                candidate(title = "刚聊过", updateAt = now - 23 * hour),
                candidate(title = ""),
            ),
            settings,
            now,
        )

        assertEquals(listOf(keep.id), result.map { it.id })
    }

    @Test
    fun `eligible keeps earliest member of a family and caps at 16`() {
        val settings = ConversationAutoFileSettings(enabled = true, idleHours = 24)
        val family = Uuid.random()
        val older = candidate(title = "同族早", createAt = now - 100 * hour, familyKey = family)
        val newer = candidate(title = "同族晚", createAt = now - 10 * hour, familyKey = family)
        val others = (1..20).map { index ->
            candidate(title = "会话$index", createAt = now - index * hour)
        }

        val result = ConversationAutoFilePlanner.eligible(listOf(older, newer) + others, settings, now)

        assertEquals(ConversationAutoFilePlanner.MAX_ITEMS_PER_RUN, result.size)
        assertTrue(result.any { it.id == older.id })
        assertTrue(result.none { it.id == newer.id })
        // 最早的在前
        assertEquals(result.map { it.createAt }, result.map { it.createAt }.sorted())
    }

    @Test
    fun `plan rejects unknown id duplicate id and low confidence`() {
        val candidate = candidate()
        val lowConfidence = candidate()
        val duplicated = candidate()
        val parsed = AutoFileParsedClassification(
            assignments = listOf(
                AutoFileAssignment(Uuid.random(), "旅行", false, 0.99),
                AutoFileAssignment(lowConfidence.id, "旅行", false, 0.5),
                AutoFileAssignment(duplicated.id, "旅行", false, 0.9),
                AutoFileAssignment(duplicated.id, "工作", false, 0.9),
                AutoFileAssignment(candidate.id, "旅行", false, 0.9),
            ),
        )

        val plan = ConversationAutoFilePlanner.plan(
            candidates = listOf(candidate, lowConfidence, duplicated),
            folders = emptyList(),
            parsed = parsed,
        )

        // 新库只有 1 个家族 → 不建
        assertTrue(plan.moves.isEmpty())
        assertTrue(plan.newFolders.isEmpty())
    }

    @Test
    fun `plan prefers existing folder and ignores isNew`() {
        val folder = AutoFileFolderRef(Uuid.random(), "工作")
        val members = (1..3).map { candidate() }
        val parsed = AutoFileParsedClassification(
            assignments = members.map { AutoFileAssignment(it.id, " 工作 ", true, 0.91) },
        )

        val plan = ConversationAutoFilePlanner.plan(members, listOf(folder), parsed)

        assertEquals(0, plan.newFolders.size)
        assertEquals(3, plan.moves.size)
        assertTrue(plan.moves.all { it.existingFolderId == folder.id })
    }

    @Test
    fun `plan creates new folder only with three distinct families`() {
        val twoFamilies = (1..2).map { candidate() }
        val family = Uuid.random()
        val sameFamily = listOf(
            candidate(familyKey = family, createAt = now - 100 * hour),
            candidate(familyKey = family, createAt = now - 90 * hour),
            candidate(familyKey = family, createAt = now - 80 * hour),
        )

        val rejectedPlan = ConversationAutoFilePlanner.plan(
            candidates = twoFamilies,
            folders = emptyList(),
            parsed = AutoFileParsedClassification(
                assignments = twoFamilies.map { AutoFileAssignment(it.id, "旅行", true, 0.95) },
            ),
        )
        assertTrue(rejectedPlan.moves.isEmpty())

        val acceptedPlan = ConversationAutoFilePlanner.plan(
            candidates = sameFamily,
            folders = emptyList(),
            parsed = AutoFileParsedClassification(
                assignments = sameFamily.map { AutoFileAssignment(it.id, "旅行", true, 0.95) },
            ),
        )
        assertTrue(acceptedPlan.moves.isEmpty())

        val threeFamilies = (1..3).map { candidate() }
        val okPlan = ConversationAutoFilePlanner.plan(
            candidates = threeFamilies,
            folders = emptyList(),
            parsed = AutoFileParsedClassification(
                assignments = threeFamilies.map { AutoFileAssignment(it.id, "旅行", true, 0.95) },
            ),
        )
        assertEquals(listOf("旅行"), okPlan.newFolders)
        assertEquals(3, okPlan.moves.size)
        assertNull(okPlan.moves.first().existingFolderId)
    }

    @Test
    fun `plan never exceeds twelve folders in total`() {
        val existing = (1..12).map { AutoFileFolderRef(Uuid.random(), "库$it") }
        val members = (1..3).map { candidate() }
        val parsed = AutoFileParsedClassification(
            assignments = members.map { AutoFileAssignment(it.id, "新库", true, 0.95) },
        )

        val plan = ConversationAutoFilePlanner.plan(members, existing, parsed)

        assertTrue(plan.newFolders.isEmpty())
        assertTrue(plan.moves.isEmpty())
        assertEquals(3, plan.rejected.size)
    }

    @Test
    fun `plan mixes existing and new folders and keeps each move once`() {
        val existing = listOf(AutoFileFolderRef(Uuid.random(), "工作"))
        val workMembers = (1..2).map { candidate() }
        val travelMembers = (1..3).map { candidate() }
        val parsed = AutoFileParsedClassification(
            assignments = workMembers.map { AutoFileAssignment(it.id, "工作", false, 0.9) } +
                travelMembers.map { AutoFileAssignment(it.id, "旅行", true, 0.9) },
        )

        val plan = ConversationAutoFilePlanner.plan(workMembers + travelMembers, existing, parsed)

        assertEquals(listOf("旅行"), plan.newFolders)
        assertEquals(5, plan.moves.size)
        assertEquals(5, plan.moves.map { it.conversationId }.distinct().size)
    }

    @Test
    fun `plan returns nothing when parse failed`() {
        val members = (1..3).map { candidate() }
        val plan = ConversationAutoFilePlanner.plan(
            members,
            emptyList(),
            AutoFileParsedClassification(assignments = emptyList(), error = "格式不正确"),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `family expansion follows only adopted families and keeps the rep first`() {
        val settings = ConversationAutoFileSettings(enabled = true, idleHours = 24)
        val family = Uuid.random()
        val representative = candidate(title = "同族代表", createAt = now - 100 * hour, familyKey = family)
        val sibling = candidate(title = "同族兄弟", createAt = now - 50 * hour, familyKey = family)
        val otherFamily = candidate(title = "别的话题", createAt = now - 90 * hour)
        val lonely = candidate(title = "没被采纳", createAt = now - 40 * hour)
        val all = listOf(representative, sibling, otherFamily, lonely)

        val representatives = ConversationAutoFilePlanner.eligible(all, settings, now)
        assertEquals(
            setOf(representative.id, otherFamily.id, lonely.id),
            representatives.map { it.id }.toSet(),
        )

        val members = ConversationAutoFilePlanner.expandFamilyMembers(representatives, all, settings, now)

        assertEquals(
            setOf(representative.id, sibling.id, otherFamily.id, lonely.id),
            members.map { it.id }.toSet(),
        )
        // 代表始终排在最前，兄弟接在后面。
        assertEquals(representative.id, members.first().id)
        assertEquals(sibling.id, members.last().id)
    }

    @Test
    fun `family expansion drops siblings that are no longer eligible`() {
        val settings = ConversationAutoFileSettings(enabled = true, idleHours = 24)
        val family = Uuid.random()
        val representative = candidate(createAt = now - 100 * hour, familyKey = family)
        val usable = candidate(createAt = now - 50 * hour, familyKey = family)
        val pinned = candidate(createAt = now - 49 * hour, familyKey = family, pinned = true)
        val locked = candidate(createAt = now - 48 * hour, familyKey = family, locked = true)
        val filed = candidate(createAt = now - 47 * hour, familyKey = family, folderId = Uuid.random())
        val fresh = candidate(createAt = now - 46 * hour, updateAt = now - 2 * hour, familyKey = family)
        val untitled = candidate(createAt = now - 45 * hour, familyKey = family, title = "")
        val all = listOf(representative, usable, pinned, locked, filed, fresh, untitled)

        val representatives = ConversationAutoFilePlanner.eligible(all, settings, now)
        val members = ConversationAutoFilePlanner.expandFamilyMembers(representatives, all, settings, now)

        assertEquals(listOf(representative.id, usable.id), members.map { it.id })
    }

    @Test
    fun `family expansion never exceeds the per batch move cap`() {
        val settings = ConversationAutoFileSettings(enabled = true, idleHours = 24)
        val family = Uuid.random()
        val representative = candidate(createAt = now - 1_000 * hour, familyKey = family)
        val siblings = (1..300).map { index ->
            candidate(createAt = now - (500 - index) * hour, familyKey = family)
        }
        val all = listOf(representative) + siblings
        val representatives = ConversationAutoFilePlanner.eligible(all, settings, now)

        val members = ConversationAutoFilePlanner.expandFamilyMembers(representatives, all, settings, now)

        assertEquals(ConversationAutoFilePlanner.MAX_MOVES_PER_RUN, members.size)
        assertEquals(
            4,
            ConversationAutoFilePlanner.expandFamilyMembers(representatives, all, settings, now, maxMoves = 4).size,
        )
        assertTrue(members.all { it.familyKey == family })
    }

    @Test
    fun `expanded assignments copy the representative advice to every sibling`() {
        val family = Uuid.random()
        val representative = candidate(familyKey = family)
        val siblingA = candidate(familyKey = family)
        val siblingB = candidate(familyKey = family)
        val unknown = Uuid.random()
        val parsed = AutoFileParsedClassification(
            assignments = listOf(
                AutoFileAssignment(representative.id, "旅行", true, 0.95),
                AutoFileAssignment(unknown, "工作", false, 0.93),
            ),
        )

        val expanded = ConversationAutoFilePlanner.expandAssignments(
            parsed = parsed,
            representatives = listOf(representative),
            members = listOf(representative, siblingA, siblingB),
        )

        assertEquals(
            setOf(representative.id, siblingA.id, siblingB.id, unknown),
            expanded.assignments.map { it.conversationId }.toSet(),
        )
        val travel = expanded.assignments.filter { it.conversationId != unknown }
        assertTrue(travel.all { it.folderName == "旅行" && it.isNew && it.confidence == 0.95 })
        // 未知 id 原样保留，交给 plan 去拒。
        assertEquals("工作", expanded.assignments.last().folderName)
    }

    @Test
    fun `siblings can never fake the three family rule`() {
        val familyA = Uuid.random()
        val familyB = Uuid.random()
        val a1 = candidate(createAt = now - 100 * hour, familyKey = familyA)
        val a2 = candidate(createAt = now - 90 * hour, familyKey = familyA)
        val a3 = candidate(createAt = now - 80 * hour, familyKey = familyA)
        val b1 = candidate(createAt = now - 70 * hour, familyKey = familyB)
        val settings = ConversationAutoFileSettings(enabled = true, idleHours = 24)
        val all = listOf(a1, a2, a3, b1)

        val representatives = ConversationAutoFilePlanner.eligible(all, settings, now)
        val members = ConversationAutoFilePlanner.expandFamilyMembers(representatives, all, settings, now)
        val expanded = ConversationAutoFilePlanner.expandAssignments(
            parsed = AutoFileParsedClassification(
                assignments = representatives.map { AutoFileAssignment(it.id, "旅行", true, 0.95) },
            ),
            representatives = representatives,
            members = members,
        )
        val plan = ConversationAutoFilePlanner.plan(members, emptyList(), expanded)

        // 4 个会话但只有 2 个家族：兄弟再多也不建新库。
        assertEquals(4, members.size)
        assertTrue(plan.moves.isEmpty())
        assertTrue(plan.newFolders.isEmpty())
        assertEquals(4, plan.rejected.size)
    }
}
