package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理输出的截断判定（对应 fork.34 的 P2：childTools 没有截断）。
 *
 * 关键点是"不能让信息不可读"：只有子代理真的能读 /tool_outputs 时才外置全文，
 * 没有本地工作区 / 远程工作区 / 子代理禁用 workspace 工具时一律保留原文。
 */
class ChildToolTruncationTest {
    private fun tool(name: String) = Tool(name = name, description = "") { emptyList() }

    @Test
    fun `local workspace with shell truncates child output`() {
        assertTrue(
            shouldTruncateChildToolOutput(
                localWorkspaceFileAccess = true,
                childTools = listOf(tool("workspace_shell"), tool("search_web")),
            )
        )
    }

    @Test
    fun `file reader alone is enough to read the archived output`() {
        assertTrue(
            shouldTruncateChildToolOutput(
                localWorkspaceFileAccess = true,
                childTools = listOf(tool("workspace_read_file"), tool("recent_chats")),
            )
        )
    }

    @Test
    fun `child without workspace tools keeps the full output`() {
        // settings.subagent.includeWorkspace = false：子代理读不到 /tool_outputs，截断等于丢信息
        assertFalse(
            shouldTruncateChildToolOutput(
                localWorkspaceFileAccess = true,
                childTools = listOf(tool("search_web"), tool("recent_chats"), tool("mcp__fs__read")),
            )
        )
    }

    @Test
    fun `no local workspace at all keeps the full output`() {
        assertFalse(
            shouldTruncateChildToolOutput(
                localWorkspaceFileAccess = false,
                childTools = listOf(tool("workspace_shell")),
            )
        )
    }

    @Test
    fun `remote workspace keeps the full output`() {
        // runtime.remote.enabled = true：/workspace 不在远端，/tool_outputs 也不在
        assertFalse(
            shouldTruncateChildToolOutput(
                localWorkspaceFileAccess = false,
                childTools = listOf(tool("workspace_shell"), tool("workspace_job_start")),
            )
        )
    }

    @Test
    fun `empty child tool list keeps the full output`() {
        assertFalse(shouldTruncateChildToolOutput(localWorkspaceFileAccess = true, childTools = emptyList()))
    }
}
