package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku user service (runs as the Shizuku server UID: 0 with root Shizuku, 2000 with shell).
 *
 * It is instantiated by Shizuku inside its own app_process via reflection, so:
 *  - it is not declared in AndroidManifest.xml (it is not an Android component);
 *  - it keeps a no-arg constructor *and* the API 13 `Context` constructor;
 *  - R8 must keep the class, both constructors and the AIDL Stub (`@Keep`).
 *
 * The class only uses framework APIs available to a plain app_process (no Activity, no
 * ContentResolver of our own package), and never reports a made-up identity: the UID comes
 * from `Os.getuid()` / `id -u`, and every command is guarded against that value.
 */
@Keep
class WorkspaceShizukuService : IWorkspaceShellService.Stub {

    private var ownerUid: Int = -1

    constructor() : super()

    @Keep
    constructor(context: Context) : super() {
        ownerUid = context.packageManager.getApplicationInfo(me.rerere.rikkahub.BuildConfig.APPLICATION_ID, 0).uid
    }

    private fun enforceOwner() {
        check(ownerUid >= 0 && android.os.Binder.getCallingUid() == ownerUid) { "Unauthorized workspace service caller" }
    }

    private class RunningTask(
        val taskId: String,
        val ownerKey: String,
        val expectedUid: Int,
        val token: NativeCancelToken,
    ) {
        val cancelled = AtomicBoolean(false)
        @Volatile
        var control: NativeProcessControl? = null
    }

    private val tasks = ConcurrentHashMap<String, RunningTask>()

    /** Cancels that arrived before the worker thread spawned anything. */
    private val pendingCancels: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Reserved transaction from the Shizuku server: called on unbind(remove = true). */
    override fun destroy() {
        val caller = android.os.Binder.getCallingUid()
        check(caller == ownerUid || caller == Os.getuid()) { "Unauthorized workspace service caller" }
        Log.i(TAG, "destroy requested, cancelling ${tasks.size} task(s)")
        for (task in tasks.values) cancelTask(task)
        tasks.clear()
        pendingCancels.clear()
        kotlin.system.exitProcess(0)
    }

    override fun probe(): String {
        enforceOwner()
        val uid = Os.getuid()
        val realUid = Os.getuid()
        val context = WorkspaceIdentityProbe.parseSelinuxContext(runProbe("id -Z"))
        return buildString {
            append("uid=").append(uid).append('\n')
            append("realUid=").append(realUid).append('\n')
            append("context=").append(context)
        }
    }

    override fun startCommand(
        taskId: String?,
        ownerKey: String?,
        command: String?,
        cwd: String?,
        timeoutMillis: Long,
        stdin: ByteArray?,
        expectedUid: Int,
    ): ParcelFileDescriptor? {
        enforceOwner()
        val id = taskId?.takeIf { it.isNotBlank() }
            ?: return failFast("taskId must not be empty")
        val userCommand = command.orEmpty()
        val workDir = cwd?.takeIf { it.isNotBlank() } ?: WorkspaceCommandLimits.DEFAULT_CWD

        validateOrNull(userCommand, workDir, stdin, timeoutMillis)?.let { reason ->
            return failFast(reason)
        }

        val task = RunningTask(id, ownerKey.orEmpty(), expectedUid, NativeCancelToken())
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            return failFast("failed to create output pipe: ${t.message}")
        }
        val readSide = pipe[0]
        val writeSide = pipe[1]
        tasks[id] = task

        // Cancel may race with the binder call: honour it before any process is created.
        if (pendingCancels.remove(id)) {
            task.cancelled.set(true)
            task.token.cancel()
        }

        Thread({
            executeTask(task, writeSide, userCommand, workDir, timeoutMillis, stdin)
        }, "ws-task-$id").apply { isDaemon = true }.start()

