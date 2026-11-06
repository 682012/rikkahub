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

/** Same device bridge as MCP; local assistants cannot bypass its per-app grants. */
internal fun createDeviceControlTools(
    bridge: DeviceControlBridge?,
    settings: LocalMcpSettingsStore?,
    assistantId: Uuid,
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
        Tool(
            name = descriptor.name,
            description = descriptor.description,
            parameters = { InputSchema.Obj(properties, required) },
            // Snapshot and stateful gestures share the device: do not overlap UI actions.
            executionMode = ToolExecutionMode.SERIAL,
            repeatable = descriptor.readOnly,
            needsApproval = { !descriptor.readOnly },
            execute = { args ->
                check(authorized()) { "Device access was disabled or assigned to another assistant" }
                listOf(UIMessagePart.Text(bridge.call(descriptor.name, args.jsonObject).toString()))
            },
        )
    }
}
