package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import me.rerere.rikkahub.data.model.Conversation

/**
 * 会话列表（抽屉）里那些「错了就会闪空/串区/假加载」的判定。
 *
 * 全部是纯函数，不碰 Android、不碰 Compose 运行时，单测可以直接钉住；
 * 之前这些判断散在 Composable 里，一处写反就是用户看到的那几个 bug，而且没法回归。
 *
 * ── 接线现状（按 m3 只读复核 + 逐行验证，2026-11）─────────────
 * 以下函数在 UI 层**无直接调用**，但其表达的功能由等效机制覆盖：
 *
 * - `visibleListRows` / `groupMemberIdsToHide`：
 *   未归组查询（`getUnfiledConversationsOfAssistantPaging`）使用 `folder_id = ''` 过滤，
 *   分组内的成员不会出现在主列表中，因此不需要在主列表内「让位」。等效逻辑在 DAO 层。
 *
 * - `listRowForCurrentConversation` / `pagingHasConversation`：
 *   当前会话行自然出现在分页查询结果中（未归组家族根）。若当前会话有 folder_id（在组内），
 *   则不应在未归组列表中补行，否则会把组内成员串出组外。等效表现为「不补行就是正确行为」。
 *
 * - `pagingListLoadingState` / `pagingListHasRealRows`：
 *   Compose 内联使用 `loadState.refresh is NotLoading` 判断空态，不以分页刷新作为整表加载覆盖层。
 *   等效逻辑在 ConversationList.kt 渲染中；实际滚动与刷新观感仍需真机检查。
 *
 * - `chatContentLoading`：
 *   聊天内容加载态（非抽屉列表）由 service.stability.chatShowsContentLoading 控制，
 *   该函数仅作为一层包装引用，UI 调用点在 ChatPage 侧，不在本文件接线范围内。
 *
 * - `conversationRowRunning`：
 *   ConversationItem 内联使用 `conversation.id in conversationJobs`，
 *   而 conversationJobs 仅包含有活跃生成 Job 的会话，与 `jobActive` 等价。
 *
 * 保留这些函数作为参考定义与单测锚点：若未来界面逻辑需要变化，在此修正并补上 UI 调用即可。
 */


// ---- 1. 列表与当前会话的对应关系 ----

/**
 * 抽屉列表里可以为 [current] 补一条「正在打开的这条会话」。
 *
 * 只在该列表本来就该包含它时补：同一个助手、且没有被筛进某个组/某个家族。
 * 跨助手补行会把别的助手的会话塞进当前助手的列表（就是「在 A 助手的其它聊天区开的会话串到主会话区」）。
 * 当前列表已经自带这一行时，调用方不该再补（会变成两行）。
 *
 * ⚠ 当前 m3 UI 未调用此函数，因查询层 `folder_id = ''` filter 已保证：当前会话是未归组家族根
 *   时自然出现在列表中；有 folder_id（在组内）时不应出现，不补行就是正确行为。
 */
fun listRowForCurrentConversation(
    current: Conversation,
    assistantId: kotlin.uuid.Uuid?,
    selectedFolderId: kotlin.uuid.Uuid?,
    selectedLineageId: kotlin.uuid.Uuid?,
    alreadyListed: Boolean,
): Conversation? {
    if (alreadyListed) return null
    if (assistantId == null || current.assistantId != assistantId) return null
    if (selectedLineageId != null) return null
    if (selectedFolderId != null && current.folderId != selectedFolderId) return null
    return current
}

// ---- 2. 列表的真实加载状态 ----

/**
 * 抽屉列表这次渲染能不能说「加载中」。
 *
 * - `refresh is Loading` 且一条都没有，才是真正的首次加载 / 重新加载，这时说加载中不会骗人；
 * - 已经靠 `prepend`/`append` 拿到了新一页（Paging 在 invalidate 之后常常只走这两条），
 *   列表内容已经是真的，不能因为 `mediator` 挂着或 refresh 未完成就整片显示「加载中」。
 */
fun pagingListLoadingState(refresh: LoadState, hasRows: Boolean): Boolean =
    refresh is LoadState.Loading && !hasRows

/** 列表已经能给出真实内容（包括「查完了确实没有」）。 */
fun pagingListHasRealRows(refresh: LoadState, hasRows: Boolean): Boolean =
    hasRows || refresh is LoadState.NotLoading

// ---- 3. 滚动定位 ----

/**
 * 打开某个会话时该滚到哪一项；返回 null 表示这一帧没有可定位的行（列表还没来）。
 *
 * 以前用「当前会话那一行不在首屏就 scrollToItem」做定位，每次切换会话、每次分页刷新都硬拽
 * 一次列表，用户看到的就是「点一下卡一下、还闪」。现在只在行确实存在且不可见时给目标，
 * 而且调用方按键变化只触发一次。
 */
fun scrollTargetForKey(
    items: List<ConversationListItem>,
    headerCount: Int,
    conversationId: kotlin.uuid.Uuid,
    listState: LazyListState,
): Int? {
    val itemIndex = items.indexOfFirst { (it as? ConversationListItem.Item)?.conversation?.id == conversationId }
    if (itemIndex < 0) return null
    val target = itemIndex + headerCount
    val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == target }
    return if (visible) null else target
}

