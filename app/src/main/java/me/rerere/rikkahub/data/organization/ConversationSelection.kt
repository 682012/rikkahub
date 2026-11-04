package me.rerere.rikkahub.data.organization

import me.rerere.rikkahub.data.model.Conversation
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 会话整理（批量删除）的纯逻辑：现在看哪些会话、批量选择选了谁、最后要删谁。
 *
 * 不碰 Android、不碰数据库，也不决定删不删——删除永远是用户点确认后逐条走
 * `ChatService.deleteConversation`。这里只把「筛什么 / 选了什么」算清楚，方便单测。
 */

/** 助手范围。 */
enum class ConversationScope {
    CURRENT_ASSISTANT,
    ALL_ASSISTANTS,
}

/**
 * 时间筛选看的是「多久没聊过」，不是「多久前建的」。
 *
 * [days] 为空表示不筛；否则只留下 `updateAt` 早于 `now - days` 的会话。
 * 正好卡在边界上的算「刚用过」，不选——批量删掉刚聊过的比漏删几个代价大得多。
 */
enum class ConversationAgeFilter(val days: Long?) {
    ALL(null),
    UNUSED_7_DAYS(7),
    UNUSED_30_DAYS(30),
    UNUSED_90_DAYS(90),
    ;

    fun cutoff(now: Instant): Instant? = days?.let { now.minus(Duration.ofDays(it)) }

    fun matches(updateAt: Instant, now: Instant): Boolean {
        val cutoff = cutoff(now) ?: return true
        return updateAt.isBefore(cutoff)
    }
}

/** 搜索词 + 时间 + 助手范围，三者叠加。 */
data class ConversationFilterState(
    val query: String = "",
    val age: ConversationAgeFilter = ConversationAgeFilter.ALL,
    val scope: ConversationScope = ConversationScope.CURRENT_ASSISTANT,
) {
    val isFiltered: Boolean
        get() = query.isNotBlank() || age != ConversationAgeFilter.ALL
}

/**
 * 标题搜索 + 时间筛选。搜索词去首尾空白、忽略大小写，只匹配标题（和 repo 的标题查询一致）。
 * 顺序沿用传入顺序（DAO 已经是置顶优先、按更新时间倒序）。
 */
fun filterConversations(
    conversations: List<Conversation>,
    filter: ConversationFilterState,
    now: Instant,
): List<Conversation> {
    val keyword = filter.query.trim()
    return conversations.filter { conversation ->
        (keyword.isEmpty() || conversation.title.contains(keyword, ignoreCase = true)) &&
            filter.age.matches(conversation.updateAt, now)
    }
}

/**
 * 批量选择的集合运算。
 *
 * 集合里只会留下「当前可见」的会话 id：换助手、换筛选、删完之后都要裁一次，
 * 不然确认框里的数量会把看不见的会话也算进去，那才是最危险的那种误删。
 */
object ConversationSelection {
    fun toggle(selected: Set<Uuid>, id: Uuid): Set<Uuid> =
        if (id in selected) selected - id else selected + id

    /**
     * 全选当前筛选结果，默认跳过置顶；已经手动点上的置顶保留。
     * 置顶要精确地一个个手选，不能被「全选」顺手带进来。
     */
    fun selectAllVisible(selected: Set<Uuid>, visible: List<Conversation>): Set<Uuid> {
        if (visible.isEmpty()) return selected
        val addable = visible.filterNot { it.isPinned }.map { it.id }
        return if (addable.isEmpty()) selected else selected + addable
    }

    /** 裁掉已经不可见的 id。选择为空或全都可见时原样返回，避免无意义的分配。 */
    fun retainVisible(selected: Set<Uuid>, visible: List<Conversation>): Set<Uuid> {
        if (selected.isEmpty()) return selected
        val visibleIds = HashSet<Uuid>(visible.size)
        visible.mapTo(visibleIds) { it.id }
        if (selected.all { it in visibleIds }) return selected
        return selected.filterTo(LinkedHashSet<Uuid>(selected.size)) { it in visibleIds }
    }

    /** 实际要删的会话，按可见列表的顺序给出，逐条交给 ChatService。 */
    fun targets(selected: Set<Uuid>, visible: List<Conversation>): List<Conversation> =
        if (selected.isEmpty()) emptyList() else visible.filter { it.id in selected }

    /** 选择里有多少条是置顶的：确认框要明确报出来。 */
    fun pinnedCount(selected: Set<Uuid>, visible: List<Conversation>): Int =
        if (selected.isEmpty()) 0 else visible.count { it.id in selected && it.isPinned }
}

/**
 * 一次批量删除的结果。
 *
 * [failed] 里是逐条删除时抛错的会话，页面会把它们留在选择里方便重试；
 * 不做整批事务，也不做全量内存快照的撤销。
 */
data class ConversationDeleteReport(
    val deleted: Int,
    val failed: List<Conversation>,
) {
    val total: Int
        get() = deleted + failed.size

    val isComplete: Boolean
        get() = failed.isEmpty()
}
