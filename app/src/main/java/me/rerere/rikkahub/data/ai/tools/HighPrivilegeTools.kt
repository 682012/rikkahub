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
 * 调度：写与共享状态（settings / shell / diagnostics）一律 [ToolExecutionMode.SERIAL]，
 * 只读且幂等的（status / settings get / packages）才允许并行；写工具 `repeatable=false`
 * 且 `needsApproval=true`，日志类同样要审批。
 */
internal suspend fun createHighPrivilegeTools(
    host: HighPrivilegeToolHost?,
    assistantId: String,
): List<Tool> {
    if (host == null) return emptyList()
    // 现查：未开启 / 未绑定到本助手 → 空目录。
    val descriptors = host.exposedTools(assistantId)
    if (descriptors.isEmpty()) return emptyList()
    return descriptors.map { descriptor ->
        Tool(
            name = descriptor.name,
            description = descriptor.description,
            parameters = {
                val properties = descriptor.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (descriptor.inputSchema["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                InputSchema.Obj(properties, required)
            },
            executionMode = if (descriptor.readOnly && !descriptor.needsApproval) {
                ToolExecutionMode.PARALLEL_DEDUPLICATED
            } else {
                ToolExecutionMode.SERIAL
            },
            repeatable = descriptor.readOnly,
            needsApproval = { descriptor.needsApproval },
            execute = { args ->
                val result = host.callLocal(assistantId, descriptor.name, args.jsonObject)
                // 结果已经是结构化 JSON；不抛异常，让模型看到真实的 ok/error。
                listOf(UIMessagePart.Text(result.toString()))
            },
        )
    }
}

/** 给日志/报告用的固定名字集合（不含隐藏逻辑，仅便于排查）。 */
internal val HIGH_PRIVILEGE_TOOL_NAMES: Set<String> = HighPrivilegeTooling.all
