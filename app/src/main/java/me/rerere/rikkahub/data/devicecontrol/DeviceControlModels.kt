package me.rerere.rikkahub.data.devicecontrol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 设备操控的授权粒度。读（看树/列 App）、写（点、长按、填字、手势）、全局动作（返回/桌面/最近任务/启动 App）
 * 三者分开授权：用户只想给"看一眼"就不必开写。
 */
enum class DeviceCapability {
    READ,
    ACT,
    GLOBAL,
}

/** 失败原因。机器可读，UI/主代理按 code 自己翻译，不把英文 message 当结论。 */
enum class DeviceErrorCode(val wire: String) {
    SERVICE_DISABLED("service_disabled"),
    NOT_AUTHORIZED("not_authorized"),
    PACKAGE_NOT_ALLOWED("package_not_allowed"),
    CAPABILITY_DISABLED("capability_disabled"),
    STALE_SNAPSHOT("stale_snapshot"),
    NO_SNAPSHOT("no_snapshot"),
    UNKNOWN_NODE("unknown_node"),
    SENSITIVE_FIELD("sensitive_field"),
    BAD_ARGUMENT("bad_argument"),
    ACTION_FAILED("action_failed"),
    UNKNOWN_TOOL("unknown_tool"),
    PACKAGE_NOT_FOUND("package_not_found"),
}

/**
 * 设备操控的本地授权，默认全关。
 *
 * 存在 App 私有目录的 JSON 里（见 DeviceControlPreferences），不进 Settings：既避免和共享 Settings.kt
 * 的合并冲突，也让"关掉开关"只影响这一份文件。
 */
@Serializable
data class DeviceControlGrants(
    val enabled: Boolean = false,
    /** 允许操作的包名。空集 = 一个都不允许，包括坐标手势。 */
    val allowedPackages: Set<String> = emptySet(),
    val allowRead: Boolean = true,
    val allowAct: Boolean = false,
    val allowGlobalActions: Boolean = false,
    /** 单次抓树最多保留多少个节点；超过就截断并标记 truncated。 */
    val maxNodesPerSnapshot: Int = 400,
    /** 一页返回多少个节点。 */
    val pageSize: Int = 60,
    /** 单个节点的文本/描述最大字符数。 */
    val maxCharsPerNode: Int = 200,
    /** 快照多久后算过期（毫秒）。 */
    val snapshotTtlMs: Long = 30_000,
) {
    /** 包名做过去空处理，避免 "" 混进白名单。 */
    fun sanitized(): DeviceControlGrants = copy(
        allowedPackages = allowedPackages.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        maxNodesPerSnapshot = maxNodesPerSnapshot.coerceIn(20, 2_000),
        pageSize = pageSize.coerceIn(5, 200),
        maxCharsPerNode = maxCharsPerNode.coerceIn(20, 2_000),
        snapshotTtlMs = snapshotTtlMs.coerceIn(1_000, 300_000),
    )
}

/** 一个可被点击/读取的节点。id 是本次快照内的子树路径，跨快照不复用。 */
data class DeviceNode(
    val id: String,
    val className: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val sensitive: Boolean = false,
    val textTruncated: Boolean = false,
    val children: Int = 0,
) {
    /** 过期判定的指纹：结构和位置。文本会自己变（时钟、输入框），不参与比对。 */
    val fingerprint: String get() = "$className|$left,$top,$right,$bottom"

    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("class", JsonPrimitive(className))
        put("text", JsonPrimitive(text))
        put("description", JsonPrimitive(contentDescription))
        put(
            "bounds",
            buildJsonObject {
                put("left", JsonPrimitive(left))
                put("top", JsonPrimitive(top))
                put("right", JsonPrimitive(right))
                put("bottom", JsonPrimitive(bottom))
            },
        )
        put("clickable", JsonPrimitive(clickable))
        put("long_clickable", JsonPrimitive(longClickable))
        put("editable", JsonPrimitive(editable))
        put("scrollable", JsonPrimitive(scrollable))
        put("sensitive", JsonPrimitive(sensitive))
        put("text_truncated", JsonPrimitive(textTruncated))
    }
}

/** 一次抓树的结果（整棵树），分页在 [DeviceSnapshot] 上做。 */
data class CapturedTree(
    val packageName: String,
    val nodes: List<DeviceNode>,
    val totalNodes: Int,
    val truncated: Boolean,
    val capturedAtMillis: Long,
)

/** 返回给调用方的一页快照。 */
data class DeviceSnapshot(
    val id: String,
    val packageName: String,
    val capturedAtMillis: Long,
    val page: Int,
    val pageSize: Int,
    val totalNodes: Int,
    val nodes: List<DeviceNode>,
    val hasMore: Boolean,
    val truncated: Boolean,
    /** 抓树时的 AccessibilityWindowInfo.id。写动作前必须与当前窗口一致，防止"异屏同路径"误点。 */
    val windowId: Int = -1,
    val windowTitle: String = "",
)

/** 工具描述：主代理把它直接映射成 MCP tool 或内部 Tool。 */
data class DeviceToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
)
