package me.rerere.rikkahub.data.workspace

import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises Android's real all-files app-op, only on this task's owned emulator. */
@RunWith(AndroidJUnit4::class)
class WorkspaceStorageGrantAndroidTest {
    @Test fun sharedMountRequiresExplicitStorageGrantAndFailsClosedAfterRevocation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
        val root = "storage-test-${UUID.randomUUID()}"
        val source = "/sdcard/Download/$root"
        val initiallyGranted = Environment.isExternalStorageManager()
        val privileges = WorkspacePrivilegeManager(context)
        val access = WorkspaceAccessManager(context, privileges)
        try {
            shell("mkdir -p $source; printf shared-probe > $source/probe.txt")
            shell("appops set $packageName MANAGE_EXTERNAL_STORAGE default")
            assertFalse(access.storageAccessGranted())
            assertNotNull(runCatching { access.addMount(root, source, "/mnt/phone") }.exceptionOrNull())
            shell("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
            assertTrue(access.storageAccessGranted())
            access.addMount(root, source, "/mnt/phone")
            val binding = access.localAccess(root).bindMounts.single()
            assertEquals("shared-probe", binding.source.resolve("probe.txt").readText())
            shell("appops set $packageName MANAGE_EXTERNAL_STORAGE default")
            assertNotNull(runCatching { access.localAccess(root) }.exceptionOrNull())
            access.removeMount(root, access.get(root).mounts.single().id)
            assertTrue(access.get(root).mounts.isEmpty())
            assertEquals("shared-probe", shell("cat $source/probe.txt").trim())
        } finally {
            shell("appops set $packageName MANAGE_EXTERNAL_STORAGE ${if (initiallyGranted) "allow" else "default"}")
            shell("rm -rf $source")
            context.noBackupFilesDir.resolve("workspace-access/$root.json").delete()
            privileges.closeAndJoin()
        }
    }
}
