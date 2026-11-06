package me.rerere.rikkahub.data.deviceprivilege

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 默认全关、原子落盘、损坏文件不能变成"已授权"。 */
class HighPrivilegeStoreTest {

    private val dir: File = Files.createTempDirectory("highpriv-store").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `missing record means everything off`() {
        val store = HighPrivilegeStore(File(dir, "absent"))
        val state = store.read()
        assertFalse(state.enabled)
        assertFalse(state.allowWrites)
        assertNull(state.assistantId)
        assertEquals(0, state.revision)
    }

    @Test
    fun `round trip keeps the binding and writes`() {
        val store = HighPrivilegeStore(dir)
        store.write(HighPrivilegeAutomation(enabled = true, allowWrites = true, assistantId = "assistant-a", revision = 3))
        val read = HighPrivilegeStore(dir).read()
        assertTrue(read.enabled)
        assertTrue(read.allowWrites)
        assertEquals("assistant-a", read.assistantId)
        assertEquals(3, read.revision)
    }

    @Test
    fun `write permission cannot outlive the master switch`() {
        val store = HighPrivilegeStore(dir)
        store.write(HighPrivilegeAutomation(enabled = false, allowWrites = true, assistantId = "   "))
        val read = store.read()
        assertFalse(read.enabled)
        assertFalse(read.allowWrites)
        assertNull(read.assistantId)
    }

    @Test
    fun `corrupt record fails closed`() {
        val store = HighPrivilegeStore(dir)
        store.write(HighPrivilegeAutomation(enabled = true, allowWrites = true, assistantId = "a"))
        File(dir, "automation.json").writeText("{not json")
        val reopened = HighPrivilegeStore(dir)
        val read = reopened.read()
        assertFalse(read.enabled)
        assertFalse(read.allowWrites)
        assertNull(read.assistantId)
        assertNotNull(reopened.lastError())
    }

    @Test
    fun `oversized record fails closed`() {
        val store = HighPrivilegeStore(dir)
        File(dir, "automation.json").writeText("x".repeat(70 * 1024))
        assertFalse(store.read().enabled)
        assertNotNull(store.lastError())
    }
}
