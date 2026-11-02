package me.rerere.rikkahub.data.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared limits for every Android host execution channel (app UID / Shizuku / su).
 *
 * Output is bounded per stream so that a chatty command cannot blow up Binder (1 MiB per
 * transaction) or the app heap. The Shizuku channel ships the payload through a
 * [android.os.ParcelFileDescriptor] pipe, and still keeps the same bound.
 */
object WorkspaceCommandLimits {
    const val MAX_OUTPUT_BYTES_PER_STREAM: Int = 128 * 1024
    const val MAX_COMMAND_CHARS: Int = 32 * 1024
    const val MAX_STDIN_BYTES: Int = 256 * 1024
    const val MIN_TIMEOUT_MILLIS: Long = 1_000L
    const val MAX_TIMEOUT_MILLIS: Long = 600_000L
    const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000L
    const val MAX_WIRE_BYTES: Int = 1024 * 1024
    const val DEFAULT_CWD: String = "/"
}

/** Stable, testable failure reasons. Never used to silently fall back to another privilege. */
enum class WorkspacePrivilegeFailure {
    SHIZUKU_NOT_RUNNING,
    SHIZUKU_UNSUPPORTED,
    SHIZUKU_PERMISSION_DENIED,
    SHIZUKU_DISCONNECTED,
    SHIZUKU_SERVICE_TIMEOUT,
    ROOT_UNAVAILABLE,
    IDENTITY_MISMATCH,
    INVALID_REQUEST,
    PROCESS_START_FAILED,
    INTERNAL_ERROR,
}