        return readSide
    }

    override fun cancel(taskId: String?) {
        enforceOwner()
        val id = taskId ?: return
        val task = tasks[id]
        if (task == null) {
            pendingCancels.add(id)
            return
        }
        cancelTask(task)
    }

    private fun cancelTask(task: RunningTask) {
        task.cancelled.set(true)
        task.token.cancel()
        task.control?.terminate()
    }

    private fun executeTask(
        task: RunningTask,
        writeSide: ParcelFileDescriptor,
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
    ) {
        var uid = Os.getuid()
        var context = ""
        var payload: NativeWireResult
        try {
            // Identity guard runs before the user command, in the very same process/UID.
            val probedUid = WorkspaceIdentityProbe.parseUid(runProbe("id -u"))
            context = WorkspaceIdentityProbe.parseSelinuxContext(runProbe("id -Z"))
            if (probedUid == null) {
                payload = errorPayload(task, uid, context, "identity probe failed: `id -u` returned no uid")
                writeResult(writeSide, payload)
                return
            }
            uid = probedUid
            val mismatch = WorkspaceIdentityProbe.verifyUid(task.expectedUid, probedUid, "shizuku user service")
            if (mismatch != null) {
                payload = errorPayload(task, uid, context, mismatch)
                writeResult(writeSide, payload)
                return
            }

            val outcome = WorkspaceNativeCommandRunner.run(
                NativeCommandRequest(
                    argv = listOf(WorkspaceShellCommand.systemShell(), "-c", command),
                    cwd = cwd,
                    timeoutMillis = timeoutMillis,
                    stdin = stdin,
                    environment = mapOf(WorkspaceProcessReaper.MARKER_ENV to task.taskId),
                    marker = task.taskId,
                    cancelToken = task.token,
                    killCommand = WorkspaceProcessReaper.DEFAULT_KILL,
                ),
                onStarted = { control ->
                    task.control = control
                    if (task.cancelled.get()) control.terminate()
                },
            )

            payload = NativeWireResult(
                taskId = task.taskId,
                uid = uid,
                selinuxContext = context,
                exitCode = outcome.exitCode,
                stdout = outcome.stdoutText(),
                stderr = if (outcome.startError != null) {
                    outcome.stderrText() + "\n" + outcome.startError
                } else {
                    outcome.stderrText()
                },
                timedOut = outcome.timedOut,
                truncated = outcome.truncated,
                cancelled = outcome.cancelled || task.cancelled.get(),
            )
        } catch (t: Throwable) {
            payload = errorPayload(task, uid, context, "user service failure: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            tasks.remove(task.taskId)
        }
        writeResult(writeSide, payload)
    }

    private fun errorPayload(task: RunningTask, uid: Int, context: String, message: String): NativeWireResult =
        NativeWireResult(
            taskId = task.taskId,
            uid = uid,
            selinuxContext = context,
            exitCode = -1,
            stdout = "",
            stderr = "",
            timedOut = false,
            truncated = false,
            cancelled = task.cancelled.get(),
            error = message,
        )

    private fun writeResult(writeSide: ParcelFileDescriptor, payload: NativeWireResult) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                output.write(WorkspaceCommandWire.encode(payload))
                output.flush()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to write result for ${payload.taskId}", t)
            runCatching { writeSide.close() }
        }
    }

    private fun failFast(message: String): ParcelFileDescriptor? {
        // No pipe: the client sees a null descriptor and turns it into a clear error instead of
        // a fake success. Kept as a separate method so the AIDL contract stays ParcelFileDescriptor.
        Log.w(TAG, "rejecting startCommand: $message")
        lastValidationError.set(message)
        return null
    }

    private fun validateOrNull(command: String, cwd: String, stdin: ByteArray?, timeoutMillis: Long): String? {
        if (command.isBlank()) return "command must not be empty"
        if (command.length > WorkspaceCommandLimits.MAX_COMMAND_CHARS) return "command exceeds ${WorkspaceCommandLimits.MAX_COMMAND_CHARS} chars"
        if (command.contains('\u0000')) return "command contains a NUL byte"
        if (stdin != null && stdin.size > WorkspaceCommandLimits.MAX_STDIN_BYTES) return "stdin exceeds ${WorkspaceCommandLimits.MAX_STDIN_BYTES} bytes"
        if (timeoutMillis !in WorkspaceCommandLimits.MIN_TIMEOUT_MILLIS..WorkspaceCommandLimits.MAX_TIMEOUT_MILLIS) {
            return "timeoutMillis out of range"
        }
        WorkspaceShellCommand.validateCwd(cwd)?.let { return it }
        return null
    }

    /** Runs a harmless probe command and returns stdout; never runs the user command. */
    private fun runProbe(command: String): String {
        val outcome = WorkspaceNativeCommandRunner.run(
            NativeCommandRequest(
                argv = listOf(WorkspaceShellCommand.systemShell(), "-c", command),
                cwd = WorkspaceCommandLimits.DEFAULT_CWD,
                timeoutMillis = 5_000,
            ),
        )
        return outcome.stdoutText()
    }

    private companion object {
        const val TAG = "WorkspaceShellSvc"
        val lastValidationError = java.util.concurrent.atomic.AtomicReference<String?>(null)

        @Suppress("unused")
        fun lastError(): String? = lastValidationError.get()
    }
}
