package me.rerere.workspace

/**
 * 工作区后台任务。
 *
 * 与 [WorkspaceCommandResult] 的区别：命令启动后立即返回，进程继续在 Rootfs 内运行。
 * 状态和日志通过 [WorkspaceJobManager] 查询，适合编译、下载、长脚本这类超过
 * 前台工具超时的任务。
 */
data class WorkspaceJobInfo(
    val id: String,
    val command: String,
    val cwd: String,
    val pid: Long?,
    val logPath: String,
    val startedAt: Long,
)

data class WorkspaceJobStatus(
    val info: WorkspaceJobInfo,
    val running: Boolean,
    val exitCode: Int?,
    val logTail: String,
    val logTruncated: Boolean,
)
