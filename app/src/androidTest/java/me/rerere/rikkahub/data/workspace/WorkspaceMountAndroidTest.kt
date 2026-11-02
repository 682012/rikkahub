package me.rerere.rikkahub.data.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceLocalAccess
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real PRoot bind test using Android's existing shell/toybox, with no Ubuntu image download. */
@RunWith(AndroidJUnit4::class)
class WorkspaceMountAndroidTest {
    @Test fun fileToolsAndRealShellUseTheSameDynamicMount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDir = File(context.cacheDir, "mount-test-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(testDir, "phone-folder").apply { mkdirs() }
        var enabled = true
        val manager = WorkspaceManager(
            baseDir = File(testDir, "workspaces"),
            shellRunner = ProotShellRunner(File(context.applicationInfo.nativeLibraryDir)),
            bindMounts = listOf(WorkspaceBindMount(File("/system"), "/system"), WorkspaceBindMount(File("/apex"), "/apex")),
            localAccess = { root ->
                WorkspaceLocalAccess(
                    bindMounts = if (enabled && root == "a") listOf(WorkspaceBindMount(source, "/mnt/phone", true)) else emptyList(),
                    trustedRootfs = root == "a",
                )
            },
        )
        try {
            manager.ensureWorkspace("a")
            val linux = manager.linuxDir("a")
            listOf("bin", "usr/bin", "etc", "tmp", "dev", "proc", "sys", "system", "apex", "mnt").forEach { File(linux, it).mkdirs() }
            File("/system/bin/sh").copyTo(File(linux, "bin/sh"), overwrite = true).setExecutable(true)
            File("/system/bin/toybox").copyTo(File(linux, "usr/bin/env"), overwrite = true).setExecutable(true)
            assertTrue(manager.hasRootfs("a"))
            val command = manager.executeCommand("a", "printf 'from-shell' > /mnt/phone/probe; /system/bin/cat /mnt/phone/probe", timeoutMillis = 10_000)
            assertEquals(command.stderr, 0, command.exitCode)
            assertEquals("from-shell", command.stdout.trim())
            assertEquals("from-shell", source.resolve("probe").readText())
            assertEquals(listOf("from-shell"), manager.readRootfsLines("a", "/mnt/phone/probe").lines)
            assertFalse(manager.resolveRootfsFile("b", "/mnt/phone/probe").exists())
            assertTrue(manager.layout("a").isWritable("/etc/hostname"))
            assertFalse(manager.layout("b").isWritable("/etc/hostname"))
            enabled = false
            assertFalse(manager.resolveRootfsFile("a", "/mnt/phone/probe").exists())
            val revoked = manager.executeCommand("a", "test ! -e /mnt/phone/probe && printf cleared", timeoutMillis = 10_000)
            assertEquals(revoked.stderr, 0, revoked.exitCode)
            assertEquals("cleared", revoked.stdout.trim())
            assertTrue(source.resolve("probe").exists())
        } finally { testDir.deleteRecursively() }
    }
}