class WorkspacePrivilegeException(
    val failure: WorkspacePrivilegeFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class ShizukuAvailability(
    val binderAlive: Boolean,
    val permissionGranted: Boolean,
    val apiVersion: Int = -1,
)

sealed interface WorkspaceRouteDecision {
    data class Allowed(val mode: WorkspacePrivilegeMode) : WorkspaceRouteDecision
    data class Rejected(
        val failure: WorkspacePrivilegeFailure,
        val message: String,
    ) : WorkspaceRouteDecision
}

/**
 * Pure routing policy: a requested mode either runs exactly as requested or fails loudly.
 * There is deliberately no branch that maps SHIZUKU/ROOT onto APP.
 */
object WorkspaceCommandPolicy {
    fun decide(
        mode: WorkspacePrivilegeMode,
        shizuku: ShizukuAvailability = ShizukuAvailability(false, false),
        suAvailable: Boolean = false,
    ): WorkspaceRouteDecision = when (mode) {
        WorkspacePrivilegeMode.APP -> WorkspaceRouteDecision.Allowed(WorkspacePrivilegeMode.APP)

        WorkspacePrivilegeMode.SHIZUKU -> when {
            !shizuku.binderAlive -> WorkspaceRouteDecision.Rejected(
                WorkspacePrivilegeFailure.SHIZUKU_NOT_RUNNING,
                "Shizuku is not running. Start Shizuku/Sui first; commands are not downgraded to the app UID.",
            )

            !shizuku.permissionGranted -> WorkspaceRouteDecision.Rejected(
                WorkspacePrivilegeFailure.SHIZUKU_PERMISSION_DENIED,
                "Shizuku permission is not granted for this app. Open the privilege settings and approve the request.",
            )

            shizuku.apiVersion in 0..9 -> WorkspaceRouteDecision.Rejected(
                WorkspacePrivilegeFailure.SHIZUKU_UNSUPPORTED,
                "Shizuku API 10 or newer is required to bind a user service (reported v${shizuku.apiVersion}).",
            )

            else -> WorkspaceRouteDecision.Allowed(WorkspacePrivilegeMode.SHIZUKU)
        }

        WorkspacePrivilegeMode.ROOT -> if (suAvailable) {
            WorkspaceRouteDecision.Allowed(WorkspacePrivilegeMode.ROOT)
        } else {
            WorkspaceRouteDecision.Rejected(
                WorkspacePrivilegeFailure.ROOT_UNAVAILABLE,
                "No installed su binary was found. This app ships no su and does not fall back to the app or shell UID.",
            )
        }
    }
}

/** Quoting / argv construction helpers, kept free of Android APIs so they are unit-testable. */
object WorkspaceShellCommand {
    fun systemShell(): String = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"

    /** POSIX single-quote escaping: `it's` -> `'it'\''s'`. */
    fun singleQuote(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"

    /** @return null when the cwd is usable, otherwise a user-facing reason. */
    fun validateCwd(cwd: String): String? = when {
        cwd.isEmpty() -> "cwd must not be empty; use an absolute Android host path such as \"/\""
        cwd.contains('\u0000') -> "cwd contains a NUL byte"
        !cwd.startsWith("/") ->
            "cwd must be an absolute Android host path (got \"$cwd\"); relative or /workspace-relative paths are not supported"

        cwd.split('/').any { it == ".." } -> "cwd cannot contain .."
        else -> null
    }

    /**
     * Script handed to `su -c`. The cwd is quoted and the shell exits before the user command
     * when the directory is unavailable, so a command never silently runs in another directory.
     */
    fun rootScript(command: String, cwd: String): String =
        "[ \"$(id -u)\" = 0 ] || exit 126\n" +
            "cd " + singleQuote(cwd) + " || exit 125\n" + command
}

internal class BoundedByteSink(private val limit: Int) {
    private val effectiveLimit = limit.coerceAtLeast(1)
    private val buffer = ByteArrayOutputStream(minOf(effectiveLimit, 8 * 1024))
    private var truncatedFlag = false

    @Volatile
    var totalBytes: Long = 0L
        private set

    @Synchronized
    fun append(data: ByteArray, length: Int) {
        if (length <= 0) return
        totalBytes += length
        val room = effectiveLimit - buffer.size()
        if (room <= 0) {
            truncatedFlag = true
            return
        }
        val take = minOf(room, length)
        buffer.write(data, 0, take)
        if (take < length) truncatedFlag = true
    }

    @Synchronized
    fun snapshot(): ByteArray = buffer.toByteArray()

    @Synchronized
    fun truncated(): Boolean = truncatedFlag
}

/**
 * Continuously drains one stream into a bounded sink. Reading goes on after the limit is hit
 * (bytes are dropped) so the child never blocks on a full pipe while we are reporting truncation.
 */
private class StreamDrain(
    private val input: InputStream,
    private val sink: BoundedByteSink,
    name: String,
) {
    private val done = CountDownLatch(1)
    private val thread = Thread({
        try {
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.append(buffer, read)
            }
        } catch (_: IOException) {
            // Stream closed by us (timeout/cancel) or by process death: what we got is what we keep.
        } catch (_: Throwable) {
            // Never let a drain thread escape into the app process.
        } finally {
            done.countDown()
        }
    }, name).apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    fun await(millis: Long): Boolean = done.await(millis, TimeUnit.MILLISECONDS)

    fun close() {
        runCatching { input.close() }
    }
}

class NativeCancelToken {
    private val flag = AtomicBoolean(false)
    val isCancelled: Boolean get() = flag.get()

    fun cancel() {
        flag.set(true)
    }
}

/**
 * Handle for a command that has already been spawned, so `cancelOwner` can kill it.
 * Termination is best effort and never a blanket pkill: when a unique marker is supplied,
 * only processes carrying that marker are reaped.
 */
class NativeProcessControl internal constructor(
    val process: Process,
    private val marker: String,
    private val killCommand: String,
    private val privilegedCleanup: (() -> Unit)? = null,
) {
    fun terminate() {
        runCatching { process.destroy() }
        runCatching {
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        reapMarkedProcesses()
    }

    fun reapMarkedProcesses() {
        if (marker.isEmpty()) return
        val pids = WorkspaceProcessReaper.findMarkedPids(marker)
        if (pids.isNotEmpty()) WorkspaceProcessReaper.killAll(pids, killCommand)
        privilegedCleanup?.invoke()
    }
}

data class NativeCommandRequest(
    val argv: List<String>,
    val cwd: String = WorkspaceCommandLimits.DEFAULT_CWD,
    val timeoutMillis: Long = WorkspaceCommandLimits.DEFAULT_TIMEOUT_MILLIS,
    val stdin: ByteArray? = null,
    val environment: Map<String, String> = emptyMap(),
    val marker: String = "",
    val outputLimitBytes: Int = WorkspaceCommandLimits.MAX_OUTPUT_BYTES_PER_STREAM,
    val killCommand: String = WorkspaceProcessReaper.DEFAULT_KILL,
    val cancelToken: NativeCancelToken = NativeCancelToken(),
    val privilegedCleanup: (() -> Unit)? = null,
)

data class NativeCommandOutcome(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val cancelled: Boolean = false,
    val startError: String? = null,
) {
    fun stdoutText(): String = stdout.toString(Charsets.UTF_8)
    fun stderrText(): String = stderr.toString(Charsets.UTF_8)
}

/**
 * Single, tested implementation of "run a host command with timeout, bounded output and
 * cancellation". Used on the app side and inside the Shizuku user service process.
 *
 * Ordering matters: we `waitFor(25ms)` in a loop instead of draining until EOF, so a command
 * that keeps the pipe open forever still hits the timeout; the drain threads run concurrently
 * from the start, so normal output is captured before the process is killed.
 */
object WorkspaceNativeCommandRunner {
    fun run(
        request: NativeCommandRequest,
        onStarted: (NativeProcessControl) -> Unit = {},
    ): NativeCommandOutcome {
        if (request.cancelToken.isCancelled) {
            return NativeCommandOutcome(
                exitCode = -1,
                stdout = ByteArray(0),
                stderr = ByteArray(0),
                cancelled = true,
            )
        }

        val timeout = request.timeoutMillis.coerceAtLeast(0L)
        val builder = ProcessBuilder(request.argv).directory(File(request.cwd.ifEmpty { WorkspaceCommandLimits.DEFAULT_CWD }))
        request.environment.forEach { (key, value) -> builder.environment()[key] = value }

        val process = try {
            builder.start()
        } catch (t: Throwable) {
            return NativeCommandOutcome(
                exitCode = -127,
                stdout = ByteArray(0),
                stderr = (t.message ?: t.javaClass.simpleName).toByteArray(),
                startError = "failed to start ${request.argv.firstOrNull() ?: "<none>"}: ${t.message ?: t.javaClass.simpleName}",
            )
        }

        val control = NativeProcessControl(process, request.marker, request.killCommand, request.privilegedCleanup)
        onStarted(control)
        if (request.cancelToken.isCancelled) control.terminate()

        val stdoutSink = BoundedByteSink(request.outputLimitBytes)
        val stderrSink = BoundedByteSink(request.outputLimitBytes)
        val stdoutDrain = StreamDrain(process.inputStream, stdoutSink, "ws-stdout")
        val stderrDrain = StreamDrain(process.errorStream, stderrSink, "ws-stderr")
        stdoutDrain.start()
        stderrDrain.start()

        val stdin = request.stdin
        if (stdin != null && stdin.isNotEmpty()) {
            Thread({
                try {
                    process.outputStream.use {
                        it.write(stdin)
                        it.flush()
                    }
                } catch (_: IOException) {
                    // Process already gone: the exit code tells the real story.
                }
            }, "ws-stdin").apply { isDaemon = true }.start()
        } else {
            runCatching { process.outputStream.close() }
        }

        var timedOut = false
        var cancelled = false
        val startedAt = System.nanoTime()
        var interrupted = false
        try {
        while (true) {
            if (process.waitFor(25, TimeUnit.MILLISECONDS)) break
            if (request.cancelToken.isCancelled) {
                cancelled = true
                control.terminate()
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                break
            }
            if (timeout > 0 && (System.nanoTime() - startedAt) / 1_000_000L >= timeout) {
                timedOut = true
                control.terminate()
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                break
            }
        }

        } catch (_: InterruptedException) {
            interrupted = true
            cancelled = true
            request.cancelToken.cancel()
            control.terminate()
        }
        // A host command has no daemon lifetime. Reap only this command's marked descendants.
        control.reapMarkedProcesses()

        // Give readers a short window to hit EOF, then force the pipes closed so we never hang
        // on a grandchild that inherited them.
        try {
            stdoutDrain.await(2_000)
            stderrDrain.await(2_000)
        } catch (_: InterruptedException) { interrupted = true; control.terminate() }
        finally {
            stdoutDrain.close()
            stderrDrain.close()
            runCatching { process.outputStream.close() }
        }
        if (interrupted) throw InterruptedException("Android command was cancelled")

        val exitCode = try {
            process.exitValue()
        } catch (_: IllegalThreadStateException) {
            runCatching { process.destroyForcibly() }
            -1
        }

        return NativeCommandOutcome(
            exitCode = exitCode,
            stdout = stdoutSink.snapshot(),
            stderr = stderrSink.snapshot(),
            timedOut = timedOut,
            truncated = stdoutSink.truncated() || stderrSink.truncated(),
            cancelled = cancelled,
        )
    }
}

/**
 * Best-effort, narrowly scoped process-tree cleanup. We only touch PIDs whose environment
 * carries our per-command marker; there is no `pkill`/pattern matching.
 *
 * Limits we do not hide: /proc/<pid>/environ is only readable for processes owned by the same
 * UID (or root), so a root child that survives a dead `su` intermediary cannot always be found
 * from the app process. Root mode does the same marker scan inside the `su` shell where possible.
 */
object WorkspaceProcessReaper {
    const val DEFAULT_KILL: String = "/system/bin/kill"
    const val MARKER_ENV: String = "WORKSPACE_ACCESS_TASK"

    fun findMarkedPids(marker: String, procRoot: File = File("/proc")): List<Int> {
        if (marker.isEmpty()) return emptyList()
        val needle = "$MARKER_ENV=$marker\u0000".toByteArray(Charsets.UTF_8)
        val entries = procRoot.listFiles() ?: return emptyList()
        val found = ArrayList<Int>()
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            val environ = File(entry, "environ")
            // /proc/<pid>/environ reports st_size = 0, so read it through a bounded stream.
            val bytes = try {
                environ.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    while (total < buffer.size) {
                        val read = input.read(buffer, total, buffer.size - total)
                        if (read < 0) break
                        total += read
                    }
                    buffer.copyOf(total)
                }
            } catch (_: Throwable) {
                null
            } ?: continue
            if (contains(bytes, needle)) found.add(pid)
        }
        return found
    }

    fun killAll(pids: List<Int>, killCommand: String = DEFAULT_KILL) {
        for (pid in pids) {
            runCatching { ProcessBuilder(killCommand, "-9", pid.toString()).start() }
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}

object WorkspaceSuLocator {
    private val CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/product/bin/su",
    )

    fun locate(
        exists: (String) -> Boolean = { File(it).isFile },
        pathEnv: String? = System.getenv("PATH"),
    ): String? {
        for (candidate in CANDIDATES) if (exists(candidate)) return candidate
        pathEnv?.split(':')?.forEach { dir ->
            if (dir.isEmpty()) return@forEach
            val candidate = "$dir/su"
            if (exists(candidate)) return candidate
        }
        return null
    }
}

object WorkspaceIdentityProbe {
    /** `id -u` prints a single number. */
    fun parseUid(raw: String): Int? = raw.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.substringBefore(' ')
        ?.toIntOrNull()

    /** `id -Z` prints `u:r:shell:s0`; anything else is treated as unknown (empty). */
    fun parseSelinuxContext(raw: String): String {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return if (line.startsWith("u:") && line.count { it == ':' } >= 3) line else ""
    }

    /** First field of `/proc/self/status` `Uid:` is the real UID. */
    fun parseProcStatusUid(raw: String): Int? = raw.lineSequence()
        .firstOrNull { it.trimStart().startsWith("Uid:") }
        ?.trimStart()
        ?.removePrefix("Uid:")
        ?.trim()
        ?.split(' ', '\t')
        ?.firstOrNull { it.isNotBlank() }
        ?.toIntOrNull()

    /** @return null when the observed UID matches, otherwise the refusal message. */
    fun verifyUid(expected: Int, observed: Int, channel: String): String? {
        if (expected == observed) return null
        return "identity guard failed for $channel: expected uid $expected but `id -u` reported $observed; refusing to run the command"
    }
}

/**
 * Payload the Shizuku user service writes into the pipe. Base64 keeps arbitrary output
 * (invalid UTF-8, newlines) intact and avoids a JSON parser on either side.
 */
data class NativeWireResult(
    val taskId: String,
    val uid: Int,
    val selinuxContext: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val cancelled: Boolean,
    val error: String? = null,
)

object WorkspaceCommandWire {
    private const val HEADER = "WSACCESS/1"
    private val UTF8 = Charsets.UTF_8

    fun encode(result: NativeWireResult): ByteArray {
        val builder = StringBuilder(HEADER).append('\n')
        fun put(key: String, value: String) {
            builder.append(key).append(':').append(encodeText(value)).append('\n')
        }
        put("task", result.taskId)
        put("uid", result.uid.toString())
        put("ctx", result.selinuxContext)
        put("exit", result.exitCode.toString())
        put("timedOut", result.timedOut.toString())
        put("truncated", result.truncated.toString())
        put("cancelled", result.cancelled.toString())
        put("error", result.error ?: "")
        put("stdout", result.stdout)
        put("stderr", result.stderr)
        return builder.toString().toByteArray(UTF8)
    }

    fun decode(bytes: ByteArray): NativeWireResult {
        val lines = bytes.toString(UTF8).split('\n')
        if (lines.firstOrNull() != HEADER) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.INTERNAL_ERROR,
                "Malformed response from the Shizuku user service (missing $HEADER header)",
            )
        }
        val fields = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val index = line.indexOf(':')
            if (index <= 0) continue
            fields[line.substring(0, index)] = decodeText(line.substring(index + 1))
        }
        return NativeWireResult(
            taskId = fields["task"].orEmpty(),
            uid = fields["uid"]?.toIntOrNull() ?: -1,
            selinuxContext = fields["ctx"].orEmpty(),
            exitCode = fields["exit"]?.toIntOrNull() ?: -1,
            stdout = fields["stdout"].orEmpty(),
            stderr = fields["stderr"].orEmpty(),
            timedOut = fields["timedOut"]?.toBooleanStrictOrNull() ?: false,
            truncated = fields["truncated"]?.toBooleanStrictOrNull() ?: false,
            cancelled = fields["cancelled"]?.toBooleanStrictOrNull() ?: false,
            error = fields["error"]?.takeIf { it.isNotEmpty() },
        )
    }

    private fun encodeText(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(UTF8))

    private fun decodeText(value: String): String = try {
        String(Base64.getDecoder().decode(value), UTF8)
    } catch (_: IllegalArgumentException) {
        ""
    }
}

