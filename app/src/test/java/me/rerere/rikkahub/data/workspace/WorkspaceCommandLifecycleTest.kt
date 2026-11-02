package me.rerere.rikkahub.data.workspace

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class WorkspaceCommandLifecycleTest {
    @Test fun zeroTimeoutReallyMeansNoLimit() {
        WorkspaceCommandRequestValidator.validate("true", "/", null, 0)
        val result = WorkspaceNativeCommandRunner.run(NativeCommandRequest(
            argv = listOf("/bin/sh", "-c", "sleep 0.05; printf done"), timeoutMillis = 0,
        ))
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("done", result.stdoutText())
    }

    @Test fun privilegedWorkingDirectoryIsNotCheckedWithTheAppUid() {
        assertNull(WorkspaceShellCommand.validateCwd("/data/user/0/another.package/files"))
    }

    @Test fun revocationRejectsLateRegistrationButNotAnotherWorkspaceOrNewEpoch() {
        val registry = WorkspaceTaskRegistry()
        registry.revokeOwner("a:4")
        fun entry(id: String, owner: String) = WorkspaceTaskRegistry.Entry(id, owner, NativeCancelToken()) {}
        assertThrows(IllegalStateException::class.java) { registry.register(entry("old", "a:4")) }
        assertThrows(IllegalStateException::class.java) { registry.register(entry("older", "a:3")) }
        registry.register(entry("new", "a:5"))
        registry.register(entry("other", "b:4"))
        assertEquals(2, registry.size())
    }

    @Test fun processMarkersMustMatchTheWholeEnvironmentValue() {
        val dir = Files.createTempDirectory("marked-proc").toFile()
        try {
            dir.resolve("123").mkdirs()
            dir.resolve("123/environ").writeText("${WorkspaceProcessReaper.MARKER_ENV}=abc1\u0000OTHER=yes\u0000")
            assertTrue(WorkspaceProcessReaper.findMarkedPids("abc", dir).isEmpty())
            assertEquals(listOf(123), WorkspaceProcessReaper.findMarkedPids("abc1", dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptCleansUpInsteadOfOnlyCancellingTheCoroutine() {
        val marker = "test-${UUID.randomUUID()}"
        val interrupted = AtomicBoolean(false)
        val thread = Thread {
            try {
                WorkspaceNativeCommandRunner.run(NativeCommandRequest(
                    argv = listOf("/bin/sh", "-c", "sleep 30"), timeoutMillis = 60_000,
                    environment = mapOf(WorkspaceProcessReaper.MARKER_ENV to marker), marker = marker,
                    killCommand = if (File("/bin/kill").exists()) "/bin/kill" else "/usr/bin/kill",
                ))
            } catch (_: InterruptedException) { interrupted.set(true) }
        }
        thread.start()
        Thread.sleep(200)
        thread.interrupt()
        thread.join(6_000)
        assertFalse("worker remained blocked after interruption", thread.isAlive)
        assertTrue(interrupted.get())
        assertTrue(WorkspaceProcessReaper.findMarkedPids(marker).isEmpty())
    }
}
