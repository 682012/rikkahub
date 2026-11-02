package me.rerere.rikkahub.data.workspace

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceAccessAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val privileges = WorkspacePrivilegeManager(context)

    @After fun cleanUp() { privileges.close() }

    @Test fun appChannelReportsTheActualAndroidUid() = runBlocking {
        val identity = privileges.requestAuthorization(WorkspacePrivilegeMode.APP)
        assertEquals(Process.myUid(), identity.uid)
        assertNotEquals(0, identity.uid)
        val result = privileges.execute(WorkspacePrivilegeMode.APP, "id -u", ownerKey = "test:0")
        assertEquals(0, result.result.exitCode)
        assertEquals(Process.myUid(), result.result.stdout.trim().toInt())
        assertEquals(identity.uid, result.identity.uid)
    }

    @Test fun rootCannotBeActivatedByAnAiExecutionCall() = runBlocking {
        val failure = runCatching { privileges.execute(WorkspacePrivilegeMode.ROOT, "id", ownerKey = "test:0") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("explicit authorization"))
    }

    @Test fun localConsentCanBeRevokedWithoutDeletingWorkspaceData() = runBlocking {
        val root = "test-access-${UUID.randomUUID()}"
        val access = WorkspaceAccessManager(context, privileges)
        try {
            access.authorize(root, WorkspacePrivilegeMode.APP)
            assertTrue(access.get(root).androidShellEnabled)
            assertEquals(Process.myUid(), access.executeAndroid(root, "id -u", "/", 10_000).identity.uid)
            access.revoke(root)
            assertFalse(access.get(root).androidShellEnabled)
            assertNotNull(runCatching { access.executeAndroid(root, "id", "/", 10_000) }.exceptionOrNull())
        } finally { context.noBackupFilesDir.resolve("workspace-access/$root.json").delete() }
    }

    @Test fun cancellationStopsTheAppHostCommandPromptly() = runBlocking {
        val started = System.nanoTime()
        val task = async { privileges.execute(WorkspacePrivilegeMode.APP, "sleep 30", timeoutMillis = 60_000, ownerKey = "cancel-test:0") }
        delay(200)
        task.cancel()
        runCatching { task.await() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
    }
}
