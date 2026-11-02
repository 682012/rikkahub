package me.rerere.rikkahub.data.workspace

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceLocalAccess
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorkspaceRepositoryAccessTest {
    @Test fun applicationRepositoryExposesTheSelectedWorkspaceLayoutNotTheLegacyStaticLayout() = runBlocking {
        val dir = Files.createTempDirectory("repo-access").toFile()
        try {
            val source = dir.resolve("phone").apply { mkdirs() }
            val manager = WorkspaceManager(dir.resolve("workspaces"), localAccess = { root ->
                if (root == "root-a") WorkspaceLocalAccess(listOf(WorkspaceBindMount(source, "/mnt/phone", true)), true)
                else WorkspaceLocalAccess()
            })
            val dao = mockk<WorkspaceDAO>()
            coEvery { dao.getById("a") } returns WorkspaceEntity("a", "A", "root-a", createdAt = 1, updatedAt = 1)
            coEvery { dao.getById("b") } returns WorkspaceEntity("b", "B", "root-b", createdAt = 1, updatedAt = 1)
            val repository = WorkspaceRepository(dao, manager, mockk(), mockk(), mockk(), mockk())
            val a = repository.layout("a")!!
            assertTrue(a.isWritable("/etc/hostname"))
            assertTrue(a.writableRoots.contains("/mnt/phone"))
            val b = repository.layout("b")!!
            assertFalse(b.isWritable("/etc/hostname"))
            assertFalse(b.writableRoots.contains("/mnt/phone"))
        } finally { dir.deleteRecursively() }
    }
}
