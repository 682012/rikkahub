package me.rerere.rikkahub.data.workspace

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process as AndroidProcess
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.BuildConfig
import me.rerere.workspace.WorkspaceCommandResult
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Executes workspace commands on the Android host with the privilege the user explicitly chose.
 *
 * Contract highlights:
 *  - [requestAuthorization] must only be called from a user action (button/toggle): it is the only
 *    entry point that may raise the Shizuku permission dialog or the `su` prompt.
 *  - [execute] never downgrades. A requested SHIZUKU/ROOT channel that is unavailable, denied or
 *    dead fails with [WorkspacePrivilegeException] instead of silently running as the app UID.
 *  - Every command is guarded with `id -u` before the user command and the reported
 *    [WorkspaceIdentity] is the real UID (0 stays 0, 2000 stays 2000 — never relabelled).
 *  - The Shizuku user service is `daemon(false)` and is unbound with `remove = true` as soon as
 *    this manager has no active command, so no privileged process lingers in the background.
 *  - cwd is an absolute Android host path ("/" by default). The app-private /workspace root is not
 *    addressable from the shell UID, so it is never used as an implicit cwd.
 */
class WorkspacePrivilegeManager(private val context: Context) {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = WorkspaceTaskRegistry()
    private val taskSeq = AtomicLong()
    private val bindMutex = Mutex()

    private var shizukuService: IWorkspaceShellService? = null
    private var shizukuBindCount = 0
    private var shizukuConnection: ServiceConnection? = null

    @Volatile
    private var appIdentity: WorkspaceIdentity? = null

    @Volatile
    private var rootIdentity: WorkspaceIdentity? = null

    // ---------------------------------------------------------------- authorization

    /**
     * Runs harmless identity probes for [mode] and returns the real identity of that channel.
     * Only call this from a user-initiated action (it may show the Shizuku/`su` prompt).
     */
    suspend fun requestAuthorization(mode: WorkspacePrivilegeMode): WorkspaceIdentity = withContext(Dispatchers.IO) {
        when (mode) {
            WorkspacePrivilegeMode.APP -> localIdentity()
            WorkspacePrivilegeMode.SHIZUKU -> {
                ensureShizukuPermission(allowPrompt = true)
                withShizukuService { service -> probeShizuku(service) }
            }
            WorkspacePrivilegeMode.ROOT -> kotlinx.coroutines.runInterruptible { probeRoot() }
        }
    }

    // ---------------------------------------------------------------- execution

    suspend fun execute(
        mode: WorkspacePrivilegeMode,
        command: String,
        cwd: String = WorkspaceCommandLimits.DEFAULT_CWD,
        timeoutMillis: Long = WorkspaceCommandLimits.DEFAULT_TIMEOUT_MILLIS,
        stdin: ByteArray? = null,
        ownerKey: String = "",
    ): WorkspaceAndroidCommandResult = withContext(Dispatchers.IO) {
        WorkspaceCommandRequestValidator.validate(command, cwd, stdin, timeoutMillis)
        when (mode) {
            WorkspacePrivilegeMode.APP -> kotlinx.coroutines.runInterruptible { executeLocalApp(command, cwd, timeoutMillis, stdin, ownerKey) }
            WorkspacePrivilegeMode.SHIZUKU -> executeShizuku(command, cwd, timeoutMillis, stdin, ownerKey)
            WorkspacePrivilegeMode.ROOT -> kotlinx.coroutines.runInterruptible { executeRoot(command, cwd, timeoutMillis, stdin, ownerKey) }
        }
    }

    /**
     * Cancels every command of [ownerKey] without blocking the calling thread (never on the Binder
     * thread). An empty [ownerKey] cancels all commands owned by this manager, which is what the
     * settings screen should call when the privilege mode is removed.
     */
    fun cancelOwner(ownerKey: String) {
        for (entry in registry.revokeOwner(ownerKey)) {
            entry.cancelToken.cancel()
            entry.cancelRemote.invoke()
            entry.control?.let { control -> io.launch { control.terminate() } }
            registry.remove(entry.taskId)
        }
    }