/** Validates a user request before any privilege channel is touched. */
object WorkspaceCommandRequestValidator {
    fun validate(command: String, cwd: String, stdin: ByteArray?, timeoutMillis: Long) {
        if (command.isBlank()) {
            throw WorkspacePrivilegeException(WorkspacePrivilegeFailure.INVALID_REQUEST, "Command must not be empty")
        }
        if (command.length > WorkspaceCommandLimits.MAX_COMMAND_CHARS) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.INVALID_REQUEST,
                "Command is longer than ${WorkspaceCommandLimits.MAX_COMMAND_CHARS} characters",
            )
        }
        if (command.contains('\u0000')) {
            throw WorkspacePrivilegeException(WorkspacePrivilegeFailure.INVALID_REQUEST, "Command contains a NUL byte")
        }
        if (stdin != null && stdin.size > WorkspaceCommandLimits.MAX_STDIN_BYTES) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.INVALID_REQUEST,
                "stdin is larger than ${WorkspaceCommandLimits.MAX_STDIN_BYTES} bytes",
            )
        }
        if (timeoutMillis < 0) {
            throw WorkspacePrivilegeException(
                WorkspacePrivilegeFailure.INVALID_REQUEST,
                "timeoutMillis cannot be negative (0 means no limit)",
            )
        }
        WorkspaceShellCommand.validateCwd(cwd)?.let { reason ->
            throw WorkspacePrivilegeException(WorkspacePrivilegeFailure.INVALID_REQUEST, reason)
        }
    }
}

