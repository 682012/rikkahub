package me.rerere.rikkahub.data.ai.inputassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * 优化结果缓存的真实文件行为：原子替换、原文改动失效、不跨助手、无改动/失败结论也算一次尝试。
 *
 * 这些判定直接决定"会不会因为重连/重新生成再扣一次钱"，所以必须用真文件跑，而不是打桩。
 */
class InputOptimizationCacheTest {
    private fun tempDir(): File = Files.createTempDirectory("input-assist-cache").toFile()

    private fun entry(
        conversationId: Uuid,
        messageId: Uuid,
        assistantId: Uuid,
        text: String?,
        hash: String = "hash-1",
        status: OptimizationCacheKind = OptimizationCacheKind.REWRITE,
        createdAtMillis: Long = System.currentTimeMillis(),
    ) = InputOptimizationCache.Entry(
        conversationId = conversationId.toString(),
        messageId = messageId.toString(),
        assistantId = assistantId.toString(),
        originalHash = hash,
        optimizedText = text,
        status = status,
        modelName = "fast-model",
        modelId = "gpt-4o-mini",
        providerId = "provider-1",
        failureReason = null,
        // 默认用当前时间：默认 TTL 是 14 天，写死一个很小的 created 时间会被当成过期条目
        createdAtMillis = createdAtMillis,
    )

    @Test
    fun `a cached rewrite is returned and no temp file is left behind`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        cache.put(entry(conversation, message, assistant, "目标：合并 3 个 csv"))

        val hit = cache.get(conversation, message, "hash-1", assistant)
        assertEquals("目标：合并 3 个 csv", hit?.optimizedText)
        assertEquals("fast-model", hit?.modelName)
        assertEquals(OptimizationCacheKind.REWRITE, hit?.status)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `model and provider ids survive for diagnostics`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        cache.put(entry(conversation, message, assistant, "优化版"))