    /** Cancels everything and releases the Shizuku binding. Safe to call from a ViewModel `onCleared`. */
    fun close() {
        cancelOwner("")
        io.launch { releaseShizukuIfIdle(force = true) }
    }

    /** Structured, honest status for the settings UI. No privilege is reported as granted unless it is. */
    fun shizukuStatus(): String {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return "status=NOT_RUNNING; api=-1; uid=unknown; userServiceBound=false; fallback=none"
        val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val uid = if (granted) runCatching { Shizuku.getUid() }.getOrNull() else null
        return buildString {
            append("status=").append(if (granted) "READY" else "PERMISSION_DENIED")
            append("; api=").append(version)
            append("; uid=").append(uid ?: "unknown")
            append("; userServiceBound=").append(shizukuService != null)
            append("; fallback=none")
        }
    }

    // ---------------------------------------------------------------- APP channel

    private fun executeLocalApp(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
        ownerKey: String,
    ): WorkspaceAndroidCommandResult {
        val identity = localIdentity()
        val taskId = nextTaskId()
        val token = NativeCancelToken()
        val entry = WorkspaceTaskRegistry.Entry(taskId, ownerKey, token) { /* local process only */ }
        registry.register(entry)
        try {
            val outcome = WorkspaceNativeCommandRunner.run(
                NativeCommandRequest(
                    argv = listOf(WorkspaceShellCommand.systemShell(), "-c", command),
                    cwd = cwd,
                    timeoutMillis = timeoutMillis,
                    stdin = stdin,
                    environment = mapOf(WorkspaceProcessReaper.MARKER_ENV to taskId),
                    marker = taskId,
                    cancelToken = token,
                ),
                onStarted = { registry.attachControl(taskId, it) },
            )
            return toResult(identity, outcome)
        } finally {
            registry.remove(taskId)
        }
    }

    private fun localIdentity(): WorkspaceIdentity {
        appIdentity?.let { return it }
        val context = runCatching {
            WorkspaceNativeCommandRunner.run(
                NativeCommandRequest(
                    argv = listOf(WorkspaceShellCommand.systemShell(), "-c", "id -Z"),
                    timeoutMillis = 3_000,
                ),
            ).stdoutText()
        }.getOrDefault("")
        val identity = WorkspaceIdentity(
            mode = WorkspacePrivilegeMode.APP,
            uid = AndroidProcess.myUid(),
            selinuxContext = WorkspaceIdentityProbe.parseSelinuxContext(context),
        )
        appIdentity = identity
        return identity
    }

    // ---------------------------------------------------------------- SHIZUKU channel

