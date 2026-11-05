package me.rerere.rikkahub.data.skills

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SkillFileCacheTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun cacheHitAndExplicitInvalidation() {
        val file = temp.newFile().apply { writeText("old") }
        val cache = StampedFileCache<String>()
        var reads = 0
        fun load() = cache.get(file) { reads++; it.readText() }
        assertEquals("old", load()); assertEquals("old", load()); assertEquals(1, reads)
        file.writeText("new"); cache.invalidate(file)
        assertEquals("new", load()); assertEquals(2, reads)
        file.delete(); assertTrue(runCatching { load() }.isFailure)
    }
    @Test fun revalidationBoundsStaleness() {
        val file = temp.newFile().apply { writeText("abc") }; var now = 0L; var reads = 0
        val cache = StampedFileCache<String>(revalidateMillis = 10, now = { now })
        repeat(2) { cache.get(file) { reads++; it.readText() } }
        assertEquals(1, reads); now = 11
        cache.get(file) { reads++; it.readText() }; assertEquals(2, reads)
    }
    @Test fun headerDoesNotReadHugeBody() {
        val file = temp.newFile().apply { writeText("---\nname: test\ndescription: useful\n---\n" + "x".repeat(70000)) }
        assertEquals("test", SkillFileCache.metadata(file)["name"])
        file.writeText("---\nname: " + "x".repeat(70000)); SkillFileCache.invalidate(file)
        assertTrue(runCatching { SkillFileCache.metadata(file) }.isFailure)
    }
    @Test fun bodyHasSizeLimit() {
        val file = temp.newFile().apply { writeText("x".repeat(4 * 1024 * 1024 + 1)) }
        assertTrue(runCatching { SkillFileCache.text(file) }.isFailure)
    }
}
