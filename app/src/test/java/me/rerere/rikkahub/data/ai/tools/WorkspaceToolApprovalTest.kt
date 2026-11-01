package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workspace 工具默认审批策略。
 *
 * 说明：这里原本还测 `canReadToolOutputs` / `shouldTruncateChildToolOutput` 这两个"要不要外置"的
 * 门槛，fork.42 已经把门槛删掉了——读工具读的是 app 自己的 filesDir，LOCAL_ROOTFS / REMOTE_HTTP /
 * SSH / 没有 workspace 四种情况下都能读回，外置因此无条件开启（理由写在 ToolOutputStore 顶部注释）。
 * 那两个函数没有生产调用点，对应测试也就没有意义，随之删除。这里只保留与审批相关的回归。
 */
class WorkspaceToolApprovalTest {
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
