package me.rerere.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
private data class JobMeta(
    val id: String,
    val command: String,
    val cwd: String = "",
    val pid: Long? = null,
    val logPath: String,
    val startedAt: Long,
)

/**
 * 在 Rootfs 内启动并跟踪后台任务。
 *
 * 设计取舍：
 * - 进程优先用 setsid 启动，脱离当前 shell 的进程组，工具返回后不会被回收；
 * - pid 与日志落在 job 目录，App 重启后还能查；
 * - 所有状态判断都走 Rootfs 内的 shell，避免宿主与 proot 的 PID 命名空间不一致。
 */
class WorkspaceJobManager(
    private val manager: WorkspaceManager,
) {
    companion object {
        private const val JOBS_DIR = ".jobs"
        private const val MAX_LOG_TAIL_CHARS = 16 * 1024
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    fun jobsDir(root: String): File = File(manager.workspaceDir(root), JOBS_DIR)

    fun list(root: String): List<WorkspaceJobInfo> {
        val dir = jobsDir(root)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { readInfo(root, it.name) }
            .sortedByDescending { it.startedAt }
    }

    fun start(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = 0L,
    ): WorkspaceJobInfo {
        val id = UUID.randomUUID().toString().take(8)
        val jobDir = File(jobsDir(root), id)
        check(jobDir.mkdirs()) { "Cannot create job directory" }
        val log = File(jobDir, "job.log")
        val pidFile = File(jobDir, "job.pid")

        // 用 sh -c 包一层，保证重定向和 pid 记录都在 Rootfs 内完成。
        // setsid 不存在时退化为 nohup + 普通后台进程。
        val inner = buildString {
            append("cd ").append(shellQuote(cwd.ifBlank { "/workspace" })).append(" && ")
            append("if command -v setsid >/dev/null 2>&1; then ")
            append("setsid sh -c ").append(shellQuote(command))
            append(" > ").append(shellQuote(log.absolutePath)).append(" 2>&1 & ")
            append("else ")
            append("nohup sh -c ").append(shellQuote(command))
            append(" > ").append(shellQuote(log.absolutePath)).append(" 2>&1 & ")
            append("fi; echo \$! > ").append(shellQuote(pidFile.absolutePath))
        }

        val result = manager.executeCommand(
            root = root,
            command = inner,
            cwd = cwd,
            timeoutMillis = 30_000L,
        )
        check(result.exitCode == 0) {
            "Failed to start job: ${result.stderr.ifBlank { result.stdout }}"
        }
        val pid = runCatching { pidFile.readText().trim().toLong() }.getOrNull()
        val info = WorkspaceJobInfo(
            id = id,
            command = command,
            cwd = cwd,
            pid = pid,
            logPath = log.absolutePath,
            startedAt = System.currentTimeMillis(),
        )
        writeInfo(jobDir, info)
        return info
    }

    fun status(root: String, jobId: String, tailLines: Int = 80): WorkspaceJobStatus {
        val info = readInfo(root, jobId) ?: error("Job not found: $jobId")
        val pid = info.pid
        val running = if (pid == null) false else {
            manager.executeCommand(
                root = root,
                command = "kill -0 $pid 2>/dev/null && echo running || echo stopped",
                timeoutMillis = 10_000L,
            ).stdout.trim() == "running"
        }
        val log = File(info.logPath)
        val text = if (log.isFile) readTail(log, tailLines.coerceIn(1, 400)) else ""
        val truncated = text.length > MAX_LOG_TAIL_CHARS
        return WorkspaceJobStatus(
            info = info,
            running = running,
            exitCode = null,
            logTail = if (truncated) text.takeLast(MAX_LOG_TAIL_CHARS) else text,
            logTruncated = truncated,
        )
    }

    fun kill(root: String, jobId: String): Boolean {
        val info = readInfo(root, jobId) ?: error("Job not found: $jobId")
        val pid = info.pid ?: return false
        // 先杀进程组，再兜底杀进程本身
        val result = manager.executeCommand(
            root = root,
            command = "kill -TERM -$pid 2>/dev/null || kill -TERM $pid 2>/dev/null; sleep 0.2; kill -0 $pid 2>/dev/null && kill -KILL -$pid 2>/dev/null; true",
            timeoutMillis = 10_000L,
        )
        return result.exitCode == 0
    }

    fun readTail(file: File, lines: Int): String {
        if (!file.isFile) return ""
        // 只读文件尾部，日志涨到几百 MB 时也不会把整份读进内存
        val maxBytes = 256 * 1024
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = maxOf(0L, length - maxBytes)
                raf.seek(start)
                val bytes = ByteArray((length - start).toInt())
                raf.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                text.lineSequence().toList().takeLast(lines).joinToString("\n")
            }
        }.getOrDefault("")
    }

    private fun readInfo(root: String, jobId: String): WorkspaceJobInfo? {
        val dir = File(jobsDir(root), jobId)
        val meta = File(dir, "job.json")
        if (!meta.isFile) return null
        return runCatching {
            val m = json.decodeFromString<JobMeta>(meta.readText())
            WorkspaceJobInfo(
                id = m.id,
                command = m.command,
                cwd = m.cwd,
                pid = m.pid,
                logPath = m.logPath,
                startedAt = m.startedAt,
            )
        }.getOrNull()
    }

    private fun writeInfo(dir: File, info: WorkspaceJobInfo) {
        val meta = JobMeta(
            id = info.id,
            command = info.command,
            cwd = info.cwd,
            pid = info.pid,
            logPath = info.logPath,
            startedAt = info.startedAt,
        )
        File(dir, "job.json").writeText(json.encodeToString(JobMeta.serializer(), meta))
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
