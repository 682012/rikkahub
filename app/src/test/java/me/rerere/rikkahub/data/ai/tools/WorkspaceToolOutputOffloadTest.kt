package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.repository.WorkspaceExecutionBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长工具输出是否外置到本机 `/tool_outputs`（fork.36 的 P1-1：HTTP 远程执行时父子都读不到外置文件）。
 *
 * `canReadToolOutputs` 就是 ChatToolFactory.createTools 里用的那份判断，
 * `shouldTruncateChildToolOutput` 是父/子两侧共用的门槛，所以这里覆盖的是真实路径。
 */
class WorkspaceToolOutputOffloadTest {
    private fun tool(name: String) = Tool(name = name, description = "") { emptyList() }

    /** HTTP 远程模式下真正会给出的工具集：只有 shell 与 job，没有本地读写 */
    private val remoteOnlyTools = listOf(
        tool("workspace_shell"),
        tool("workspace_job_start"),
        tool("workspace_job_status"),
        tool("workspace_job_kill"),
    )

    /** 本地 Rootfs 模式的工具集 */
    private val localTools = listOf(tool("workspace_read_file"), tool("workspace_shell"))

    @Test
    fun `local rootfs can read the archived output`() {
        assertTrue(
            canReadToolOutputs(
                backend = WorkspaceExecutionBackend.LOCAL_ROOTFS,
                workspaceTools = localTools,
                workspaceHasRootfs = true,
            )
        )
    }

    @Test
    fun `http remote mode must not offload even though the workspace has a rootfs`() {
        // 之前的 bug：只看 capabilities.rootfs，LOCAL+HTTP 时判断为 true，
        // 全文写进本机 filesDir，而 workspace_shell 跑在服务器上，`cat /tool_outputs/x.txt` 必然失败
        assertFalse(
            canReadToolOutputs(
                backend = WorkspaceExecutionBackend.REMOTE_HTTP,
                workspaceTools = remoteOnlyTools,
                workspaceHasRootfs = true,
            )
        )
    }

    @Test
    fun `ssh never offloads to the local tool output folder`() {
        assertFalse(
            canReadToolOutputs(
                backend = WorkspaceExecutionBackend.SSH,
                workspaceTools = localTools,
                workspaceHasRootfs = false,
            )
        )
        // 即便 rootfs 标志是 true（异常配置），后端不是 LOCAL 也不外置
        assertFalse(
            canReadToolOutputs(
                backend = WorkspaceExecutionBackend.SSH,
                workspaceTools = localTools,
                workspaceHasRootfs = true,
            )
        )
    }

    @Test
    fun `no tools or no rootfs keeps the full output`() {
        assertFalse(canReadToolOutputs(WorkspaceExecutionBackend.LOCAL_ROOTFS, emptyList(), true))
        assertFalse(canReadToolOutputs(WorkspaceExecutionBackend.LOCAL_ROOTFS, localTools, false))
        assertFalse(canReadToolOutputs(null, localTools, true))
    }

    @Test
    fun `offload only ever happens on the local backend`() {
        val backends = listOf(
            WorkspaceExecutionBackend.LOCAL_ROOTFS,
            WorkspaceExecutionBackend.REMOTE_HTTP,
            WorkspaceExecutionBackend.SSH,
            null,
        )
        backends.forEach { backend ->
            val canRead = canReadToolOutputs(backend, remoteOnlyTools, workspaceHasRootfs = true)
            if (canRead) {
                assertEquals(WorkspaceExecutionBackend.LOCAL_ROOTFS, backend)
            }
        }
    }

    @Test
    fun `child output is kept whole when the parent cannot read the archive`() {
        val parentCanRead = canReadToolOutputs(
            backend = WorkspaceExecutionBackend.REMOTE_HTTP,
            workspaceTools = remoteOnlyTools,
            workspaceHasRootfs = true,
        )
        assertFalse(shouldTruncateChildToolOutput(parentCanRead, remoteOnlyTools))
    }

    @Test
    fun `local parent still truncates child output when a reader tool exists`() {
        val parentCanRead = canReadToolOutputs(
            backend = WorkspaceExecutionBackend.LOCAL_ROOTFS,
            workspaceTools = localTools,
            workspaceHasRootfs = true,
        )
        assertTrue(shouldTruncateChildToolOutput(parentCanRead, localTools))
        assertFalse(shouldTruncateChildToolOutput(parentCanRead, listOf(tool("search_web"))))
    }

    @Test
    fun `default approvals are untouched`() {
        assertTrue(resolveWorkspaceToolApproval("workspace_shell", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_read_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_write_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_edit_file", emptyMap()))
        // 用户覆盖项仍然优先于默认值
        assertFalse(resolveWorkspaceToolApproval("workspace_shell", mapOf("workspace_shell" to false)))
        assertTrue(resolveWorkspaceToolApproval("workspace_read_file", mapOf("workspace_read_file" to true)))
    }
}
