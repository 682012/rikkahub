package me.rerere.rikkahub.ui.pages.chat

data class DashboardTotals(
    val requests: Long = 0,
    val prompt: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val invalidCacheReport: Boolean = false,
    /** 命中率只来自纯计算模型 [computeCacheHitRate]；UI 不许自己拿 cached/prompt 再算一遍。 */
    val cache: CacheHitRate = CacheHitRate(),
)

data class DashboardModel(
    val name: String,
    val requests: Long,
    val tokens: Long,
)

data class DashboardContext(
    val used: Int?,
    val capacity: Int?,
    val approximate: Boolean = false,
)

enum class UsageScope {
    ALL, CHAT, SUBAGENT, OTHER
}

