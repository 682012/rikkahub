package me.rerere.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
private data class JobMeta(
    val id: String,
    val command: String,
    val cwd: String = "",
    val pid: Long? = null,
    val logPath: String,
    val startedAt: Long,
)

/** The parent process owns the tracer. A detached guest is killed by PRoot --kill-on-exit. */
class WorkspaceJobManager(private val manager: WorkspaceManager) {
    private val processes = ConcurrentHashMap<String, Process>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun jobsDir(root: String) = File(manager.filesDir(root), ".jobs").apply { mkdirs() }
    private fun directory(root: String, id: String): File {
        require(id.matches(Regex("[a-fA-F0-9-]{8,36}"))) { "Invalid job id" }
        return File(jobsDir(root), id)
    }
    private fun key(root: String, id: String) = "$root/$id"

    fun list(root: String): List<WorkspaceJobInfo> = jobsDir(root).listFiles().orEmpty()
        .filter { it.isDirectory }.mapNotNull { runCatching { readInfo(root, it.name) }.getOrNull() }
        .sortedByDescending { it.startedAt }

    fun start(root: String, command: String, cwd: String = "", timeoutMillis: Long = 0,
        shellCompatibilityMode: Boolean = false): WorkspaceJobInfo {
        require(command.isNotBlank())
        val id = UUID.randomUUID().toString()
        val dir = directory(root, id)
        check(dir.mkdirs())
        val log = File(dir, "job.log")
        val exitFile = File(dir, "exit.code")
        val info = WorkspaceJobInfo(id, command, cwd, null, log.absolutePath, System.currentTimeMillis())
        File(dir, "job.json").writeText(json.encodeToString(JobMeta.serializer(),
            JobMeta(id, command, cwd, null, log.absolutePath, info.startedAt)))
        val process = try {
            manager.startCommand(root, command, cwd, log, shellCompatibilityMode)
        } catch (e: Exception) {
            exitFile.writeText("127")
            log.appendText("Start failed: ${e.message}\n")
            throw e
        }
        processes[key(root, id)] = process
        Thread({
            try {
                val finished = if (timeoutMillis <= 0) { process.waitFor(); true }
                    else process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                if (!finished) terminate(process)
                val code = if (finished) process.exitValue() else -1
                exitFile.writeText(code.toString())
            } catch (e: InterruptedException) {
                terminate(process)
                exitFile.writeText("-1")
                Thread.currentThread().interrupt()
            } finally {
                processes.remove(key(root, id), process)
            }
        }, "workspace-job-$id").apply { isDaemon = true; start() }
        return info
    }

    fun status(root: String, jobId: String, tailLines: Int = 80): WorkspaceJobStatus {
        val info = readInfo(root, jobId) ?: error("Job not found: $jobId")
        val process = processes[key(root, jobId)]
        val running = process?.isAlive == true
        val exitFile = File(directory(root, jobId), "exit.code")
        val exitCode = if (running) null else runCatching {
            if (exitFile.isFile) exitFile.readText().trim().toInt() else process?.exitValue()
        }.getOrNull()
        val log = File(info.logPath)
        val text = readTail(log, tailLines.coerceIn(1, 400))
        val suffix = if (!running && exitCode == null) "\n[Task owner was restarted; this task is no longer managed. Do not trust or signal its old PID.]" else ""
        return WorkspaceJobStatus(info, running, exitCode, text.takeLast(16 * 1024) + suffix,
            log.length() > 256 * 1024 || text.length > 16 * 1024)
    }

    fun kill(root: String, jobId: String): Boolean {
        checkNotNull(readInfo(root, jobId)) { "Job not found: $jobId" }
        // Never kill a persisted PID: it may have been recycled by the operating system.
        val process = processes[key(root, jobId)] ?: return false
        terminate(process)
        return !process.isAlive
    }

    private fun terminate(process: Process) {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }

    fun prune(root: String, keepDays: Int) {
        if (keepDays <= 0) return
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        list(root).filter { it.startedAt < cutoff && processes[key(root, it.id)] == null }
            .forEach { directory(root, it.id).deleteRecursively() }
    }

    fun readTail(file: File, lines: Int): String = if (!file.isFile) "" else runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val count = minOf(raf.length(), 256 * 1024L).toInt()
            raf.seek(raf.length() - count)
            val bytes = ByteArray(count)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8).lineSequence().toList().takeLast(lines).joinToString("\n")
        }
    }.getOrDefault("")

    private fun readInfo(root: String, id: String): WorkspaceJobInfo? {
        val dir = directory(root, id)
        val file = File(dir, "job.json")
        if (!file.isFile) return null
        return runCatching {
            val meta = json.decodeFromString<JobMeta>(file.readText())
            require(meta.id == id)
            WorkspaceJobInfo(id, meta.command, meta.cwd, null, File(dir, "job.log").absolutePath, meta.startedAt)
        }.getOrNull()
    }
}
