package me.rerere.rikkahub.data.organization.grouping

import me.rerere.rikkahub.data.model.Folder
import kotlin.uuid.Uuid

/**
 * 手动分组的纯逻辑。
 *
 * 分组复用 r21 已有的「会话文件夹」（`conversation_folder` 表 + `conversationentity.folder_id`）：
 * 组只是归属列，**不改** 会话的消息、配置和 lineage。所以
 * - 组内每条会话仍然各自带着自己的上下文（不是合并消息，也不是 fork）；
 * - 移出组只是把 `folder_id` 清空，会话本体完全不动；
 * - 家族折叠（`collapseFamilies` 的 collapsed 查询）仍然按「同助手 + 同文件夹 + 同根」算，
 *   这里不碰 `lineage_id`，也不从 lineage 推出组成员。
 *
 * 放进这个文件的部分都是能脱离 Room / Compose 单测的判定，UI 和 ViewModel 只做接线。
 */

/** 单个分组的展开状态 + 条数，界面直接用。 */
data class GroupSummary(
    val id: Uuid,
    val name: String,
    val count: Int,
    val expanded: Boolean,
)

/**
 * 一次「多选归组」的划分结果：哪些真的搬、哪些因为不属于当前助手被挡下。
 * [rejected] 里的会话一条都不会被搬进组，跨助手禁止混组。
 */
data class GroupMovePlan(
    val accepted: List<Uuid>,
    val rejected: List<Uuid>,
)

/**
 * 只保留属于 [assistantId] 的选中项。
 *
 * 列表里只会出现当前助手的会话，这个函数是第二道闸：即使调用方传进来别的助手的 id（比如切换助手
 * 时残留的选中状态），也不会被搬进当前助手的组里。跨助手禁止混组。
 */
fun selectionWithinAssistant(
    selected: Collection<Uuid>,
    assistantId: Uuid,
    assistantOf: (Uuid) -> Uuid?,
): GroupMovePlan {
    val accepted = mutableListOf<Uuid>()
    val rejected = mutableListOf<Uuid>()
    selected.distinct().forEach { id ->
        if (assistantOf(id) == assistantId) accepted += id else rejected += id
    }
    return GroupMovePlan(accepted = accepted, rejected = rejected)
}

/**
 * 一批「移入/移出组」的结果。
 *
 * 存在的意义是不许「全程 runCatching 吞掉、然后无条件清空选中」：搬成功的才算搬了，
 * 失败和因跨助手被挡下的留在选中里，界面据此提示并让用户重试。
 */
data class GroupMoveOutcome(
    val moved: List<Uuid>,
    val failed: List<Uuid>,
    val rejected: List<Uuid>,
) {
    /** 出问题的条数（写入失败 + 不属于当前助手被挡下）。 */
    val problemCount: Int get() = failed.size + rejected.size

    /** 全部搬成功且没有漏网的。 */
    val isComplete: Boolean get() = problemCount == 0
}

/**
 * 操作结束后还要保持选中、等用户重试的项：只有真正搬走的才从选中里去掉。
 * 失败的那些留在选中里，用户能看到是哪几条没成，再点一次即可。
 */
fun remainingSelection(selected: Set<Uuid>, outcome: GroupMoveOutcome): Set<Uuid> =
    selected - outcome.moved.toSet()

/**
 * 目标组能不能收这批会话：组必须存在，且属于同一个助手。
 * 返回 false 时调用方一句都不搬（不做「搬一半」）。
 */
fun canAssignToGroup(folder: Folder?, assistantId: Uuid): Boolean =
    folder != null && folder.assistantId == assistantId

/**
 * 组名清洗：去掉首尾空白和换行，空名返回 null（不建无名组）。
 * 超过 [MAX_GROUP_NAME_LENGTH] 个字符截断，避免标题把抽屉撑开。
 */
