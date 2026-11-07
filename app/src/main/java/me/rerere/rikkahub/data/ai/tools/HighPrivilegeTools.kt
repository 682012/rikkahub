package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeToolHost
import me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeTooling

/**
 * m1 高权限工具（本地助手侧）。
 *
 * **opt-in**：只有用户在权限中心把"自动化授权"打开并绑定到**这个**助手时，工具才出现在目录里；
 * 默认（未开 / 未绑定 / 绑的是别的助手）一个都不加，不是"加了再在 execute 里拒"。
 *
 * 运行期每次调用都重新核一次授权（[HighPrivilegeToolHost.callLocal]），所以中途收权立刻生效，
 * 不会沿用创建工具目录那一刻的旧快照。
 *
 * m3：**审批**与**授权**彻底分开：
 * - 审批走与设备工具 / workspace 工具同一张可配置表 [approvalOverrides]（见 [ToolApprovalPolicy]），
 *   默认只读查询免交互、写（settings_put / shell）与日志类（diagnostics）需审批，用户可逐项改；
 * - 授权仍然一步都不能省：目录本身由权限中心实时授权决定，每次 execute 还在
 *   [HighPrivilegeToolHost.callLocal] 里重核自动化开关 / 助手绑定 / 写开关 / Shizuku，
 *   写路径的幂等账本也不在这里，而在 host/controller 内部。
 *   也就是说"把审批关掉"只等于"不再弹窗问我"，不等于多拿到任何一项权限。
 *
 * 调度：写与共享状态（settings / shell / diagnostics）一律 [ToolExecutionMode.SERIAL]，
 * 只读且幂等的（status / settings get / packages）才允许并行。
 */
internal suspend fun createHighPrivilegeTools(
    host: HighPrivilegeToolHost?,
    assistantId: String,
    /** 用户配置的审批覆盖表；工具名 -> 是否需要审批。空表 = 全部用默认值。 */
    approvalOverrides: Map<String, Boolean> = emptyMap(),
): List<Tool> {
    if (host == null) return emptyList()
    // 现查：未开启 / 未绑定到本助手 → 空目录。这一步与审批配置无关，关审批不会让工具凭空出现。
    val descriptors = host.exposedTools(assistantId)
    if (descriptors.isEmpty()) return emptyList()
    return descriptors.map { descriptor ->
        // 每次求值都读快照里的表，所以会话中途改设置，下一次 needsApproval 就是新值
        fun needsApproval(): Boolean = ToolApprovalPolicy.resolve(
            toolName = descriptor.name,
            overrides = approvalOverrides,
            default = ToolApprovalPolicy.highPrivilegeApprovalDefault(descriptor.name),
        )
        Tool(
            name = descriptor.name,
            description = descriptor.description,
            parameters = {
                val properties = descriptor.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (descriptor.inputSchema["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                InputSchema.Obj(properties, required)
            },
            // 并行与否看的是"有没有副作用"，不看"要不要审批"：
            // 用户把 settings_put 设成免审批之后，它仍然是写操作，不能和别的成员并发。
            executionMode = if (descriptor.readOnly && !HighPrivilegeTooling.isSensitive(descriptor.name)) {
                ToolExecutionMode.PARALLEL_DEDUPLICATED
            } else {
                ToolExecutionMode.SERIAL
            },
            repeatable = descriptor.readOnly,
            needsApproval = { needsApproval() },
            execute = { args ->
                // 授权、写开关、Shizuku、幂等账本全在 host 里重核；失败返回结构化 ok=false，
                // 不抛异常、不重试、不 fallback —— 审批放行不会改变这里的任何一条结论。
                val result = host.callLocal(assistantId, descriptor.name, args.jsonObject)
                listOf(UIMessagePart.Text(result.toString()))
            },
        )
    }
}

/** 给日志/报告用的固定名字集合（不含隐藏逻辑，仅便于排查）。 */
internal val HIGH_PRIVILEGE_TOOL_NAMES: Set<String> = HighPrivilegeTooling.all
