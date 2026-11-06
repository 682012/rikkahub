package me.rerere.rikkahub.service.stability

/**
 * 聊天内容显示决策（纯函数，无 Android/Compose 依赖）。
 *
 * 历史 bug：会话对象先以「空壳占位」进入内存，页面立刻把它当成真实内容渲染，于是已有聊天
 * 看起来被瞬间清空。把「还没加载好」和「确实没有消息」分成两种状态之后，空列表只有在内容就绪时
 * 才算真的空。
 *
 * - [ChatContentState.LOADING] 内容未就绪（占位空壳 / 正在读库），页面显示单独加载态，不能渲染成空会话
 * - [ChatContentState.CONTENT] 有可见消息
 * - [ChatContentState.EMPTY] 内容已就绪且没有消息：新会话，或被用户真正清空/删除
 */
enum class ChatContentState { LOADING, CONTENT, EMPTY }

/**
 * @param contentReady 内容是否已经就绪（加载完成 / 显式替换完成），来自 ConversationSession
 * @param visibleMessageCount 当前要显示的消息数量（包含为防闪白保留的上一份快照）
 */
fun chatContentState(contentReady: Boolean, visibleMessageCount: Int): ChatContentState = when {
    visibleMessageCount > 0 -> ChatContentState.CONTENT
    contentReady -> ChatContentState.EMPTY
    else -> ChatContentState.LOADING
}

/** 生成中的 loading 和内容加载是两件事：前者叠加在已有内容上，后者才允许顶替内容。 */
fun chatShowsContentLoading(contentReady: Boolean, visibleMessageCount: Int): Boolean =
    chatContentState(contentReady, visibleMessageCount) == ChatContentState.LOADING

/**
 * 什么时候允许把一份内存快照留给下一次打开同一个会话（避免重新读库时闪空）。
 *
 * 必须同时满足已加载过、且已经在库里：只在内存里、从没落库的草稿会话不该被保留，
 * 删除/清空会把会话换成一个 existsInDatabase=false 的空会话，所以真实清空照样能清掉，
 * 不会为了防闪白永远留着旧内容。
 */
object ChatSnapshotRetention {
    const val DEFAULT_MAX_ENTRIES = 8

    fun shouldRetain(contentInitialized: Boolean, existsInDatabase: Boolean): Boolean =
        contentInitialized && existsInDatabase
}

/**
 * 按会话 id 隔离的小容量快照缓存（最近写入优先淘汰最旧的）。
 *
 * key 必须是真实会话 id：换助手只是换一个会话 id / 同一会话的配置，不会拿到别人的内容。
 */
class ChatSnapshotCache<K : Any, V : Any>(
    private val maxEntries: Int = ChatSnapshotRetention.DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = LinkedHashMap<K, V>()

    fun put(key: K, value: V): Unit = synchronized(entries) {
        entries.remove(key)
        entries[key] = value
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    fun get(key: K): V? = synchronized(entries) { entries[key] }

    /** Transfer ownership back to the live session; never leave a stale second copy. */
    fun take(key: K): V? = synchronized(entries) { entries.remove(key) }

    fun remove(key: K): Unit = synchronized(entries) { entries.remove(key) }

    fun clear(): Unit = synchronized(entries) { entries.clear() }

    fun size(): Int = synchronized(entries) { entries.size }

    fun keys(): Set<K> = synchronized(entries) { entries.keys.toSet() }
}
