package me.rerere.rikkahub.data.mcpserver

/**
 * 本机 MCP 启动/恢复的决策规则。**纯函数、零依赖**：不碰 Android、不碰协程、不碰 Ktor，
 * 所以能直接放进纯 JVM 单测（见 `LocalMcpStartPolicyTest`）。
 *
 * 这里单独成一文件，是因为下面两条规则都曾经被写错过，而且错得很安静：
 * 表现是"开关是开的、前台服务也在，但 127.0.0.1 上什么都没有"，或者"真实原因被一句笼统文案盖掉"。
 */

/**
 * 一次前台服务启动该做什么。
 *
 * @property writeEnabled 是否需要把 `enabled = true` 落盘。
 * @property sync 是否**必须**无条件把运行状态对齐一次（启动 HTTP 服务或如实停掉）。
 */
data class LocalMcpStartPlan(
    val writeEnabled: Boolean,
    val sync: Boolean,
)

/**
 * 前台服务 `onStartCommand` 的启动计划。
 *
 * [LocalMcpStartPlan.sync] 永远为 `true`，这就是本函数的全部价值：`applySettings` 只在"设置真的变了"
 * 时才同步运行状态，而**冷启动恢复**的场景（用户上次已经打开、`enabled` 落盘就是 `true`）走
 * `applySettings` 会 `before == after`，于是一个字节都没启动，前台服务却已经常驻、通知停在"正在启动"，
 * 随后还被当成启动失败报错。带 action 的显式启动请求和无 action 的投递都必须真正对齐一次。
 *
 * @param intentAction 这次 `onStartCommand` 的 action，`null` 表示系统/普通投递。
 * @param startAction 显式启动用的 action（`LocalMcpService.ACTION_START`），由调用方传入，
 *   避免这个纯文件反向依赖 Android 的 `Service`。
 * @param enabled 当前已落盘的 `enabled`。
 */
fun planLocalMcpStart(
    intentAction: String?,
    startAction: String,
    enabled: Boolean,
): LocalMcpStartPlan = when (intentAction) {
    // 显式启动：先按用户意图落盘（已经 true 就不用再写），然后照样必须同步。
    startAction -> LocalMcpStartPlan(writeEnabled = !enabled, sync = true)
    // 其它投递：只对齐真实状态，不擅自把开关打开。
    else -> LocalMcpStartPlan(writeEnabled = false, sync = true)
}

/**
 * 启动尝试结束后要不要用一句兜底文案去覆盖运行状态。
 *
 * 返回 `null` 表示**不要覆盖**：
 * - 已经在跑（`running = true`），没东西要报；
 * - 状态已经是 ERROR 且带着真实原因（`port_in_use:8766`、`bind_failed:8766`、设置校验失败……），
 *   这时再用"前台服务被拒绝"覆盖，用户就再也看不到该改什么了。
 *
 * 返回非 null 表示用这个文案报错（调用方传入本地化后的兜底文案）。
 */
fun localMcpStartFallbackError(
    running: Boolean,
    statusIsError: Boolean,
    existingError: String?,
    fallback: String,
): String? = when {
    running -> null
    statusIsError && !existingError.isNullOrBlank() -> null
    else -> fallback
}