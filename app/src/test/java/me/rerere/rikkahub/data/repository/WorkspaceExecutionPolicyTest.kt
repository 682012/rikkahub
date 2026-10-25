package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.datastore.RemoteExecutionSettings
import me.rerere.rikkahub.data.datastore.WorkspaceRuntimeSettings
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行后端选择（fork.36 的 P1：全局 HTTP 远程开关把 SSH 工作区劫持到另一台机器）。
 *
 * 这些函数就是 WorkspaceRepository.executeCommand/startJob/jobStatus/killJob、
 * WorkspaceTools.createWorkspaceTools 与 ChatToolFactory.createWorkspaceToolsIfReady
 * 真正调用的那份判断，所以这里锁住的是实际行为，不是另写一套。
 */
class WorkspaceExecutionPolicyTest {
    private val enabledRemote = RemoteExecutionSettings(enabled = true, baseUrl = "http://10.0.0.2:18080")
    private val disabledRemote = RemoteExecutionSettings(enabled = false, baseUrl = "http://10.0.0.2:18080")

    private fun runtime(remote: RemoteExecutionSettings?) = WorkspaceRuntimeSettings(remote = remote)

    // --- 后端选择 -------------------------------------------------------------

    @Test
    fun `global remote switch never hijacks an ssh workspace`() {
        val withRemote = runtime(enabledRemote)
        assertNull(effectiveRemoteExecution(isSsh = true, runtime = withRemote))
        assertEquals(WorkspaceExecutionBackend.SSH, effectiveExecutionBackend(isSsh = true, runtime = withRemote))
        // 关掉开关时 SSH 还是 SSH，行为不变
        assertEquals(WorkspaceExecutionBackend.SSH, effectiveExecutionBackend(isSsh = true, runtime = runtime(null)))
    }

    @Test
    fun `local workspace uses http only when the switch is enabled`() {
        val withRemote = runtime(enabledRemote)
        assertNotNull(effectiveRemoteExecution(isSsh = false, runtime = withRemote))
        assertEquals(WorkspaceExecutionBackend.REMOTE_HTTP, effectiveExecutionBackend(isSsh = false, runtime = withRemote))

        assertNull(effectiveRemoteExecution(isSsh = false, runtime = runtime(disabledRemote)))
        assertEquals(WorkspaceExecutionBackend.LOCAL_ROOTFS, effectiveExecutionBackend(isSsh = false, runtime = runtime(disabledRemote)))

        assertNull(effectiveRemoteExecution(isSsh = false, runtime = runtime(null)))
        assertEquals(WorkspaceExecutionBackend.LOCAL_ROOTFS, effectiveExecutionBackend(isSsh = false, runtime = runtime(null)))
    }

    @Test
    fun `entity overload follows the same rule`() {
        val ssh = WorkspaceEntity(
            id = "ws-ssh",
            name = "SSH",
            root = "ws-ssh",
            createdAt = 0L,
            updatedAt = 0L,
            type = WorkspaceType.SSH.name,
        )
        val local = ssh.copy(id = "ws-local", root = "ws-local", type = WorkspaceType.LOCAL.name)
        val withRemote = runtime(enabledRemote)

        assertEquals(WorkspaceExecutionBackend.SSH, effectiveExecutionBackend(ssh, withRemote))
        assertEquals(WorkspaceExecutionBackend.REMOTE_HTTP, effectiveExecutionBackend(local, withRemote))
        assertNull(effectiveExecutionBackend(null, withRemote))
    }

    // --- 就绪判定 -------------------------------------------------------------

    @Test
    fun `ssh readiness is its own state and cannot be bypassed by the global switch`() {
        val withRemote = runtime(enabledRemote)
        val backend = effectiveExecutionBackend(isSsh = true, runtime = withRemote)
        assertEquals(WorkspaceExecutionBackend.SSH, backend)
        assertFalse(workspaceToolsReady(backend, WorkspaceShellStatus.DISABLED.name))
        assertFalse(workspaceToolsReady(backend, WorkspaceShellStatus.INSTALLING.name))
        assertTrue(workspaceToolsReady(backend, WorkspaceShellStatus.READY.name))
    }

