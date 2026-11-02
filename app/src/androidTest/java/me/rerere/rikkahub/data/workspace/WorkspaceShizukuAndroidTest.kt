package me.rerere.rikkahub.data.workspace

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

/** Run on the owned emulator with the official Shizuku server started as adb shell. */
@RunWith(AndroidJUnit4::class)
class WorkspaceShizukuAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val privileges = WorkspacePrivilegeManager(context)

    @After fun close() = runBlocking { privileges.closeAndJoin() }

    @Test fun explicitShizukuAuthorizationRunsAsRealShellAndCanWriteShellOnlyDirectory() = runBlocking {
        val identity = privileges.requestAuthorization(WorkspacePrivilegeMode.SHIZUKU)
        assertEquals(2000, identity.uid)
        val path = "/data/local/tmp/workspace-access-${UUID.randomUUID()}"
        val command = "printf workspace-shell > $path && cat $path; result=${'$'}?; rm -f $path; exit ${'$'}result"
        val result = privileges.execute(WorkspacePrivilegeMode.SHIZUKU, command, "/data/local/tmp", 10_000, ownerKey = "shizuku-test:0")
        assertEquals(2000, result.identity.uid)
        assertEquals(result.result.stderr, 0, result.result.exitCode)
        assertEquals("workspace-shell", result.result.stdout.trim())
        val app = privileges.execute(WorkspacePrivilegeMode.APP, "test -w /data/local/tmp", ownerKey = "app-test:0")
        assertNotEquals(0, app.result.exitCode)
    }

    @Test fun remoteCancellationDoesNotBreakTheNextCommand() = runBlocking {
        privileges.requestAuthorization(WorkspacePrivilegeMode.SHIZUKU)
        val started = System.nanoTime()
        val task = async { privileges.execute(WorkspacePrivilegeMode.SHIZUKU, "sleep 30", timeoutMillis = 60_000, ownerKey = "remote-cancel:0") }
        delay(600)
        task.cancel()
        runCatching { task.await() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 12_000)
        val next = privileges.execute(WorkspacePrivilegeMode.SHIZUKU, "id -u", timeoutMillis = 10_000, ownerKey = "remote-next:0")
        assertEquals(0, next.result.exitCode)
        assertEquals("2000", next.result.stdout.trim())
    }
}
