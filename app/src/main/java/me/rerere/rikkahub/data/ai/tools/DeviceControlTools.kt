package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.devicecontrol.DeviceControlBridge
import me.rerere.rikkahub.data.mcpserver.LocalMcpSettingsStore
import kotlin.uuid.Uuid

/**
 * Same device bridge as MCP; local assistants cannot bypass its per-app grants.
 *
 * 审批（要不要先问用户）与授权（这个 App / 这个动作到底允不允许做）是两件事：
 * - 审批走可配置表 [approvalOverrides]，只读查询默认免交互，写操作/装机/杀进程默认需审批，
 *   用户可以在工作区设置里逐项打开/关闭；
 * - 授权仍然完全由 [DeviceControlBridge] 和权限中心决定（enabled / allowRead / allowAct /
 *   allowGlobalActions / 包名白名单 / 助手绑定）。审批放行不会绕过任何一条授权，
 *   所以这里绝不根据"免审批"去放宽 grants。
 */
internal fun createDeviceControlTools(
    bridge: DeviceControlBridge?,
    settings: LocalMcpSettingsStore?,
    assistantId: Uuid,
    /** 用户配置的审批覆盖表；工具名 -> 是否需要审批。空表 = 全部用默认值。 */
    approvalOverrides: Map<String, Boolean> = emptyMap(),
): List<Tool> {
    if (bridge == null || settings == null) return emptyList()
    fun authorized(): Boolean {
        val current = settings.current()
        return current.allowDevice && current.assistantId == assistantId.toString() &&
            bridge.preferences.current().enabled
    }
    if (!authorized()) return emptyList()
    return bridge.tools().map { descriptor ->
        val properties = descriptor.inputSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (descriptor.inputSchema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
        // 审批判定按快照的覆盖表求值，而不是在创建时固化一个布尔值：
        // 用户会话中途改设置，下一次 needsApproval 调用就看得到新值。
        Tool(
            name = descriptor.name,
            description = descriptor.description,
            parameters = { InputSchema.Obj(properties, required) },
            // Snapshot and stateful gestures share the device: do not overlap UI actions.
            executionMode = ToolExecutionMode.SERIAL,
            repeatable = descriptor.readOnly,
            needsApproval = {
                ToolApprovalPolicy.resolve(
                    toolName = descriptor.name,
                    overrides = approvalOverrides,
                    default = ToolApprovalPolicy.deviceToolApprovalDefault(descriptor.name),
                )
            },
            execute = { args ->
                check(authorized()) { "Device access was disabled or assigned to another assistant" }
                // 兜底：免审批不等于免授权。审批表只决定"要不要问用户"，
                // 真正的授权（enabled / allowRead / allowAct / allowGlobalActions / 包名白名单）
                // 由 bridge 在 call() 里按动作能力逐条判定，这里不再自作主张放宽任何一条。
                listOf(UIMessagePart.Text(bridge.call(descriptor.name, args.jsonObject).toString()))
            },
        )
    }
}
