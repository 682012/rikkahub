package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class AssistantMemoryIsolationTest {
    @Test fun `legacy shared flag cannot redirect assistant memory`() {
        val a = Assistant(useGlobalMemory = true)
        val b = Assistant(useGlobalMemory = true)
        assertEquals(a.id.toString(), a.memoryId)
        assertNotEquals(a.memoryId, b.memoryId)
        assertNotEquals(MemoryRepository.GLOBAL_MEMORY_ID, a.memoryId)
    }

    @Test fun `explicit migration fills missing files without replacing user edits`() = runBlocking {
        val repo = MemoryRepository(FakeMemoryDAO())
        repo.writeFile("old", "/profile.md", "old profile", "new")
        repo.writeFile("old", "/topics/work.md", "old work", "new")
        repo.writeFile("new", "/profile.md", "user edit", "new")
        repo.copyFiles("old", "new")
        assertEquals("user edit", repo.getFile("new", "/profile.md")?.content)
        assertEquals("old work", repo.getFile("new", "/topics/work.md")?.content)
        assertEquals("old profile", repo.getFile("old", "/profile.md")?.content)
        repo.copyFiles("old", "new")
        assertEquals(2, repo.getFiles("new").size)
    }
}
