package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * JVM tests for the privilege-independent part of the host executor: quoting, bounds, timeout,
 * cancellation, identity guards and the "never downgrade" routing policy.
 *
 * The Android channels (Shizuku user service, su) are exercised through the same runner, but the
 * binder/manifest wiring can only be verified on a device (documented in the task report).
 */
class WorkspaceNativeCommandRunnerTest {

    private val sh = WorkspaceShellCommand.systemShell()

    private fun run(
        command: String,
        timeoutMillis: Long = 10_000,
        stdin: ByteArray? = null,
        cwd: String = "/",
        token: NativeCancelToken = NativeCancelToken(),
        marker: String = "",
        limit: Int = WorkspaceCommandLimits.MAX_OUTPUT_BYTES_PER_STREAM,
    ): NativeCommandOutcome = WorkspaceNativeCommandRunner.run(
        NativeCommandRequest(
            argv = listOf(sh, "-c", command),
            cwd = cwd,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
            marker = marker,
            environment = if (marker.isEmpty()) emptyMap() else mapOf(WorkspaceProcessReaper.MARKER_ENV to marker),
            cancelToken = token,
            outputLimitBytes = limit,
        ),
    )

    @Test
    fun quotingEscapesShellMetacharacters() {
        val hostile = "it's; printf injected; echo \$HOME `id` \\ '"
        val quoted = WorkspaceShellCommand.singleQuote(hostile)
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))

        val outcome = run("printf %s $quoted")
        assertEquals(0, outcome.exitCode)
        // Byte-for-byte round trip through a real shell: no expansion, no command substitution.
        assertEquals(hostile, outcome.stdoutText())
    }

    @Test
    fun capturesExitCodeAndSeparatesStreams() {
        val outcome = run("echo out; echo err >&2; exit 7")
        assertEquals(7, outcome.exitCode)
        assertEquals("out\n", outcome.stdoutText())
        assertEquals("err\n", outcome.stderrText())
        assertFalse(outcome.timedOut)
        assertFalse(outcome.truncated)
        assertNull(outcome.startError)
    }

    @Test
    fun pipesStdin() {
        val outcome = run("cat", stdin = "hello stdin\n".toByteArray())
        assertEquals(0, outcome.exitCode)
        assertEquals("hello stdin\n", outcome.stdoutText())
    }

    @Test
    fun timeoutKeepsOutputAndTerminates() {
        val started = System.nanoTime()
        val outcome = run("echo started; sleep 30", timeoutMillis = 1_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("command should be reported as timed out", outcome.timedOut)
        assertTrue("output produced before the timeout must be kept: '${outcome.stdoutText()}'", outcome.stdoutText().contains("started"))
        assertTrue("timeout must not wait for EOF, took ${elapsedMs}ms", elapsedMs < 15_000)
    }

    @Test
    fun truncatesEachStreamAtTheLimitAndKeepsDraining() {
        val limit = 64 * 1024
        val started = System.nanoTime()
        // 1 MiB on stdout, then a marker on stderr: the child must not block on a full pipe.
        val outcome = run("dd if=/dev/zero bs=1024 count=1024 2>/dev/null | tr '\\0' x; echo done >&2", limit = limit)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(0, outcome.exitCode)
        assertTrue(outcome.truncated)
        assertEquals(limit, outcome.stdout.size)
        assertEquals("done\n", outcome.stderrText())
        assertTrue("truncation must not deadlock, took ${elapsedMs}ms", elapsedMs < 20_000)
    }

    @Test
    fun cancelledBeforeStartNeverSpawns() {
        val marker = "ws-test-prestart"
        val token = NativeCancelToken().apply { cancel() }
        val outcome = run("sleep 30", token = token, marker = marker)

        assertTrue(outcome.cancelled)
        assertTrue(WorkspaceProcessReaper.findMarkedPids(marker).isEmpty())
    }

    @Test
    fun cancelDuringRunStopsTheProcess() {
        val token = NativeCancelToken()
        val canceller = Thread {
            Thread.sleep(300)
            token.cancel()
        }.apply { isDaemon = true; start() }
        val started = System.nanoTime()
        val outcome = run("sleep 30", timeoutMillis = 20_000, token = token)
        canceller.join()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(outcome.cancelled)
        assertFalse(outcome.timedOut)
        assertTrue("cancel must be prompt, took ${elapsedMs}ms", elapsedMs < 10_000)
    }

    @Test
    fun cwdMustBeAnAbsoluteAndroidHostPath() {
        assertNull(WorkspaceShellCommand.validateCwd("/"))
        val temp = File.createTempFile("ws-cwd", null).parentFile!!
        assertNull(WorkspaceShellCommand.validateCwd(temp.absolutePath))
        assertNotNull(WorkspaceShellCommand.validateCwd("workspace"))
        assertNotNull(WorkspaceShellCommand.validateCwd("/workspace/../workspace-access-src"))
        assertNotNull(WorkspaceShellCommand.validateCwd(""))
        assertEquals("/", WorkspaceCommandLimits.DEFAULT_CWD)
    }

    @Test
    fun cwdErrorsAreEnforcedBeforeSpawning() {
        val exception = runCatching {
            WorkspaceCommandRequestValidator.validate("true", "relative/path", null, 30_000)
        }.exceptionOrNull()
        assertTrue(exception is WorkspacePrivilegeException)
        assertEquals(WorkspacePrivilegeFailure.INVALID_REQUEST, (exception as WorkspacePrivilegeException).failure)
        assertTrue(exception.message!!.contains("absolute"))
    }

    @Test
    fun rootScriptChangesDirectoryWithQuoting() {
        val script = WorkspaceShellCommand.rootScript("echo hi", "/sdcard/My Dir's")
        assertTrue(script.contains("cd " + WorkspaceShellCommand.singleQuote("/sdcard/My Dir's")))
        assertTrue(script.endsWith("echo hi"))
    }

    @Test
    fun identityGuardRejectsMismatchAndAcceptsMatch() {
        assertNull(WorkspaceIdentityProbe.verifyUid(2_000, 2_000, "shizuku user service"))
        val message = WorkspaceIdentityProbe.verifyUid(2_000, 0, "shizuku user service")
        assertNotNull(message)
        assertTrue(message!!.contains("expected uid 2000"))
        assertTrue(message.contains("reported 0"))
        assertTrue(message.contains("refusing"))
    }

    @Test
    fun parsesUidAndProcStatus() {
        assertEquals(2_000, WorkspaceIdentityProbe.parseUid("2000\n"))
        assertEquals(0, WorkspaceIdentityProbe.parseUid("  0  "))
        assertNull(WorkspaceIdentityProbe.parseUid(""))
        assertEquals(0, WorkspaceIdentityProbe.parseProcStatusUid("Name:\tsh\nUid:\t0\t0\t0\t0\nGid:\t0\t0\t0\t0\n"))
        assertEquals(10_123, WorkspaceIdentityProbe.parseProcStatusUid("Uid: 10123 10123 10123 10123"))
        assertNull(WorkspaceIdentityProbe.parseProcStatusUid("Name:\tsh\n"))
        assertEquals("u:r:shell:s0", WorkspaceIdentityProbe.parseSelinuxContext("u:r:shell:s0\n"))
        assertEquals("", WorkspaceIdentityProbe.parseSelinuxContext("not a context"))
    }

    @Test
    fun policyRefusesInsteadOfFallingBack() {
        val notRunning = WorkspaceCommandPolicy.decide(
            WorkspacePrivilegeMode.SHIZUKU,
            ShizukuAvailability(binderAlive = false, permissionGranted = true, apiVersion = 13),
            suAvailable = true,
        )
        assertTrue(notRunning is WorkspaceRouteDecision.Rejected)
        assertEquals(WorkspacePrivilegeFailure.SHIZUKU_NOT_RUNNING, (notRunning as WorkspaceRouteDecision.Rejected).failure)

        val denied = WorkspaceCommandPolicy.decide(
            WorkspacePrivilegeMode.SHIZUKU,
            ShizukuAvailability(binderAlive = true, permissionGranted = false, apiVersion = 13),
        )
        assertEquals(WorkspacePrivilegeFailure.SHIZUKU_PERMISSION_DENIED, (denied as WorkspaceRouteDecision.Rejected).failure)

        val rootMissing = WorkspaceCommandPolicy.decide(WorkspacePrivilegeMode.ROOT, suAvailable = false)
        assertEquals(WorkspacePrivilegeFailure.ROOT_UNAVAILABLE, (rootMissing as WorkspaceRouteDecision.Rejected).failure)

        val app = WorkspaceCommandPolicy.decide(WorkspacePrivilegeMode.APP)
        assertEquals(WorkspaceRouteDecision.Allowed(WorkspacePrivilegeMode.APP), app)

        val shizuku = WorkspaceCommandPolicy.decide(
            WorkspacePrivilegeMode.SHIZUKU,
            ShizukuAvailability(binderAlive = true, permissionGranted = true, apiVersion = 13),
        )
        assertEquals(WorkspaceRouteDecision.Allowed(WorkspacePrivilegeMode.SHIZUKU), shizuku)

        // A rejection is never "silently APP": no allowed decision may carry another mode.
        assertNotEquals(WorkspaceRouteDecision.Allowed(WorkspacePrivilegeMode.APP), denied)
    }

    @Test
    fun requestValidatorRejectsOversizedInput() {
        val tooLong = "x".repeat(WorkspaceCommandLimits.MAX_COMMAND_CHARS + 1)
        val failures = listOf(
            runCatching { WorkspaceCommandRequestValidator.validate(tooLong, "/", null, 30_000) },
            runCatching {
                WorkspaceCommandRequestValidator.validate(
                    "true",
                    "/",
                    ByteArray(WorkspaceCommandLimits.MAX_STDIN_BYTES + 1),
                    30_000,
                )
            },
            runCatching { WorkspaceCommandRequestValidator.validate("true", "/", null, -1) },
            runCatching { WorkspaceCommandRequestValidator.validate("   ", "/", null, 30_000) },
        )
        for (attempt in failures) {
            val error = attempt.exceptionOrNull()
            assertTrue(error is WorkspacePrivilegeException)
            assertEquals(WorkspacePrivilegeFailure.INVALID_REQUEST, (error as WorkspacePrivilegeException).failure)
        }
    }

    @Test
    fun wireRoundTripKeepsOutputAndFlags() {
        val stdout = "line-1\n" + "x".repeat(WorkspaceCommandLimits.MAX_OUTPUT_BYTES_PER_STREAM)
        val payload = NativeWireResult(
            taskId = "ws-1",
            uid = 2_000,
            selinuxContext = "u:r:shell:s0",
            exitCode = 143,
            stdout = stdout,
            stderr = "boom\n",
            timedOut = true,
            truncated = true,
            cancelled = false,
            error = null,
        )
        val decoded = WorkspaceCommandWire.decode(WorkspaceCommandWire.encode(payload))
        assertEquals(payload, decoded)
        assertEquals(stdout.length, decoded.stdout.length)

        val bad = runCatching { WorkspaceCommandWire.decode("garbage".toByteArray()) }.exceptionOrNull()
        assertTrue(bad is WorkspacePrivilegeException)
        assertEquals(WorkspacePrivilegeFailure.INTERNAL_ERROR, (bad as WorkspacePrivilegeException).failure)
    }

    @Test
    fun suLocatorOnlyUsesExistingPaths() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ws-su-${System.nanoTime()}").apply { mkdirs() }
        val fakeSu = File(dir, "su").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        fakeSu.deleteOnExit()
        dir.deleteOnExit()
        assertEquals(fakeSu.absolutePath, WorkspaceSuLocator.locate(exists = { it == fakeSu.absolutePath }, pathEnv = dir.absolutePath))
        assertEquals(fakeSu.absolutePath, WorkspaceSuLocator.locate(exists = { it == fakeSu.absolutePath }, pathEnv = "/no/such/dir:${dir.absolutePath}"))
        assertNull(WorkspaceSuLocator.locate(exists = { false }, pathEnv = "/no/such/dir"))
    }

    @Test
    fun reaperFindsAndKillsOnlyMarkedProcesses() {
        assumeTrue("kill(1) is required for the reaper test", File(WorkspaceProcessReaper.DEFAULT_KILL).exists() || File("/bin/kill").exists())
        val killCommand = if (File(WorkspaceProcessReaper.DEFAULT_KILL).exists()) WorkspaceProcessReaper.DEFAULT_KILL else "/bin/kill"
        val marker = "ws-test-reaper-${System.nanoTime()}"
        val process = ProcessBuilder(sh, "-c", "sleep 30")
            .apply { environment()[WorkspaceProcessReaper.MARKER_ENV] = marker }
            .start()
        val pid = process.pid().toInt()
        try {
            awaitPid(process)
            val found = WorkspaceProcessReaper.findMarkedPids(marker)
            assertTrue("marked process $pid not found in $found", found.contains(pid))

            WorkspaceProcessReaper.killAll(found, killCommand)
            assertTrue("marked process should die", process.waitFor(10, TimeUnit.SECONDS))

            // Unrelated processes are never touched by a marker scan.
            assertTrue(WorkspaceProcessReaper.findMarkedPids("ws-test-absent-marker").none { it == pid })
        } finally {
            process.destroyForcibly()
        }
    }

    private fun awaitPid(process: Process) {
        val pid = process.pid().toInt()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (File("/proc/$pid").exists()) return
            Thread.sleep(20)
        }
    }
}
