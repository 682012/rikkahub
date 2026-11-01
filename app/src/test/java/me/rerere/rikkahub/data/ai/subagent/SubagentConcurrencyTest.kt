package me.rerere.rikkahub.data.ai.subagent

import me.rerere.rikkahub.data.datastore.SubagentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理并发的内存兜底（fork.42 的 3.1）。
 *
 * 每个子代理是独立 runAgent + 独立工具集 + 独立消息历史，工具集里还带着 `tool_batch`
 * （内部 Semaphore(8)）。所以"用户填多少就真跑多少"在低端机上会直接把进程打爆：
 * 这里锁住"默认 4、上限 8、低内存自动降"这三条。
 */
class SubagentConcurrencyTest {
    @Test
    fun `default stays four and the hard cap stays eight`() {
        assertEquals(4, SubagentSettings().maxConcurrent)
        assertEquals(8, subagentMemoryConcurrencyCap(512))
        assertEquals(8, effectiveSubagentConcurrency(8, 512))
        // 用户配置永远不会被放大，也不会被压到 0
        assertEquals(1, effectiveSubagentConcurrency(1, 512))
        assertEquals(1, effectiveSubagentConcurrency(0, 512))
        assertEquals(1, effectiveSubagentConcurrency(-5, 512))
    }

    @Test
    fun `low memory class lowers the effective concurrency`() {
        // 256MB 以上才允许跑到 8
        assertEquals(8, effectiveSubagentConcurrency(8, 256))
        assertEquals(8, effectiveSubagentConcurrency(8, 1024))
        // 192MB 级：最多 4
        assertEquals(4, effectiveSubagentConcurrency(8, 192))
        assertEquals(4, effectiveSubagentConcurrency(4, 200))
        assertEquals(3, effectiveSubagentConcurrency(3, 200))
        // 小内存：最多 2
        assertEquals(2, effectiveSubagentConcurrency(8, 128))
        assertEquals(2, effectiveSubagentConcurrency(4, 64))
        assertEquals(1, effectiveSubagentConcurrency(1, 64))
        // 拿不到 memoryClass（<=0）时按最保守处理
        assertEquals(2, subagentMemoryConcurrencyCap(0))
        assertEquals(2, effectiveSubagentConcurrency(8, 0))
    }

    @Test
    fun `effective concurrency is never zero or negative`() {
        listOf(-5, 0, 1, 2, 4, 8, 99).forEach { configured ->
            listOf(0, 64, 128, 192, 256, 512).forEach { memory ->
                val effective = effectiveSubagentConcurrency(configured, memory)
                assertTrue("configured=$configured memory=$memory -> $effective", effective >= 1)
                assertTrue("configured=$configured memory=$memory -> $effective", effective <= 8)
            }
        }
    }
}
