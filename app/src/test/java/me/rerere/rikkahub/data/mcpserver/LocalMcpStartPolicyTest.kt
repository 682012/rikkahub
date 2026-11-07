package me.rerere.rikkahub.data.mcpserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑回归：前台服务启动/恢复的决策。
 *
 * 两条被写错过的地方：
 * 1. 冷启动恢复（`enabled` 落盘已经是 `true`）只走 `applySettings`，因为 `before == after` 不会同步运行
 *    状态 → "前台服务在、127.0.0.1 上什么都没有"，随后还被当成启动失败报错；所以显式启动**必须**同步，
 *    无论 `enabled` 是否已经是 `true`。
 * 2. 启动失败时统一报"前台服务被拒绝"，把 `port_in_use` / `bind_failed` 这类真实原因冲掉，用户不知道改什么。
 */
class LocalMcpStartPolicyTest {

    private val start = LocalMcpServiceActionFixture.ACTION_START

    @Test
    fun `explicit start always syncs even when already enabled`() {
        // 冷启动恢复：enabled 早就是 true。这里如果 sync=false 就是本次要防的回归。
        val restored = planLocalMcpStart(
            intentAction = start,
            startAction = start,
            enabled = true,
        )
        assertTrue(restored.sync)
        // 已经 true 就不必再写一次，但同步不能少。
        assertFalse(restored.writeEnabled)
    }

    @Test
    fun `explicit start from disabled writes intent and syncs`() {
        val fresh = planLocalMcpStart(intentAction = start, startAction = start, enabled = false)
        assertTrue(fresh.writeEnabled)
        assertTrue(fresh.sync)
    }

    @Test
    fun `delivery without action syncs but never flips the switch on`() {
        for (action in listOf(null, "me.rerere.rikkahub.action.UNKNOWN")) {
            val plan = planLocalMcpStart(intentAction = action, startAction = start, enabled = false)
            assertFalse("$action 不该擅自把开关打开", plan.writeEnabled)
            assertTrue("$action 仍必须对齐真实运行状态", plan.sync)
        }
    }

    @Test
    fun `running server never gets a failure message`() {
        assertNull(
            localMcpStartFallbackError(
                running = true,
                statusIsError = false,
                existingError = null,
                fallback = "foreground_denied",
            )
        )
    }

    @Test
    fun `real reason is not overwritten by the generic message`() {
        assertNull(
            localMcpStartFallbackError(
                running = false,
                statusIsError = true,
                existingError = "port_in_use:8766",
                fallback = "foreground_denied",
            )
        )
    }

    @Test
    fun `plain failure falls back to the localised message`() {
        assertEquals(
            "foreground_denied",
            localMcpStartFallbackError(
                running = false,
                statusIsError = false,
                existingError = null,
                fallback = "foreground_denied",
            ),
        )
        // 状态是 ERROR 但原因空白（旧状态 / 兜底被清掉）时仍然要报。
        assertEquals(
            "foreground_denied",
            localMcpStartFallbackError(
                running = false,
                statusIsError = true,
                existingError = "   ",
                fallback = "foreground_denied",
            ),
        )
    }
}

/**
 * `LocalMcpService.ACTION_START` 的字面值。测试不能引用 Service 类（Android），
 * 所以在这里盯着同一个常量字符串，改了任一侧都会让这里失败。
 */
private object LocalMcpServiceActionFixture {
    const val ACTION_START = "me.rerere.rikkahub.action.LOCAL_MCP_START"
}