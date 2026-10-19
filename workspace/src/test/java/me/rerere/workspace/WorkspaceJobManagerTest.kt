package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceJobManagerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun manager(): Pair<WorkspaceManager, WorkspaceJobManager> {
        val m = WorkspaceManager(baseDir = File(temp.root, "workspaces"))
        m.ensureWorkspace("test")
        return m to WorkspaceJobManager(m)
    }

    @Test fun `job survives return and reports real exit code and log`() {
        val (_, jobs) = manager()
        val info = jobs.start("test", "echo job-output; sleep 1; exit 7", timeoutMillis = 10_000)
        val first = jobs.status("test", info.id)
        assertTrue(first.running)
        Thread.sleep(1_800)
        val done = jobs.status("test", info.id)
        assertFalse(done.running)
        assertEquals(7, done.exitCode)
        assertTrue(done.logTail.contains("job-output"))
    }

    @Test fun `kill stops only an in-process owned job`() {
        val (_, jobs) = manager()
        val info = jobs.start("test", "sleep 30", timeoutMillis = 60_000)
        assertTrue(jobs.status("test", info.id).running)
        assertTrue(jobs.kill("test", info.id))
        val deadline = System.currentTimeMillis() + 5_000
        while (jobs.status("test", info.id).running && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertFalse(jobs.status("test", info.id).running)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `job ids cannot escape managed directory`() {
        val (_, jobs) = manager()
        jobs.status("test", "../../etc")
    }
}
