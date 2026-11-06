package me.rerere.rikkahub.data.devicecontrol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 工具清单与 JSON Schema。描述里写清楚"要用户先在系统里启用"，避免模型以为随时可点。
 * readOnly 用于内部审批策略和自动化 recipe 校验（只读步骤不需要额外确认）。
 */
object DeviceToolCatalog {

    private fun schema(builder: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { builder() }
            put("additionalProperties", false)
        }

    private fun JsonObjectBuilder.string(name: String, description: String) {
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
        }
    }

    private fun JsonObjectBuilder.integer(name: String, description: String) {
        putJsonObject(name) {
            put("type", "integer")
            put("description", description)
        }
    }

    private fun JsonObjectBuilder.number(name: String, description: String) {
        putJsonObject(name) {
            put("type", "number")
            put("description", description)
        }
    }

    private fun nodeActionSchema(extraText: Boolean): JsonObject = schema {
        string("snapshot_id", "Snapshot id returned by device_snapshot; must still be the latest snapshot.")
        string("node_id", "Node id from that snapshot, e.g. 0/2/1.")
        if (extraText) string("text", "Text to set into the editable node.")
    }

    fun descriptors(): List<DeviceToolDescriptor> = listOf(
        DeviceToolDescriptor(
            name = DeviceAction.SNAPSHOT.wire,
            description = "Read the accessibility tree of the CURRENT foreground window. Requires the user to have enabled " +
                "RikkaHub's accessibility service in system settings and whitelisted that app. " +
                "Returns a snapshot_id plus paged nodes (password fields are redacted). The snapshot goes stale quickly; " +
                "re-read it after every action.",
            inputSchema = schema {
                integer("page", "Zero-based page index, default 0.")
                integer("page_size", "Nodes per page, default from user settings.")
            },
            readOnly = true,
        ),
        DeviceToolDescriptor(
            name = DeviceAction.LIST_APPS.wire,
            description = "List launchable apps installed on this device (package + label). Read-only.",
            inputSchema = schema {
                string("query", "Optional case-insensitive filter on package name or label.")
                integer("limit", "Max results, default 60, max 200.")
            },
            readOnly = true,
        ),
        DeviceToolDescriptor(
            name = DeviceAction.TAP_NODE.wire,
            description = "Click a node from the latest snapshot. Fails with stale_snapshot/unknown_node instead of " +
                "guessing coordinates; password fields are refused.",
            inputSchema = nodeActionSchema(extraText = false),
            readOnly = false,
        ),
        DeviceToolDescriptor(
            name = DeviceAction.LONG_PRESS_NODE.wire,
            description = "Long-press a node from the latest snapshot. Password fields are refused.",
            inputSchema = nodeActionSchema(extraText = false),
            readOnly = false,
        ),
        DeviceToolDescriptor(
            name = DeviceAction.SET_TEXT.wire,
            description = "Set text into an editable node from the latest snapshot. Password fields are refused.",
            inputSchema = nodeActionSchema(extraText = true),
            readOnly = false,
        ),
        DeviceToolDescriptor(
            name = DeviceAction.TAP.wire,
            description = "Tap screen coordinates (pixels) on the current foreground app. That app must be whitelisted.",
            inputSchema = schema {
                number("x", "Screen x in pixels.")
                number("y", "Screen y in pixels.")
            },
            readOnly = false,
        ),
        DeviceToolDescriptor(
            name = DeviceAction.SWIPE.wire,
            description = "Swipe from one point to another on the current foreground app.",
            inputSchema = schema {
                number("x1", "Start x in pixels.")
                number("y1", "Start y in pixels.")
                number("x2", "End x in pixels.")
                number("y2", "End y in pixels.")
                integer("duration_ms", "Gesture duration, 50..5000 ms, default 300.")
            },
            readOnly = false,
        ),
        DeviceToolDescriptor(
            name = DeviceAction.BACK.wire,
            description = "Global back action.",
            inputSchema = schema { },
            readOnly = false,
        ),
        DeviceToolDescriptor(
            name = DeviceAction.HOME.wire,
            description = "Go to the home screen.",
            inputSchema = schema { },
            readOnly = false,
        ),
        DeviceToolDescriptor(
            name = DeviceAction.RECENTS.wire,
            description = "Open the recent apps switcher.",
            inputSchema = schema { },
            readOnly = false,
        ),
        DeviceToolDescriptor(
            name = DeviceAction.LAUNCH_APP.wire,
            description = "Launch an installed app by package name. The target package must be whitelisted.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { string("package", "Package name, e.g. com.android.settings.") }
                putJsonArray("required") { add("package") }
                put("additionalProperties", false)
            },
            readOnly = false,
        ),
    )
}
