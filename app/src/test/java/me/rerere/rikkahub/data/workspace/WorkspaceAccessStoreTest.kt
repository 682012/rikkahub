package me.rerere.rikkahub.data.workspace

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorkspaceAccessStoreTest {
    @Test fun localConsentRoundTripsWithoutTouchingOtherWorkspace() {
        val dir = Files.createTempDirectory("access-store").toFile()
        try {
            val store = WorkspaceAccessStore(dir)
            val config = WorkspaceAccessSettings(true, true, WorkspacePrivilegeMode.ROOT)
            store.write("a", config)
            assertEquals(config, store.read("a"))
            assertEquals(WorkspaceAccessSettings(), store.read("b"))
            assertNull(store.error("a"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptRecordFailsClosed() {
        val dir = Files.createTempDirectory("access-store").toFile()
        try {
            dir.resolve("a.json").writeText("not valid json")
            val store = WorkspaceAccessStore(dir)
            assertFalse(store.read("a").androidShellEnabled)
            assertNotNull(store.error("a"))
            store.write("a", WorkspaceAccessSettings())
            assertNull(store.error("a"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidRootCannotEscapeStore() {
        val dir = Files.createTempDirectory("access-store").toFile()
        try {
            val store = WorkspaceAccessStore(dir)
            assertThrows(IllegalArgumentException::class.java) { store.read("../other") }
            assertThrows(IllegalArgumentException::class.java) { store.write("/tmp/other", WorkspaceAccessSettings()) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidUpdatePreservesPreviousAuthorization() {
        val dir = Files.createTempDirectory("access-store").toFile()
        try {
            val store = WorkspaceAccessStore(dir)
            val previous = WorkspaceAccessSettings(trustedLinux = true)
            store.write("a", previous)
            val invalid = previous.copy(mounts = listOf(WorkspaceMountSpec("one", "/tmp", "/workspace")))
            assertThrows(IllegalArgumentException::class.java) { store.write("a", invalid) }
            assertEquals(previous, store.read("a"))
        } finally { dir.deleteRecursively() }
    }
}
