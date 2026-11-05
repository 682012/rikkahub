package me.rerere.rikkahub.ui.pages.share.handler

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedTextStoreTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun fullTextSurvivesRecreationWithoutLivingInNavigation() {
        val dir = temp.newFolder()
        val text = "你好\n".repeat(100000)
        val id = SharedTextStore(dir).write(text)
        assertEquals(36, id.length)
        assertEquals(text, SharedTextStore(dir).read(id))
    }
    @Test fun rejectsPathsAndMissingFiles() {
        val store = SharedTextStore(temp.newFolder())
        assertTrue(runCatching { store.read("../secret") }.isFailure)
        assertTrue(runCatching { store.read("00000000-0000-0000-0000-000000000000") }.isFailure)
    }
    @Test fun overLimitFailsWithoutTruncatingOrEvictingRecentShare() {
        val dir = temp.newFolder(); val store = SharedTextStore(dir)
        val id = store.write("保留")
        assertTrue(runCatching { store.write("x".repeat(4 * 1024 * 1024 + 1)) }.isFailure)
        assertEquals("保留", store.read(id))
        assertEquals(1, dir.listFiles()!!.size)
    }
    @Test fun onlyExpiredUnreadSharesArePruned() {
        val dir = temp.newFolder(); var now = 10L * 24 * 60 * 60 * 1000
        val store = SharedTextStore(dir) { now }
        val old = store.write("old")
        now += 8L * 24 * 60 * 60 * 1000
        val current = store.write("new")
        assertTrue(runCatching { store.read(old) }.isFailure)
        assertEquals("new", store.read(current))
    }
}