    @Test
    fun `http execution does not depend on the local rootfs state`() {
        assertTrue(workspaceToolsReady(WorkspaceExecutionBackend.REMOTE_HTTP, WorkspaceShellStatus.DISABLED.name))
        assertTrue(workspaceToolsReady(WorkspaceExecutionBackend.REMOTE_HTTP, WorkspaceShellStatus.BROKEN.name))
        assertTrue(workspaceToolsReady(WorkspaceExecutionBackend.LOCAL_ROOTFS, WorkspaceShellStatus.READY.name))
        assertFalse(workspaceToolsReady(WorkspaceExecutionBackend.LOCAL_ROOTFS, WorkspaceShellStatus.DISABLED.name))
    }

    // --- job 工具策略 ---------------------------------------------------------

    @Test
    fun `ssh never gets background job tools even with the global switch on`() {
        val sshCaps = WorkspaceType.SSH.capabilities
        val backend = effectiveExecutionBackend(isSsh = true, runtime = runtime(enabledRemote))
        assertFalse(jobToolsAvailable(sshCaps, backend))
        // 而且 Repository 侧会用同一份后端判断直接拒绝，不会落到本地 proot
        assertTrue(SSH_JOB_UNSUPPORTED_MESSAGE.contains("SSH"))
        assertTrue(SSH_JOB_UNSUPPORTED_MESSAGE.contains("workspace_shell"))
    }

    @Test
    fun `local jobs need rootfs, http jobs come from the server`() {
        val localCaps = WorkspaceType.LOCAL.capabilities
        assertTrue(jobToolsAvailable(localCaps, WorkspaceExecutionBackend.LOCAL_ROOTFS))
        assertFalse(jobToolsAvailable(localCaps.copy(rootfs = false), WorkspaceExecutionBackend.LOCAL_ROOTFS))
        assertTrue(jobToolsAvailable(localCaps.copy(rootfs = false), WorkspaceExecutionBackend.REMOTE_HTTP))
    }

    // --- 工具可见性 -----------------------------------------------------------

    private fun visibleTools(names: List<String>, backend: WorkspaceExecutionBackend): List<String> =
        names.filter { isToolAvailableOnBackend(it, backend) }

    private val allToolNames = listOf(
        "workspace_read_file",
        "workspace_write_file",
        "workspace_edit_file",
        "workspace_shell",
        "workspace_job_start",
        "workspace_job_status",
        "workspace_job_kill",
    )

    @Test
    fun `ssh keeps the file reader and shell, and no job tools are ever created`() {
        val backend = effectiveExecutionBackend(isSsh = true, runtime = runtime(enabledRemote))
        // SSH 工作区 capabilities.fileWriteTools=false，实际候选工具是 read_file + shell；
        // 全局 remote 开关打开时两者都必须保留，job 工具即使出现在候选里也要被挡掉
        val sshCandidates = listOf(
            "workspace_read_file",
            "workspace_shell",
            "workspace_job_start",
            "workspace_job_status",
            "workspace_job_kill",
        )
        assertEquals(listOf("workspace_read_file", "workspace_shell"), visibleTools(sshCandidates, backend))
    }

    @Test
    fun `http execution hides local file tools and keeps shell plus jobs`() {
        val backend = effectiveExecutionBackend(isSsh = false, runtime = runtime(enabledRemote))
        assertEquals(
            listOf("workspace_shell", "workspace_job_start", "workspace_job_status", "workspace_job_kill"),
            visibleTools(allToolNames, backend),
        )
    }

    @Test
    fun `local rootfs keeps every workspace tool`() {
        val backend = effectiveExecutionBackend(isSsh = false, runtime = runtime(null))
        assertEquals(allToolNames, visibleTools(allToolNames, backend))
        // 没有工作区时没有任何工具可见
        assertFalse(isToolAvailableOnBackend("workspace_shell", null))
    }
}
