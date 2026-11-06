package me.rerere.rikkahub.service.stability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStabilityTest {
    // ---- 空和「暂空」分开：显式 empty 不能被当成加载中 ----

    @Test
    fun `empty list before content is ready is loading not empty chat`() {
        assertEquals(ChatContentState.LOADING, chatContentState(contentReady = false, visibleMessageCount = 0))
        assertTrue(chatShowsContentLoading(contentReady = false, visibleMessageCount = 0))
    }

    @Test
    fun `explicit empty conversation after load is a real empty chat`() {
        assertEquals(ChatContentState.EMPTY, chatContentState(contentReady = true, visibleMessageCount = 0))
        assertFalse(chatShowsContentLoading(contentReady = true, visibleMessageCount = 0))
    }

    @Test
    fun `retained snapshot keeps content visible while the next snapshot is not ready`() {
        assertEquals(ChatContentState.CONTENT, chatContentState(contentReady = false, visibleMessageCount = 7))
        assertFalse(chatShowsContentLoading(contentReady = false, visibleMessageCount = 7))
    }

    @Test
    fun `ready content is content`() {
        assertEquals(ChatContentState.CONTENT, chatContentState(contentReady = true, visibleMessageCount = 1))
    }

    // ---- 保留策略：删掉/清空真的能清 ----

    @Test
    fun `only loaded and persisted conversations may be retained`() {
        assertTrue(ChatSnapshotRetention.shouldRetain(contentInitialized = true, existsInDatabase = true))
        assertFalse(ChatSnapshotRetention.shouldRetain(contentInitialized = false, existsInDatabase = true))
        // 删除/清空会把会话换成未落库的空会话：必须丢掉缓存，不能为了防闪白永远 retain
        assertFalse(ChatSnapshotRetention.shouldRetain(contentInitialized = true, existsInDatabase = false))
    }

    // ---- 缓存按真实会话 id 隔离 ----

    @Test
    fun `cache is keyed by conversation id and never leaks across ids`() {
        val cache = ChatSnapshotCache<String, String>()
        cache.put("a", "chat of a")
        assertEquals("chat of a", cache.get("a"))
        // 另一个助手对应的另一个会话 id：必须是空的，不能看到 a 的内容
        assertNull(cache.get("b"))

        cache.put("b", "chat of b")
        assertEquals("chat of b", cache.get("b"))
        assertEquals("chat of a", cache.get("a"))
    }

    @Test
    fun `removed conversation does not come back`() {
        val cache = ChatSnapshotCache<String, String>()
        cache.put("a", "chat of a")
        cache.remove("a")
        assertNull(cache.get("a"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `repeated subscription lookups return the same snapshot without clearing it`() {
        // 反复订阅/刷新只读缓存，不产生空值，也不把自己挤掉
        val cache = ChatSnapshotCache<String, String>()
        cache.put("a", "chat of a")
        repeat(50) { assertEquals("chat of a", cache.get("a")) }
        assertEquals(1, cache.size())
        assertTrue(cache.keys().contains("a"))
    }

    @Test
    fun `cache is bounded and evicts the oldest entry`() {
        val cache = ChatSnapshotCache<String, String>(maxEntries = 3)
        listOf("a", "b", "c").forEach { cache.put(it, "chat of $it") }
        cache.put("d", "chat of d")
        assertNull(cache.get("a"))
        assertEquals(listOf("b", "c", "d").toSet(), cache.keys())
        assertEquals(3, cache.size())
    }

    @Test
    fun `rewriting an id keeps the newest snapshot`() {
        val cache = ChatSnapshotCache<String, String>()
        cache.put("a", "old")
        cache.put("a", "new")
        assertEquals("new", cache.get("a"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `clear drops everything`() {
        val cache = ChatSnapshotCache<String, String>()
        cache.put("a", "chat of a")
        cache.clear()
        assertNull(cache.get("a"))
        assertEquals(0, cache.size())
    }
}