/** 分页列表里已经能看到的会话 id 判断。 */
fun pagingHasConversation(items: LazyPagingItems<ConversationListItem>, id: kotlin.uuid.Uuid): Boolean {
    for (index in 0 until items.itemCount) {
        val item = items.peek(index) as? ConversationListItem.Item ?: continue
        if (item.conversation.id == id) return true
    }
    return false
}

// ---- 4. 分组 = 原列表里的折叠，不是另一个区 ----

/**
 * 组头应该插在哪一行前面。
 *
 * 组是「原列表里把这几条折成一组」，不是另一个区：所以组头位置由组里成员在真实排序里
 * 最靠前的那一条决定（置顶的在前、再按排序时间），而不是全部堆到列表顶部另开一区。
 */
fun groupAnchorItem(
    members: List<Conversation>,
    rows: List<ConversationListItem>,
    sortKeyOf: (Conversation) -> Long,
): ConversationListItem.Item? {
    if (members.isEmpty()) return null
    val memberIds = members.mapTo(HashSet()) { it.id }
    // 列表本身就是排好序的：第一个出现的组内成员就是这组的锚点
    rows.forEach { row ->
        val item = row as? ConversationListItem.Item ?: return@forEach
        if (item.conversation.id in memberIds) return item
    }
    // 组里的会话都不在首页（还没翻到）：用排序键挑一条，放在它该在的位置前面
    val best = members.maxWithOrNull(compareBy({ it.isPinned }, { sortKeyOf(it) })) ?: return null
    return ConversationListItem.Item(best)
}

/** 组展开后，主列表里要让位（不再重复显示）的成员 id。 */
fun groupMemberIdsToHide(members: List<Conversation>): Set<kotlin.uuid.Uuid> =
    members.mapTo(LinkedHashSet()) { it.id }

/**
 * 主列表里哪些行还要不要画。
 *
 * [hiddenIds] 是被组内那一份接管的行（组展开时才有），其余行保持原样、位置不动——
 * 这正是「在原列表折成一个可展开会话组」和「被挪到另外一个区」的区别。
 */
fun visibleListRows(
    rows: List<ConversationListItem>,
    hiddenIds: Set<kotlin.uuid.Uuid>,
): List<ConversationListItem> =
    if (hiddenIds.isEmpty()) rows
    else rows.filterNot { (it as? ConversationListItem.Item)?.conversation?.id in hiddenIds }

// ---- 5. 「已完成的会话再点却一直加载」 ----

/**
 * 页面该不该显示「内容加载中」。
 *
 * 只看两件真实的事：会话内容是否就绪、当前有没有生成任务。曾经把 `processingStatus != null`
 * 也算进加载条件，而处理状态在会话结束后并不总是清空，于是「已经完成的会话再点进去一直转圈」。
 */
fun chatContentLoading(contentReady: Boolean, generationActive: Boolean, visibleMessageCount: Int): Boolean =
    // 有可见消息时绝不用加载态顶掉它们（这正是「已完成的会话再点进去一直转圈」）：
    // 判定交给 service/stability 里那一份已被单测钉住的规则，不在这里另立一套。
    me.rerere.rikkahub.service.stability.chatShowsContentLoading(contentReady, visibleMessageCount) ||
        (generationActive && visibleMessageCount == 0)

/**
 * 就地折叠的组头落点：`index -> 要画在这一行**上面**的组头`。
 *
 * index 等于行数表示画在列表最末尾。组内会话本来就不在「未归类」列表里（它们有 folder_id），
 * 所以落点只能按组里最靠前那条会话的排序键（置顶 > 更新时间/创建时间降序）算，
 * 而不是把组头统一堆到列表顶部另开一个区——那正是用户说的「别分区」。
 */
fun inlineGroupPlacement(
    rows: List<ConversationListItem>,
    headers: List<ConversationListItem.GroupHeader>,
    sortKeyOf: (Conversation) -> Long = { it.updateAt.toEpochMilli() },
): Map<Int, List<ConversationListItem.GroupHeader>> {
    if (headers.isEmpty()) return emptyMap()
    val items = rows.mapIndexedNotNull { index, row ->
        (row as? ConversationListItem.Item)?.let { index to it }
    }
    val result = LinkedHashMap<Int, MutableList<ConversationListItem.GroupHeader>>()
    headers
        .sortedWith(compareByDescending<ConversationListItem.GroupHeader> { it.pinned }.thenByDescending { it.anchorAt })
        .forEach { header ->
            val index = items.firstOrNull { (_, row) ->
                val pinnedRow = row.conversation.isPinned
                if (pinnedRow != header.pinned) !pinnedRow && header.pinned
                else sortKeyOf(row.conversation) < header.anchorAt
            }?.first ?: rows.size
            result.getOrPut(index) { mutableListOf() } += header
        }
    return result
}

/** 抽屉里某条会话的「运行中」圆点：只认真正的生成任务，不认处理状态、不认排队。 */
fun conversationRowRunning(jobActive: Boolean): Boolean = jobActive