    private suspend fun executeShizuku(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
        ownerKey: String,
    ): WorkspaceAndroidCommandResult = withShizukuService { service ->
        val expectedUid = shizukuUid()
        val taskId = nextTaskId()
        val entry = WorkspaceTaskRegistry.Entry(taskId, ownerKey, NativeCancelToken()) {
            io.launch { runCatching { service.cancel(taskId) } }
        }
        registry.register(entry)
        try {
            val descriptor = try {
                service.startCommand(taskId, ownerKey, command, cwd, timeoutMillis, stdin, expectedUid)
            } catch (e: RemoteException) {
                throw WorkspacePrivilegeException(
                    WorkspacePrivilegeFailure.SHIZUKU_DISCONNECTED,
                    "The Shizuku user service died before the command started: ${e.message}",
                    e,
                )
            } ?: throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.INVALID_REQUEST,
                "The Shizuku user service rejected the command (invalid command, cwd, stdin or timeout)",
            )

            val payload = WorkspaceCommandWire.decode(awaitPipe(descriptor, entry, timeoutMillis))
            payload.error?.let { message ->
                val failure = when {
                    message.startsWith("identity guard failed") -> WorkspacePrivilegeFailure.IDENTITY_MISMATCH
                    else -> WorkspacePrivilegeFailure.INTERNAL_ERROR
                }
                throw WorkspacePrivilegeException(failure, message)
            }
            val identity = WorkspaceIdentity(WorkspacePrivilegeMode.SHIZUKU, payload.uid, payload.selinuxContext)
            WorkspaceAndroidCommandResult(
                identity = identity,
                result = WorkspaceCommandResult(
                    exitCode = payload.exitCode,
                    stdout = payload.stdout,
                    stderr = payload.stderr,
                    timedOut = payload.timedOut,
                    truncated = payload.truncated,
                ),
            )
        } finally {
            registry.remove(taskId)
        }
    }

    private suspend fun <T> withShizukuService(block: suspend (IWorkspaceShellService) -> T): T {
        val service = acquireShizuku()
        try {
            return block(service)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { releaseShizukuIfIdle(force = false) }
        }
    }

    private suspend fun acquireShizuku(): IWorkspaceShellService = bindMutex.withLock {
        shizukuService?.let { existing ->
            if (existing.asBinder().pingBinder()) {
                shizukuBindCount += 1
                return existing
            }
            shizukuService = null
        }
        ensureShizukuPermission()
        val bound = bindUserService()
        shizukuService = bound
        shizukuBindCount = 1
        bound
    }

    private suspend fun releaseShizukuIfIdle(force: Boolean) {
        bindMutex.withLock {
            if (!force) {
                shizukuBindCount = (shizukuBindCount - 1).coerceAtLeast(0)
                if (shizukuBindCount > 0) return
            }
            shizukuBindCount = 0
            shizukuService = null
            val connection = shizukuConnection
            shizukuConnection = null
            if (connection != null) {
                // Idle: never keep a daemon-style privileged user service running in background.
                runCatching { Shizuku.unbindUserService(userServiceArgs(), connection, true) }
            }
        }
    }

    private suspend fun ensureShizukuPermission(allowPrompt: Boolean = false) {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.SHIZUKU_NOT_RUNNING,
                "Shizuku is not running. Start Shizuku/Sui first; commands are not downgraded to the app UID.",
            )
        }
        val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        if (version in 0..9) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.SHIZUKU_UNSUPPORTED,
                "Shizuku API 10+ is required to bind a user service (reported v$version).",
            )
        }
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        if (granted) return
        if (!allowPrompt) throw WorkspacePrivilegeException(
            WorkspacePrivilegeFailure.SHIZUKU_PERMISSION_DENIED,
            "Shizuku access was revoked. Open workspace settings and explicitly authorize it again.",
        )
        requestShizukuPermission()
    }

    /** Must only be reached from a user action: this is what opens the Shizuku permission dialog. */
    private suspend fun requestShizukuPermission() {
        val granted = withTimeoutOrNull(PERMISSION_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                lateinit var listener: Shizuku.OnRequestPermissionResultListener
                listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                    if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
                    Shizuku.removeRequestPermissionResultListener(listener)
                    if (!continuation.isActive) return@OnRequestPermissionResultListener
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            WorkspacePrivilegeException(
                                WorkspacePrivilegeFailure.SHIZUKU_PERMISSION_DENIED,
                                "Shizuku permission was denied for this app; commands are not downgraded to the app UID.",
                            ),
                        )
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        }
        if (granted == null) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.SHIZUKU_PERMISSION_DENIED,
                "No answer to the Shizuku permission request within ${PERMISSION_TIMEOUT_MILLIS / 1000}s; approve it in the Shizuku app and retry.",
            )
        }
    }

    private suspend fun bindUserService(): IWorkspaceShellService = try {
        withTimeout(BIND_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val args = userServiceArgs()
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder == null || !binder.pingBinder()) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    WorkspacePrivilegeException(
                                        WorkspacePrivilegeFailure.SHIZUKU_DISCONNECTED,
                                        "Shizuku returned an invalid user service binder",
                                    ),
                                )
                            }
                            return
                        }
                        shizukuConnection = this
                        if (continuation.isActive) continuation.resume(IWorkspaceShellService.Stub.asInterface(binder))
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        shizukuService = null
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                WorkspacePrivilegeException(
                                    WorkspacePrivilegeFailure.SHIZUKU_DISCONNECTED,
                                    "The Shizuku user service was disconnected",
                                ),
                            )
                        }
                    }
                }
                try {
                    Shizuku.bindUserService(args, connection)
                } catch (t: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            WorkspacePrivilegeException(
                                WorkspacePrivilegeFailure.SHIZUKU_DISCONNECTED,
                                "Failed to bind the Shizuku user service: ${t.message ?: t.javaClass.simpleName}",
                                t,
                            ),
                        )
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw WorkspacePrivilegeException(
            WorkspacePrivilegeFailure.SHIZUKU_SERVICE_TIMEOUT,
            "Timed out after ${BIND_TIMEOUT_MILLIS}ms waiting for the Shizuku user service",
        )
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, WorkspaceShizukuService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("workspace_shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
        .tag("workspace-shell")

    private fun shizukuUid(): Int {
        val uid = runCatching { Shizuku.getUid() }.getOrElse { t ->
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.SHIZUKU_PERMISSION_DENIED,
                "Could not read the Shizuku server UID: ${t.message ?: t.javaClass.simpleName}",
                t,
            )
        }
        if (uid < 0) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.SHIZUKU_PERMISSION_DENIED,
                "Shizuku did not report a usable UID (got $uid); grant the permission again.",
            )
        }
        return uid
    }

    private fun probeShizuku(service: IWorkspaceShellService): WorkspaceIdentity {
        val raw = try {
            service.probe()
        } catch (e: RemoteException) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.SHIZUKU_DISCONNECTED,
                "The Shizuku user service died during the identity probe: ${e.message}",
                e,
            )
        }
        val fields = raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        val uid = fields["uid"]?.toIntOrNull()
            ?: throw WorkspacePrivilegeException(WorkspacePrivilegeFailure.INTERNAL_ERROR, "The Shizuku user service did not report a UID")
        val realUid = fields["realUid"]?.toIntOrNull()
        if (realUid != null && realUid != uid) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.IDENTITY_MISMATCH,
                "Shizuku reported uid $uid but the service process runs as $realUid; a faked root (PRoot) is not accepted.",
            )
        }
        val serverUid = runCatching { Shizuku.getUid() }.getOrNull()
        if (serverUid != null && serverUid >= 0 && serverUid != uid) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.IDENTITY_MISMATCH,
                "Shizuku server UID is $serverUid but the user service reports $uid; refusing to guess the identity.",
            )
        }
        return WorkspaceIdentity(
            mode = WorkspacePrivilegeMode.SHIZUKU,
            uid = uid,
            selinuxContext = fields["context"].orEmpty(),
        )
    }

    /** Reads the pipe result off the main thread; cancellation closes the pipe and calls cancel(). */
    private suspend fun awaitPipe(
        descriptor: ParcelFileDescriptor,
        entry: WorkspaceTaskRegistry.Entry,
        timeoutMillis: Long,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val watchdogFired = AtomicBoolean(false)
        val watchdog = if (timeoutMillis > 0) io.launch {
            delay(timeoutMillis.coerceAtMost(Long.MAX_VALUE - WATCHDOG_GRACE_MILLIS) + WATCHDOG_GRACE_MILLIS)
            watchdogFired.set(true)
            entry.cancelRemote.invoke()
            runCatching { descriptor.close() }
        } else null
        continuation.invokeOnCancellation {
            watchdog?.cancel()
            entry.cancelRemote.invoke()
            runCatching { descriptor.close() }
        }
        io.launch {
            try {
                val bytes = readPipe(descriptor)
                if (continuation.isActive) continuation.resume(bytes)
            } catch (t: Throwable) {
                if (continuation.isActive) {
                    val failure = if (watchdogFired.get()) {
                        WorkspacePrivilegeException(
                            WorkspacePrivilegeFailure.SHIZUKU_SERVICE_TIMEOUT,
                            "The Shizuku user service did not answer within ${(timeoutMillis + WATCHDOG_GRACE_MILLIS) / 1000}s; the command was cancelled.",
                            t,
                        )
                    } else {
                        WorkspacePrivilegeException(
                            WorkspacePrivilegeFailure.INTERNAL_ERROR,
                            "Failed to read the command result from the Shizuku user service: ${t.message ?: t.javaClass.simpleName}",
                            t,
                        )
                    }
                    continuation.resumeWithException(failure)
                }
            } finally {
                watchdog?.cancel()
                runCatching { descriptor.close() }
            }
        }
    }

    private fun readPipe(descriptor: ParcelFileDescriptor): ByteArray {
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                if (output.size() > WorkspaceCommandLimits.MAX_WIRE_BYTES) {
                    throw WorkspacePrivilegeException(
                        WorkspacePrivilegeFailure.INTERNAL_ERROR,
                        "The Shizuku response exceeded ${WorkspaceCommandLimits.MAX_WIRE_BYTES} bytes",
                    )
                }
            }
            return output.toByteArray()
        }
    }

    // ---------------------------------------------------------------- ROOT channel

    private fun probeRoot(): WorkspaceIdentity {
        val su = WorkspaceSuLocator.locate() ?: throw rootUnavailable()
        val probe = runSu(su, ROOT_PROBE_SCRIPT, ROOT_PROBE_TIMEOUT_MILLIS)
        if (probe.timedOut) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.ROOT_UNAVAILABLE,
                "`su` did not answer within ${ROOT_PROBE_TIMEOUT_MILLIS / 1000}s; approve the root prompt and retry.",
            )
        }
        if (probe.startError != null) {
            throw WorkspacePrivilegeException(WorkspacePrivilegeFailure.ROOT_UNAVAILABLE, probe.startError)
        }
        val parsed = parseRootProbe(probe.stdoutText())
        val uid = parsed.uid ?: throw WorkspacePrivilegeException(
            WorkspacePrivilegeFailure.IDENTITY_MISMATCH,
            "`su` did not report a usable UID (stdout: ${probe.stdoutText().take(200).trim()}, stderr: ${probe.stderrText().take(200).trim()})",
        )
        if (parsed.realUid != null && parsed.realUid != uid) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.IDENTITY_MISMATCH,
                "`su` reported uid $uid but /proc/self/status reports ${parsed.realUid}; refusing a faked root (PRoot/fakeroot).",
            )
        }
        if (uid != 0) throw WorkspacePrivilegeException(
            WorkspacePrivilegeFailure.IDENTITY_MISMATCH, "su did not grant root (actual UID $uid)",
        )
        val identity = WorkspaceIdentity(WorkspacePrivilegeMode.ROOT, uid, parsed.context)
        rootIdentity = identity
        return identity
    }

    private fun executeRoot(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
        ownerKey: String,
    ): WorkspaceAndroidCommandResult {
        checkNotNull(rootIdentity) { "Root execution requires explicit authorization in workspace settings after app restart" }
        val su = WorkspaceSuLocator.locate() ?: throw rootUnavailable()

        // Identity guard before the user command: a root manager / PRoot that lies here is refused.
        val guard = runSu(su, "id -u", ROOT_PROBE_TIMEOUT_MILLIS)
        val observed = WorkspaceIdentityProbe.parseUid(guard.stdoutText())
            ?: throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.IDENTITY_MISMATCH,
                "identity guard failed for root: `su -c id -u` returned no UID",
            )
        rootIdentity?.let { cached ->
            WorkspaceIdentityProbe.verifyUid(cached.uid, observed, "root")?.let {
                throw WorkspacePrivilegeException(WorkspacePrivilegeFailure.IDENTITY_MISMATCH, it)
            }
        }

        val taskId = nextTaskId()
        val token = NativeCancelToken()
        val entry = WorkspaceTaskRegistry.Entry(taskId, ownerKey, token) { /* local su process only */ }
        registry.register(entry)
        try {
            val outcome = runSu(
                su = su,
                script = WorkspaceShellCommand.rootScript(command, cwd),
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                marker = taskId,
                token = token,
                onStarted = { registry.attachControl(taskId, it) },
            )
            if (outcome.startError != null) {
                throw WorkspacePrivilegeException(WorkspacePrivilegeFailure.ROOT_UNAVAILABLE, outcome.startError)
            }
            val identity = WorkspaceIdentity(
                mode = WorkspacePrivilegeMode.ROOT,
                uid = observed,
                selinuxContext = rootIdentity?.selinuxContext.orEmpty(),
            )
            return toResult(identity, outcome)
        } finally {
            registry.remove(taskId)
        }
    }

    private fun runSu(
        su: String,
        script: String,
        timeoutMillis: Long,
        stdin: ByteArray? = null,
        marker: String = "",
        token: NativeCancelToken = NativeCancelToken(),
        onStarted: (NativeProcessControl) -> Unit = {},
    ): NativeCommandOutcome = WorkspaceNativeCommandRunner.run(
        NativeCommandRequest(
            argv = listOf(su, "-c", script),
            cwd = WorkspaceCommandLimits.DEFAULT_CWD,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
            environment = if (marker.isEmpty()) emptyMap() else mapOf(WorkspaceProcessReaper.MARKER_ENV to marker),
            marker = marker,
            cancelToken = token,
        ),
        onStarted = onStarted,
    )

    private fun parseRootProbe(raw: String): RootProbe {
        var uid: Int? = null
        var realUid: Int? = null
        var context = ""
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("WSACCESS-UID=") -> uid = trimmed.substringAfter('=').toIntOrNull()
                trimmed.startsWith("WSACCESS-REAL=") -> realUid = trimmed.substringAfter('=').toIntOrNull()
                trimmed.startsWith("WSACCESS-CTX=") ->
                    context = WorkspaceIdentityProbe.parseSelinuxContext(trimmed.substringAfter('='))
            }
        }
        return RootProbe(uid, realUid, context)
    }

    private fun rootUnavailable() = WorkspacePrivilegeException(
        WorkspacePrivilegeFailure.ROOT_UNAVAILABLE,
        "No installed su binary was found on this device. This app ships no su and does not fall back to the app or shell UID.",
    )

    // ---------------------------------------------------------------- shared helpers

    private fun toResult(identity: WorkspaceIdentity, outcome: NativeCommandOutcome): WorkspaceAndroidCommandResult {
        if (outcome.startError != null) {
            throw WorkspacePrivilegeException(WorkspacePrivilegeFailure.PROCESS_START_FAILED, outcome.startError)
        }
        return WorkspaceAndroidCommandResult(
            identity = identity,
            result = WorkspaceCommandResult(
                exitCode = outcome.exitCode,
                stdout = outcome.stdoutText(),
                stderr = outcome.stderrText(),
                timedOut = outcome.timedOut,
                truncated = outcome.truncated,
            ),
        )
    }

    private fun nextTaskId(): String = "ws-${java.util.UUID.randomUUID()}"

    private data class RootProbe(val uid: Int?, val realUid: Int?, val context: String)

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 42117
        const val BIND_TIMEOUT_MILLIS = 8_000L
        const val PERMISSION_TIMEOUT_MILLIS = 60_000L
        const val WATCHDOG_GRACE_MILLIS = 5_000L
        const val ROOT_PROBE_TIMEOUT_MILLIS = 20_000L

        // Markers are echoed so the probe can never be confused with user command output.
        val ROOT_PROBE_SCRIPT = """
            echo WSACCESS-UID=$(id -u)
            echo WSACCESS-REAL=$(grep '^Uid:' /proc/self/status 2>/dev/null | tr -s ' \t' ' ' | cut -d' ' -f2)
            echo WSACCESS-CTX=$(id -Z 2>/dev/null)
        """.trimIndent()
    }
}
