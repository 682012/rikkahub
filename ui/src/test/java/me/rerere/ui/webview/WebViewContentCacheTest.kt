package me.rerere.ui.webview

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebViewContentCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stores large content outside navigation state`() {
        val content = "<html>${"preview".repeat(50_000)}</html>"

        val id = WebViewContentCache.store(temporaryFolder.root, content)

        assertEquals(64, id.length)
        assertEquals(content, WebViewContentCache.load(temporaryFolder.root, id))
    }

    @Test
    fun `reuses the same cache entry for identical content`() {
        val content = "<html>preview</html>"

        val firstId = WebViewContentCache.store(temporaryFolder.root, content)
        val secondId = WebViewContentCache.store(temporaryFolder.root, content)

        assertEquals(firstId, secondId)
        assertEquals(1, temporaryFolder.root.resolve("webview_content").listFiles()?.size)
    }

    @Test
    fun `rejects invalid cache ids`() {
        assertNull(WebViewContentCache.load(temporaryFolder.root, "../content"))
        // 大写十六进制与长度不对的 id 都不接受，避免被当成其它路径
        assertNull(WebViewContentCache.load(temporaryFolder.root, "A".repeat(64)))
        assertNull(WebViewContentCache.load(temporaryFolder.root, "abc"))
    }

    @Test
    fun `prune drops expired entries but keeps an expired entry that was just written`() {
        val directory = contentDirectory()
        val now = System.currentTimeMillis()
        val old = entry(directory, "a".repeat(64), bytes = 10, modified = now - TimeUnit.HOURS.toMillis(30))
        val fresh = entry(directory, "b".repeat(64), bytes = 10, modified = now)
        val keep = entry(directory, "c".repeat(64), bytes = 10, modified = now - TimeUnit.HOURS.toMillis(30))

        WebViewContentCache.prune(directory, keep = keep, now = now)

        assertFalse("过期条目必须被清掉", old.exists())
        assertTrue(fresh.exists())
        assertTrue("刚写入的那一条即使过期也要留着", keep.exists())
    }

    @Test
    fun `prune stops deleting as soon as the size budget is met`() {
        val directory = contentDirectory()
        val now = System.currentTimeMillis()
        // 4 条各 10 字节，预算 30 字节 → 只该删最旧的 1 条；账算错时会一路删到只剩 1 条
        val files = (0 until 4).map { index ->
            entry(directory, index.toString().repeat(64), bytes = 10, modified = now - (4 - index) * 1000L)
        }

        WebViewContentCache.prune(directory, keep = null, now = now, maxTotalBytes = 30)

        assertEquals(
            "只能删到刚好进预算，不能多删",
            listOf(files[1], files[2], files[3]).map { it.name }.sorted(),
            directory.listFiles()?.map { it.name }?.sorted(),
        )
    }

    @Test
    fun `prune bounds entry count and never removes the just written entry`() {
        val directory = contentDirectory()
        val now = System.currentTimeMillis()
        val files = (0 until 3).map { index ->
            entry(directory, "e$index".padEnd(64, 'f'), bytes = 1, modified = now - (3 - index) * 1000L)
        }

        WebViewContentCache.prune(directory, keep = files.last(), now = now, maxEntries = 2)

        assertEquals(2, directory.listFiles()?.size)
        assertTrue("最新写入的一条必须在", files.last().exists())
        assertFalse("最旧的一条应被淘汰", files.first().exists())
    }

    private fun contentDirectory(): File =
        temporaryFolder.root.resolve("webview_content").apply { mkdirs() }

    private fun entry(directory: File, name: String, bytes: Int, modified: Long): File =
        File(directory, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }
}
