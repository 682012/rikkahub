package me.rerere.rikkahub.data.organization.autofile

import kotlin.uuid.Uuid

/**
 * 纯逻辑规划器：候选筛选 + 把模型的建议翻译成「哪些会话搬去哪个库」。
 *
 * 所有限制都在这里一次性收口，Controller 只负责照单执行：
 * - 候选必须是**同助手**（取候选时已按助手查询）、**未置顶**、**没有手动锁**、
 *   **当前不在任何文件夹里**（归库只处理「还没归类」的会话，不把用户已经整理好的搬来搬去）、
 *   **闲置够久**（`now - updateAt >= idleHours`，最低 24 小时）、有标题；
 * - 同一家族（`lineageId ?: id`）只挑**最早创建**的那条做模型输入的代表：同标题不算同族，不看标题；
 *   代表被采纳之后，家族里其余**仍合格**的兄弟由 [expandFamilyMembers] 一起跟随，
 *   这样不会每轮只搬一个、把同族散卡留在未归类里——模型输入仍然是 ≤16 条代表；
 * - 单批最多 16 条模型输入、最多 [MAX_MOVES_PER_RUN] 条实际搬迁；置信度低于 0.85 的一律不动；
 * - 已有库优先：名字和现有文件夹对得上就归进去，忽略模型给的 isNew；
 * - 新建库要求这一库里至少 3 个**不同家族**（兄弟数量凑不出来），且这批结束后助手内的文件夹总数
 *   不超过 12 个。
 */
object ConversationAutoFilePlanner {
    const val MIN_CONFIDENCE = 0.85

    /** 一次模型调用的输入条数上限（每条一个家族代表）。 */
    const val MAX_ITEMS_PER_RUN = 16

    /** 一批实际搬迁的条数上限：家族兄弟跟随进来以后也不会无限放大。 */
    const val MAX_MOVES_PER_RUN = 200

    const val MIN_FAMILIES_PER_NEW_FOLDER = 3
    const val MAX_FOLDERS_TOTAL = 12

    /**
     * 挑出本次可以参与整理的会话代表（顺序稳定：最早的在前），单批最多 [MAX_ITEMS_PER_RUN] 条。
     */
    fun eligible(
        candidates: List<AutoFileCandidate>,
        settings: ConversationAutoFileSettings,
        now: Long,
    ): List<AutoFileCandidate> =
        representatives(usable(candidates, settings, now))

    /**
     * 把「家族代表被采纳」扩成「这一族所有仍合格的兄弟一起搬」。
     *
     * [candidates] 是原始候选（可以包含不合适/已归库的），这里再按与 [eligible] 完全一致的门槛过滤一次；
     * 只有代表所属家族会被扩，其他家族即使合格也不进这批（它们没有模型的建议）。
     * 结果条数不超过 [maxMoves]，代表始终排在前面，不受上限影响。
     */
    fun expandFamilyMembers(
        representatives: List<AutoFileCandidate>,
        candidates: List<AutoFileCandidate>,
        settings: ConversationAutoFileSettings,
        now: Long,
        maxMoves: Int = MAX_MOVES_PER_RUN,
    ): List<AutoFileCandidate> {
        if (representatives.isEmpty()) return emptyList()
        val representativeIds = representatives.mapTo(HashSet()) { it.id }
        val families = representatives.mapTo(HashSet()) { it.familyKey }
        val siblings = usable(candidates, settings, now)
            .filter { it.familyKey in families && it.id !in representativeIds }
        val budget = (maxMoves.coerceAtLeast(representatives.size) - representatives.size)
        return representatives + siblings.take(budget)
    }

    /**
     * 把模型给代表的建议铺到同一个家族的所有成员上（同一目标库、同一置信度）。
     *
     * 没有建议的代表/未知 id 原样留着，交给 [plan] 去判定；重复 id 只留第一条。
     */
    fun expandAssignments(
        parsed: AutoFileParsedClassification,
        representatives: List<AutoFileCandidate>,
        members: List<AutoFileCandidate>,
    ): AutoFileParsedClassification {
        if (parsed.assignments.isEmpty() || members.isEmpty()) return parsed
        val representativeById = LinkedHashMap<Uuid, AutoFileCandidate>()
        representatives.forEach { representative ->
            if (!representativeById.containsKey(representative.id)) representativeById[representative.id] = representative
        }
        val membersByFamily = members.groupBy { it.familyKey }

        val seen = HashSet<Uuid>()
        val assignments = ArrayList<AutoFileAssignment>(members.size)
        parsed.assignments.forEach { assignment ->
            val representative = representativeById[assignment.conversationId]
            val family = if (representative == null) null else membersByFamily[representative.familyKey]
            if (family == null) {
                if (seen.add(assignment.conversationId)) assignments += assignment
                return@forEach
            }
            family.forEach { member ->
                if (seen.add(member.id)) {
                    assignments += assignment.copy(conversationId = member.id)
                }
            }
        }
        return parsed.copy(assignments = assignments)
    }

