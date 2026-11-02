package me.rerere.rikkahub.data.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceStoragePathsTest {
    @Test fun targetsKeepReservedMappingsIntact() {
        listOf("/", "/workspace", "/workspace/a", "/skills", "/proc/x", "/mnt/../etc", "/mnt/./x", "/mnt/a:b", "/mnt/a\nb").forEach {
            assertThrows("Accepted invalid target $it", IllegalArgumentException::class.java) { WorkspaceStoragePaths.target(it) }
        }
        assertEquals("/mnt/phone", WorkspaceStoragePaths.target("/mnt/phone/"))
        assertEquals("/sdcard", WorkspaceStoragePaths.target("/sdcard"))
        assertEquals("/root", WorkspaceStoragePaths.target("/root"))
    }

    @Test fun externalTreeIsResolvedWithinExplicitVolume() {
        val file = WorkspaceStoragePaths.fromDocumentId("primary:Download/work", mapOf("primary" to "/storage/emulated/0"))
        assertEquals("/storage/emulated/0/Download/work", file.path)
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceStoragePaths.fromDocumentId("primary:../private", mapOf("primary" to "/storage/emulated/0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceStoragePaths.fromDocumentId("primary:/etc", mapOf("primary" to "/storage/emulated/0"))
        }
        assertThrows(IllegalStateException::class.java) {
            WorkspaceStoragePaths.fromDocumentId("cloud:folder", mapOf("primary" to "/storage/emulated/0"))
        }
    }

    @Test fun duplicateTargetsAreRejectedBeforePersisting() {
        val mounts = listOf(WorkspaceMountSpec("a", "/tmp", "/mnt/shared"), WorkspaceMountSpec("b", "/var/tmp", "/mnt/shared/"))
        assertThrows(IllegalArgumentException::class.java) { WorkspaceStoragePaths.validate(WorkspaceAccessSettings(mounts = mounts)) }
    }
}
