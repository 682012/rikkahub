package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.datastore.RemoteExecutionSettings
import me.rerere.rikkahub.data.datastore.WorkspaceRuntimeSettings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceCapabilities
import me.rerere.workspace.WorkspaceShellStatus

/**
 * 一条命令或一次 job 实际会落到哪个执行后端。
 *
 * fork 里有两条互不相干的“远程”通道：官方 3.1.1 的 SSH 工作区（命令与文件都在对端），
 * 以及 fork 自己的 HTTP 执行服务器（LOCAL 工作区 + 命令在远端）。全局开关
 * `workspaceRuntime.remote` 只对 LOCAL 有意义，SSH 工作区永远走 SSH 后端。
 *
 * 判断集中在这里，Repository 的 execute/start/status/kill 与工具层共用同一份规则，
 * 避免各处各写一遍 `remote?.enabled` 而逐渐漂移（fork.36 的 SSH 劫持就是这么来的）。
 */
internal enum class WorkspaceExecutionBackend {
    /** 本地 PRoot Rootfs：命令与 /workspace 都在本机 */
    LOCAL_ROOTFS,

    /** fork HTTP 执行服务器：命令在远端，工作区文件仍在本机且那边没有挂载 */
    REMOTE_HTTP,

    /** 官方 SSH 工作区：命令和文件都在对端 */
    SSH,
}

/** 全局 HTTP 远程开关对当前工作区是否真的生效；SSH 工作区永不被它劫持 */
internal fun effectiveRemoteExecution(
    isSsh: Boolean,
    runtime: WorkspaceRuntimeSettings,
): RemoteExecutionSettings? = runtime.remote?.takeIf { it.enabled && !isSsh }

internal fun effectiveExecutionBackend(
    isSsh: Boolean,
    runtime: WorkspaceRuntimeSettings,
): WorkspaceExecutionBackend = when {
    isSsh -> WorkspaceExecutionBackend.SSH
    effectiveRemoteExecution(isSsh, runtime) != null -> WorkspaceExecutionBackend.REMOTE_HTTP
    else -> WorkspaceExecutionBackend.LOCAL_ROOTFS
}

/** 工作区不存在时没有执行后端 */
internal fun effectiveExecutionBackend(
    workspace: WorkspaceEntity?,
    runtime: WorkspaceRuntimeSettings,
): WorkspaceExecutionBackend? =
    workspace?.let { effectiveExecutionBackend(it.isSsh(), runtime) }

/**
 * 工作区工具是否已经可以给出。
 *
 * - LOCAL：必须装好 Rootfs（READY），没装时既没有 shell 也没有文件区；
 * - SSH：远程工作区建好即 READY，这里只认它自己的状态，不能被全局 remote 开关绕过；
 * - HTTP 远程：命令在远端跑，与本地 Rootfs 状态无关。
 */
internal fun workspaceToolsReady(
    backend: WorkspaceExecutionBackend,
    shellStatus: String,
): Boolean = when (backend) {
    WorkspaceExecutionBackend.REMOTE_HTTP -> true
    WorkspaceExecutionBackend.LOCAL_ROOTFS,
    WorkspaceExecutionBackend.SSH -> shellStatus == WorkspaceShellStatus.READY.name
}

/**
 * job 工具的可用性。
 *
 * - LOCAL：后台任务靠本地 PRoot + 前台服务，需要 rootfs；
 * - HTTP 远程：服务器自带 job API；
 * - SSH：没有 job 通道（也不该退化成在本地 proot 里跑远端路径），一律不给。
 */
internal fun jobToolsAvailable(
    capabilities: WorkspaceCapabilities,
    backend: WorkspaceExecutionBackend,
): Boolean = when (backend) {
    WorkspaceExecutionBackend.LOCAL_ROOTFS -> capabilities.rootfs
    WorkspaceExecutionBackend.REMOTE_HTTP -> true
    WorkspaceExecutionBackend.SSH -> false
}

/**
 * 单个 workspace 工具在该后端下是否还有意义。
 *
 * - HTTP 远程执行时 `/workspace` 不在远端，本地 read/write/edit 给出的是另一台机器的内容，
 *   只会误导模型，因此只保留 shell 与 job；
 * - SSH 没有 job 通道（[jobToolsAvailable] 已经不生成这些工具，这里再挡一层），
 *   但 read_file 走 SFTP 必须保留，写工具由 [WorkspaceCapabilities.fileWriteTools] 关掉；
 * - 本地 Rootfs 保留全部。
 */
internal fun isToolAvailableOnBackend(
    toolName: String,
    backend: WorkspaceExecutionBackend?,
): Boolean = when (backend) {
    null -> false
    WorkspaceExecutionBackend.REMOTE_HTTP ->
        toolName == "workspace_shell" || toolName.startsWith("workspace_job_")

    WorkspaceExecutionBackend.SSH -> !toolName.startsWith("workspace_job_")

    WorkspaceExecutionBackend.LOCAL_ROOTFS -> true
}

/** SSH 工作区没有 job 通道时的统一报错，避免误落本地 proot */
internal const val SSH_JOB_UNSUPPORTED_MESSAGE =
    "SSH workspaces do not support background jobs; run the command in workspace_shell or the SSH terminal"