/** Small in-memory registry so `cancelOwner` never needs to wait on the Binder thread. */
class WorkspaceTaskRegistry {
    private val tasks = ConcurrentHashMap<String, Entry>()
    private val ownerLock = Any()
    private val revokedEpochs = HashMap<String, Long>()

    class Entry(
        val taskId: String,
        val ownerKey: String,
        val cancelToken: NativeCancelToken,
        val cancelRemote: () -> Unit,
    ) {
        @Volatile
        var control: NativeProcessControl? = null
    }

    fun register(entry: Entry) = synchronized(ownerLock) {
        val epoch = entry.ownerKey.substringAfterLast(':', "").toLongOrNull()
        val root = entry.ownerKey.substringBeforeLast(':', "")
        check(epoch == null || epoch > (revokedEpochs[root] ?: -1L)) { "Workspace authorization was revoked" }
        tasks[entry.taskId] = entry
    }

    fun revokeOwner(ownerKey: String): List<Entry> = synchronized(ownerLock) {
        val epoch = ownerKey.substringAfterLast(':', "").toLongOrNull()
        val root = ownerKey.substringBeforeLast(':', "")
        if (epoch != null) revokedEpochs[root] = maxOf(revokedEpochs[root] ?: -1L, epoch)
        entriesFor(ownerKey)
    }

    fun attachControl(taskId: String, control: NativeProcessControl) {
        val entry = tasks[taskId]
        entry?.control = control
        if (entry?.cancelToken?.isCancelled == true) control.terminate()
    }

    fun remove(taskId: String) {
        tasks.remove(taskId)
    }

    fun entriesFor(ownerKey: String): List<Entry> =
        tasks.values.filter { ownerKey.isEmpty() || it.ownerKey == ownerKey }

    fun size(): Int = tasks.size
}