        val hit = cache.get(conversation, message, "hash-1", assistant)
        assertEquals("gpt-4o-mini", hit?.modelId)
        assertEquals("provider-1", hit?.providerId)
    }

    @Test
    fun `multi line text and backslashes survive a round trip`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        val text = "目标：把 C:\\tmp\\a.txt 里的 2 行删掉\n约束：不要改编码\\不要动换行\n"
        cache.put(entry(conversation, message, assistant, text))

        // 原样往返：换行和反斜杠都是用户内容的一部分，不能被吃掉
        assertEquals(text, cache.get(conversation, message, "hash-1", assistant)?.optimizedText)
    }

    @Test
    fun `editing the message invalidates the old rewrite`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        cache.put(entry(conversation, message, assistant, "旧优化版"))

        assertNull(cache.get(conversation, message, "hash-2", assistant))
        assertEquals(0, cache.size())
    }

    @Test
    fun `a rewrite never leaks to another assistant`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        cache.put(entry(conversation, message, Uuid.random(), "A 助手的优化版"))

        assertNull(cache.get(conversation, message, "hash-1", Uuid.random()))
        // 对不上就直接删掉，不留一份可能被误命中的旧结论
        assertEquals(0, cache.size())
    }

    @Test
    fun `an entry without an assistant id is never a hit`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        // 手写一条没有助手字段的记录：不能因为"缺字段"就当成任意助手都可用
        File(dir, "$conversation-$message.entry").writeText(
            "v=2\nc=$conversation\nm=$message\nh=hash-1\ns=REWRITE\nx=不知道是谁写的\n",
        )

        assertNull(cache.get(conversation, message, "hash-1", assistant))
        assertEquals(0, cache.size())
    }

    @Test
    fun `an unchanged attempt is remembered so the same message is not sent twice`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        cache.put(
            entry(
                conversation, message, assistant, text = null,
                status = OptimizationCacheKind.UNCHANGED,
            ),
        )

        val hit = cache.get(conversation, message, "hash-1", assistant)
        assertEquals(OptimizationCacheKind.UNCHANGED, hit?.status)
        assertNull(hit?.optimizedText)
        // 原文改了就是新问题，必须重新判定
        assertNull(cache.get(conversation, message, "hash-2", assistant))
    }

    @Test
    fun `a failed attempt is remembered only briefly`() {
        val dir = tempDir()
        var now = 1_000L
        val cache = InputOptimizationCache(dir, failedTtlMillis = 100L, now = { now })
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        cache.put(
            InputOptimizationCache.Entry(
                conversationId = conversation.toString(),
                messageId = message.toString(),
                assistantId = assistant.toString(),
                originalHash = "hash-1",
                optimizedText = null,
                status = OptimizationCacheKind.FAILED,
                failureReason = "timeout",
                createdAtMillis = 1_000L,
            ),
        )

        now = 1_050L
        val hit = cache.get(conversation, message, "hash-1", assistant)
        assertEquals(OptimizationCacheKind.FAILED, hit?.status)
        assertEquals("timeout", hit?.failureReason)

        // 5 分钟（这里是 100ms）之后就允许再试一次：一次偶发超时不能变成永久结论
        now = 1_200L
        assertNull(cache.get(conversation, message, "hash-1", assistant))
    }

    @Test
    fun `a failed attempt is not stored as a usable rewrite`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        cache.put(
            entry(
                conversation, message, assistant, text = null,
                status = OptimizationCacheKind.FAILED,
            ),
        )

        assertNull(cache.get(conversation, message, "hash-1", assistant)?.optimizedText)
        assertEquals(OptimizationCacheKind.FAILED, cache.get(conversation, message, "hash-1", assistant)?.status)
    }

    @Test
    fun `explicit invalidation removes the entry`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        cache.put(entry(conversation, message, assistant, "优化版"))

        cache.invalidateMessage(conversation, message)
        assertNull(cache.get(conversation, message, "hash-1", assistant))
    }

    @Test
    fun `forgetting a conversation drops every message of it`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val other = Uuid.random()
        val assistant = Uuid.random()
        cache.put(entry(conversation, Uuid.random(), assistant, "优化版 1"))
        cache.put(entry(conversation, Uuid.random(), assistant, "优化版 2"))
        cache.put(entry(other, Uuid.random(), assistant, "别人的优化版"))

        cache.invalidateConversation(conversation)
        assertEquals(1, cache.size())
    }

    @Test
    fun `expired entries are not reused`() {
        val dir = tempDir()
        var now = 1_000L
        val cache = InputOptimizationCache(dir, ttlMillis = 100L, now = { now })
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        cache.put(entry(conversation, message, assistant, "优化版", hash = "hash-1", createdAtMillis = 1_000L))

        now = 1_050L
        assertEquals("优化版", cache.get(conversation, message, "hash-1", assistant)?.optimizedText)
        now = 1_200L
        assertNull(cache.get(conversation, message, "hash-1", assistant))
    }

    @Test
    fun `blank rewrites are never cached`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        cache.put(entry(conversation, message, assistant, "   "))

        assertEquals(0, cache.size())
        assertNull(cache.get(conversation, message, "hash-1", assistant))
    }

    @Test
    fun `a rewrite without text is never cached`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        // 失败/无改动可以是"没有文本"，但可替换的改写不行
        cache.put(entry(conversation, message, assistant, null))

        assertEquals(0, cache.size())
    }

    @Test
    fun `cache stays bounded`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir, maxEntries = 2)
        val assistant = Uuid.random()
        repeat(5) { index ->
            val conversation = Uuid.random()
            val message = Uuid.random()
            cache.put(entry(conversation, message, assistant, "优化版 $index"))
        }
        assertTrue(cache.size() <= 2)
    }

    @Test
    fun `corrupt files degrade to a cache miss`() {
        val dir = tempDir()
        val cache = InputOptimizationCache(dir)
        val conversation = Uuid.random()
        val message = Uuid.random()
        val assistant = Uuid.random()
        File(dir, "$conversation-$message.entry").writeText("not a cache entry")
        assertNull(cache.get(conversation, message, "hash-1", assistant))
        assertFalse(File(dir, "$conversation-$message.entry").exists())
    }
}
