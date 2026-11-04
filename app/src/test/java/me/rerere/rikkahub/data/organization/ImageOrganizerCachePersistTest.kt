package me.rerere.rikkahub.data.organization

import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 图片整理缓存的可靠性：同进程多实例不互相覆盖、落盘失败可见且不先改内存。
 *
 * 纯 JVM（临时目录 + 真实文件），不碰 Android。
 */
class ImageOrganizerCachePersistTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun cacheFile(dir: File = temp.newFolder()): File = File(dir, "classification_cache.json")

    private fun cache(file: File, now: () -> Long = { 1_000L }) = ImageOrganizerCache(file, now = now)

    private fun rawText(file: File): String = file.readText()

    // ---- 多实例：串行 + 以磁盘新状态合并 ----

    @Test
    fun `a stale instance does not overwrite what another instance just wrote`() = runBlocking {
        val file = cacheFile()
        val a = cache(file)
        val b = cache(file)

        // b 先读一次，手里握着「空」的旧状态
        assertNull(b.get("gen:1|a.png|1|1"))
        a.put("gen:1|a.png|1|1", ImageCategory.PORTRAIT, "一个人")

        // b 现在写自己的条目：必须基于磁盘上的新状态，而不是它手里的空快照
        b.put("gen:2|b.png|2|2", ImageCategory.SCENERY, null)

        val fresh = cache(file)
        assertNotNull(fresh.get("gen:1|a.png|1|1"))
        assertNotNull(fresh.get("gen:2|b.png|2|2"))
    }

    @Test
    fun `protected ids from two instances are merged`() = runBlocking {
        val file = cacheFile()
        val a = cache(file)
        val b = cache(file)

        // 两个实例都先把状态读进内存，之后的写必须落在磁盘最新状态下
        assertEquals(emptySet<String>(), a.protectedIds())
        assertEquals(emptySet<String>(), b.protectedIds())

        a.setProtected("gen:1", true)
        b.setProtected("gen:2", true)

        assertEquals(setOf("gen:1", "gen:2"), cache(file).protectedIds())
    }

    @Test
    fun `unprotecting through one instance is not undone by the other`() = runBlocking {
        val file = cacheFile()
        val a = cache(file)
        val b = cache(file)
        a.setProtected(listOf("gen:1", "gen:2"), true)
        b.isProtected("gen:1") // b 记住的是「两个都保留」

        b.setProtected("gen:2", false)

        assertEquals(setOf("gen:1"), cache(file).protectedIds())
        assertFalse(cache(file).isProtected("gen:2"))
    }

    @Test
    fun `concurrent writes from two instances keep every entry`() = runBlocking {
        val file = cacheFile()
        val a = cache(file)
        val b = cache(file)

        coroutineScope {
            repeat(40) { index ->
                launch {
                    val target = if (index % 2 == 0) a else b
                    target.put("gen:$index|x.png|$index|$index", ImageCategory.OTHER, null)
                }
            }
        }

        val fresh = cache(file)
        repeat(40) { index ->
            assertNotNull("entry $index lost", fresh.get("gen:$index|x.png|$index|$index"))
        }
    }

    @Test
    fun `the file carries an increasing revision`() = runBlocking {
        val file = cacheFile()
        val store = cache(file)
        store.setProtected("gen:1", true)
        assertTrue(rawText(file).contains("\"revision\":1"))
        store.setProtected("gen:2", true)
        assertTrue(rawText(file).contains("\"revision\":2"))
    }

    // ---- 落盘失败：可见，且不先改内存 ----

    @Test
    fun `a failed write throws and leaves memory unadvanced`() {
        val dir = temp.newFolder("cache")
        val blocker = File(dir, "blocker").apply { writeText("not a dir") }
        // 父目录是一个普通文件 → 临时文件根本写不出来
        val store = cache(File(blocker, "classification_cache.json"))

        val error = assertThrows(ImageCachePersistException::class.java) {
            runBlocking { store.setProtected("gen:1", true) }
        }
        assertTrue(error.message!!.isNotEmpty())

        // 内存没有变成「已保存」，失败状态可以读出来
        assertFalse(runBlocking { store.isProtected("gen:1") })
        assertNotNull(store.persistFailure.value)
        assertEquals("not a dir", blocker.readText())
    }

    @Test
    fun `a failed write keeps the previously saved state on disk and in memory`() {
        val dir = temp.newFolder("cache")
        val file = File(dir, "classification_cache.json")
        val store = cache(file)

        runBlocking { store.setProtected("gen:1", true) }
        val saved = rawText(file).trim()

        // 让临时文件写不出来：占位目录正好叫 <file>.tmp，且非空
        val stuck = File(dir, "classification_cache.json.tmp").apply { mkdirs() }
        File(stuck, "occupied").writeText("x")

        assertThrows(ImageCachePersistException::class.java) {
            runBlocking { store.setProtected("gen:2", true) }
        }

        // 磁盘没变、内存也没变：不会出现「界面显示已保存、重启就没了」
        assertEquals(saved, rawText(file).trim())
        assertTrue(runBlocking { store.isProtected("gen:1") })
        assertFalse(runBlocking { store.isProtected("gen:2") })
        assertNotNull(store.persistFailure.value)
        assertEquals(setOf("gen:1"), runBlocking { cache(file).protectedIds() })
    }

    @Test
    fun `a successful write clears the failure flag`() {
        val dir = temp.newFolder("cache")
        val file = File(dir, "classification_cache.json")
        val stuck = File(dir, "classification_cache.json.tmp").apply { mkdirs() }
        File(stuck, "occupied").writeText("x")
        val store = cache(file)

        assertThrows(ImageCachePersistException::class.java) {
            runBlocking { store.put("gen:1|a.png|1|1", ImageCategory.OTHER, null) }
        }
        assertNotNull(store.persistFailure.value)

        // 障碍拿掉后重写一次就成功，失败标记跟着清掉
        File(stuck, "occupied").delete()
        stuck.delete()
        runBlocking {
            store.put("gen:1|a.png|1|1", ImageCategory.OTHER, null)
            assertNull(store.persistFailure.value)
            assertNotNull(store.get("gen:1|a.png|1|1"))
        }
    }

    @Test
    fun `a missing file reads as empty instead of failing`() = runBlocking {
        val file = File(temp.newFolder("cache"), "not_created_yet.json")
        val cache = cache(file)
        assertNull(cache.get("gen:1|a.png|1|1"))
        assertEquals(emptySet<String>(), cache.protectedIds())
        assertFalse(file.exists())
    }
}
