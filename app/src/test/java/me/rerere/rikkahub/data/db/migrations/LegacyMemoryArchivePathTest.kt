package me.rerere.rikkahub.data.db.migrations

import me.rerere.rikkahub.data.model.MemoryFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧记忆存档路径必须带前导斜杠。
 *
 * `memory_file` 的读写都先过 `MemoryFile.normalizePath` 再按整串精确匹配（`MemoryDAO.getFile` /
 * `deleteFile`），所以相对路径写进去以后在界面上永远打不开、删不掉，成为孤儿行。
 */
class LegacyMemoryArchivePathTest {
    @Test
    fun `archive path is absolute and openable through the memory repository`() {
        val path = legacyMemoryArchivePath(1_700_000_000_000)

        assertTrue("legacy archive path must be absolute: $path", path.startsWith("/"))
        assertTrue(path.endsWith(".md"))
        assertEquals("normalization must be a no-op for a stored path", path, MemoryFile.normalizePath(path))
    }

    @Test
    fun `relative spelling is not what the lookup produces`() {
        // 旧写法（DatabaseBackup 里出现过）落库的是相对路径，而查询会规范化成 "/_legacy/..."，
        // 两者不相等，这就是那一批点不开的旧记忆。
        val relative = "_legacy/imported_memories_1700000000000.md"

        assertNotEquals(relative, MemoryFile.normalizePath(relative))
        assertEquals("/$relative", MemoryFile.normalizePath(relative))
    }
}