fun sanitizeGroupName(raw: String): String? {
    val cleaned = raw.filterNot { it == '\n' || it == '\r' }.trim()
    if (cleaned.isEmpty()) return null
    return if (cleaned.length <= MAX_GROUP_NAME_LENGTH) cleaned else cleaned.take(MAX_GROUP_NAME_LENGTH)
}

const val MAX_GROUP_NAME_LENGTH = 40

/** 展开/收起的切换，返回新的集合（Compose 需要新实例才会重组）。 */
fun toggleExpandedGroup(expanded: Set<Uuid>, groupId: Uuid): Set<Uuid> =
    if (groupId in expanded) expanded - groupId else expanded + groupId

/** 组标题上的条数角标：组里只有一条时不显示数字，避免和单条会话行重复。 */
fun groupCountVisible(count: Int): Boolean = count > 1

/** 展开时最多先渲染多少条；超出的走「查看全部」进分页视图。 */
const val GROUP_INLINE_LIMIT = 60

/** 组内条数是否超过内联上限，超过时展开的组底部再给一个「查看全部」入口。 */
fun groupHasMoreThanInline(count: Int): Boolean = count > GROUP_INLINE_LIMIT

// ---- 展开状态的持久化（Preferences DataStore 里每个助手一个键） ----

/**
 * 某个助手「展开的组」在磁盘上的键名。
 *
 * 按助手分开存：切换助手时不会把上一个助手的展开状态带过来，也不会互相覆盖。
 * 单列出来是为了让单测能钉住键名，而不用起 Android/DataStore。
 */
fun expandedGroupsKeyName(assistantId: Uuid): String = "conversation_groups_expanded_$assistantId"

/** 把展开的组 id 编码成一行逗号分隔串；传空集合得到空串。 */
fun encodeExpandedGroups(ids: Collection<Uuid>): String = ids.joinToString(separator = ",")

/** 解析展开状态；坏片段（空串、非法 uuid）直接丢掉，不抛异常。 */
fun decodeExpandedGroups(raw: String?): Set<Uuid> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(',')
        .mapNotNull { part -> part.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Uuid.parse(it) }.getOrNull() } }
        .toSet()
}

/**
 * 列表实际用的家族折叠开关。
 *
 * 多选时必须把折叠关掉：家族折叠只显示一条代表行，其余同族会话藏在「+N」后面，
 * 用户在抽屉里根本点不到它们，也就没法把它们选中归组。多选期间展开成一行一条。
 */
fun effectiveCollapseFamilies(collapseFamilies: Boolean, selectionMode: Boolean): Boolean =
    collapseFamilies && !selectionMode

/**
 * 抽屉里要显示的组列表：只保留当前助手已有的组，按创建顺序排，带上条数和展开状态。
 * [counts] 里没有的组说明是空的（新建但还没归入会话），条数按 0 算。
 */
fun visibleGroups(
    folders: List<Folder>,
    counts: Map<Uuid, Int>,
    expanded: Set<Uuid>,
): List<GroupSummary> = folders.map { folder ->
    GroupSummary(
        id = folder.id,
        name = folder.name,
        count = counts[folder.id] ?: 0,
        expanded = folder.id in expanded,
    )
}

/**
 * 当前应该显示的组：只在最外层（未选组、未进家族列表）显示。
 * 进了某个组或某个家族后列表已经是「组内视角」，再叠一层组头会让人以为还能嵌套分组。
 */
fun shouldShowGroups(selectedGroupId: Uuid?, familyRootId: Uuid?): Boolean =
    selectedGroupId == null && familyRootId == null

/**
 * 多选模式下点一行是「选中/取消」，非多选模式点一行才是「打开会话」。
 * 抽出来是为了让 UI 的判断和单测一致（而不是散在 composable 里）。
 */
fun rowTapTogglesSelection(selectionMode: Boolean): Boolean = selectionMode

/** 选中集合的切换；返回新集合，保证不原地改。 */
fun toggleSelectedId(selected: Set<Uuid>, id: Uuid): Set<Uuid> =
    if (id in selected) selected - id else selected + id