    /**
     * 把解析后的建议落成计划。[folders] 是助手当前的全部文件夹（用于「已有库优先」和总数上限）。
     */
    fun plan(
        candidates: List<AutoFileCandidate>,
        folders: List<AutoFileFolderRef>,
        parsed: AutoFileParsedClassification,
    ): AutoFilePlan {
        if (parsed.error != null) return AutoFilePlan()
        val candidateById = LinkedHashMap<Uuid, AutoFileCandidate>()
        candidates.forEach { candidate ->
            if (!candidateById.containsKey(candidate.id)) candidateById[candidate.id] = candidate
        }

        val folderByName = LinkedHashMap<String, AutoFileFolderRef>()
        folders.forEach { folder ->
            val name = ConversationAutoFileParser.cleanName(folder.name) ?: return@forEach
            val key = name.lowercase()
            if (!folderByName.containsKey(key)) folderByName[key] = folder
        }

        val rejected = ArrayList<AutoFileRejection>()
        val accepted = LinkedHashMap<Uuid, AutoFileAssignment>()
        parsed.assignments.forEach { assignment ->
            if (accepted.containsKey(assignment.conversationId)) return@forEach
            val candidate = candidateById[assignment.conversationId]
            if (candidate == null) {
                rejected += AutoFileRejection(assignment.conversationId, "不在本次候选里")
                return@forEach
            }
            if (!assignment.confidence.isFinite() || assignment.confidence < MIN_CONFIDENCE) {
                rejected += AutoFileRejection(assignment.conversationId, "置信度不够")
                return@forEach
            }
            val name = ConversationAutoFileParser.cleanName(assignment.folderName)
            if (name == null) {
                rejected += AutoFileRejection(assignment.conversationId, "库名为空")
                return@forEach
            }
            accepted[assignment.conversationId] = assignment.copy(folderName = name)
        }
        if (accepted.isEmpty()) return AutoFilePlan(rejected = rejected)

        // 按「已有库 / 新库」分组。已有库用现有 id，新库用折叠后的名字当 key。
        val groups = LinkedHashMap<String, AutoFileGroup>()
        accepted.values.forEach { assignment ->
            val existing = folderByName[assignment.folderName.lowercase()]
            val key = existing?.id?.toString() ?: "new:${assignment.folderName.lowercase()}"
            val group = groups.getOrPut(key) {
                AutoFileGroup(
                    key = key,
                    name = existing?.name?.let { ConversationAutoFileParser.cleanName(it) } ?: assignment.folderName,
                    existingFolderId = existing?.id,
                )
            }
            candidateById[assignment.conversationId]?.let { group.members += it }
        }

        val existingGroups = groups.values
            .filter { it.existingFolderId != null && it.members.isNotEmpty() }
            .sortedByDescending { it.members.size }
        val newGroups = groups.values
            .filter { it.existingFolderId == null && it.members.isNotEmpty() }
            .sortedByDescending { it.members.size }

        val moves = ArrayList<AutoFilePlannedMove>()
        val newFolders = ArrayList<String>()

        existingGroups.forEach { group ->
            group.members.sortedBy { it.createAt }.forEach { candidate ->
                moves += AutoFilePlannedMove(candidate.id, group.name, group.existingFolderId)
            }
        }

        // 现有文件夹已经到顶时，新库一律不建（不牺牲已有库的归位）。
        val existingFolderKeys = folderByName.keys
        var totalFolders = existingFolderKeys.size
        newGroups.forEach { group ->
            val families = group.members.map { it.familyKey }.toSet()
            if (families.size < MIN_FAMILIES_PER_NEW_FOLDER) {
                group.members.forEach { candidate ->
                    rejected += AutoFileRejection(candidate.id, "新库至少要 ${MIN_FAMILIES_PER_NEW_FOLDER} 个不同话题")
                }
                return@forEach
            }
            if (totalFolders >= MAX_FOLDERS_TOTAL) {
                group.members.forEach { candidate ->
                    rejected += AutoFileRejection(candidate.id, "文件夹总数超过 ${MAX_FOLDERS_TOTAL}")
                }
                return@forEach
            }
            totalFolders += 1
            newFolders += group.name
            group.members.sortedBy { it.createAt }.forEach { candidate ->
                moves += AutoFilePlannedMove(candidate.id, group.name, null)
            }
        }

        val seenMoves = HashSet<Uuid>()
        val dedupedMoves = moves.filter { seenMoves.add(it.conversationId) }
        return AutoFilePlan(moves = dedupedMoves, newFolders = newFolders, rejected = rejected)
    }

    /** 与 [eligible] 相同的门槛：未置顶、未加锁、不在任何文件夹、有标题、闲置够久。 */
    private fun usable(
        candidates: List<AutoFileCandidate>,
        settings: ConversationAutoFileSettings,
        now: Long,
    ): List<AutoFileCandidate> {
        val idleMillis = settings.idleMillis()
        return candidates
            .filter { candidate ->
                !candidate.pinned &&
                    !candidate.locked &&
                    candidate.folderId == null &&
                    candidate.title.isNotBlank() &&
                    now - candidate.updateAt >= idleMillis
            }
            .sortedWith(compareBy({ it.createAt }, { it.id.toString() }))
    }

    /** 同一家族只留最早创建的一条做代表；族内其余成员由 [expandFamilyMembers] 跟随。 */
    private fun representatives(usable: List<AutoFileCandidate>): List<AutoFileCandidate> {
        val byFamily = LinkedHashMap<Uuid, AutoFileCandidate>()
        usable.forEach { candidate ->
            if (!byFamily.containsKey(candidate.familyKey)) byFamily[candidate.familyKey] = candidate
        }
        return byFamily.values.take(MAX_ITEMS_PER_RUN)
    }
}

/** 规划过程中的分组：同一个目标库（已有库或待建库）下的一批候选。 */
private class AutoFileGroup(
    val key: String,
    val name: String,
    val existingFolderId: Uuid?,
    val members: MutableList<AutoFileCandidate> = ArrayList(),
)
