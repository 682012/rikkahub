package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.days

/**
 * 逐张识别缓存：只存文字、按 key 隔离、能落到磁盘再读回。
 *
 * 用临时 JSON 文件跑，不碰 App 的 cacheDir，也不发请求。
 */
class SequentialImageCacheTest {

    private fun tempFile(): File = File.createTempFile("sequential-image-cache", ".json").apply { deleteOnExit() }

    @Test
    fun `put then get returns the same description`() {
        val file = tempFile()
        try {
            val cache = SequentialImageCache(file)
            cache.put("k1", "一张写着 42 的截图")
            assertEquals("一张写着 42 的截图", cache.get("k1"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `different keys never see each other`() {
        val file = tempFile()
        try {
            val cache = SequentialImageCache(file)
            cache.put("conv-a|model-x|img-1|q-1", "描述 A")
            assertNull(cache.get("conv-b|model-x|img-1|q-1"))
            assertNull(cache.get("conv-a|model-y|img-1|q-1"))
            assertNull(cache.get("conv-a|model-x|img-2|q-1"))
            assertNull(cache.get("conv-a|model-x|img-1|q-2"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `blank result is never cached`() {
        val file = tempFile()
        try {
            val cache = SequentialImageCache(file)
            cache.put("k1", "   ")
            assertNull(cache.get("k1"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `description survives a process restart through the file store`() {
        val file = tempFile()
        try {
            SequentialImageCache(file).put("k1", "重启后仍然可用的描述")
            // 新实例模拟下一次 buildStep：从磁盘预加载，命中就不再发图
            assertEquals("重启后仍然可用的描述", SequentialImageCache(file).get("k1"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `expired ttl does not return a stale description`() {
        val file = tempFile()
        try {
            val cache = SequentialImageCache(file, ttl = (-1).days)
            cache.put("k1", "旧描述")
            assertNull(cache.get("k1"))
        } finally {
            file.delete()
        }
    }
}
