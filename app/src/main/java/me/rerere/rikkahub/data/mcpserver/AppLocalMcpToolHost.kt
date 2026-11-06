package me.rerere.rikkahub.data.mcpserver

import kotlinx.serialization.json.JsonObject

/**
 * 唯一依赖 MCP 服务器契约（`LocalMcp*`）的适配层。契约改了只动这个文件。
 *
 * 服务器侧的边界（本层不重复实现）：`enabled`、端口、token 鉴权、请求体上限、并发上限。
 * 本层只负责：能力开关二次核验、绑定助手范围、归属校验、写幂等、写串行。
 *
 * 分类映射用工具的 gates 决定，而不是简单按 `readOnly` 猜：
 * `chat_send` / `compression_start` 是 [LocalMcpToolCategory.MODEL]（会触发计费模型请求），
 * 设备代理工具是 [LocalMcpToolCategory.DEVICE]，`automation_*` 是 [LocalMcpToolCategory.AUTOMATION]。
 * 服务器的 `visibleTools()` 就按这个分类过滤。
 */
class AppLocalMcpToolHost(
    private val host: AppMcpToolHost,
) : LocalMcpToolHost {

    override suspend fun tools(): List<LocalMcpTool> = host.tools().map { exposed ->
        LocalMcpTool(
            name = exposed.name,
            description = exposed.description,
            inputSchema = exposed.inputSchema,
            readOnly = exposed.readOnly,
            category = categoryOf(exposed),
        )
    }

    override suspend fun call(name: String, arguments: JsonObject): JsonObject = host.call(name, arguments)

    private fun categoryOf(tool: AppMcpExposedTool): LocalMcpToolCategory {
        val spec = AppMcpToolCatalog.find(tool.name)
        return when {
            // 清单外只可能是设备桥代理出来的工具
            spec == null -> LocalMcpToolCategory.DEVICE
            AppMcpGate.AUTOMATION in spec.gates -> LocalMcpToolCategory.AUTOMATION
            AppMcpGate.MODEL_REQUESTS in spec.gates -> LocalMcpToolCategory.MODEL
            !spec.readOnly -> LocalMcpToolCategory.MUTATION
            else -> LocalMcpToolCategory.READ
        }
    }
}

/**
 * 本机设置 → 开关快照的唯一映射点。DI 里这样用：
 *
 * ```kotlin
 * val store = LocalMcpSettingsStore(context)
 * AppMcpGatesSource {
 *     val s = store.state.value
 *     appMcpGates(
 *         boundAssistantId = s.assistantId,
 *         allowRead = s.allowRead,
 *         allowMutations = s.allowMutations,
 *         allowModelRequests = s.allowModelRequests,
 *         allowDevice = s.allowDevice,
 *         allowAutomation = s.allowAutomation,
 *     )
 * }
 * ```
 *
 * `allowRead` 是所有能力的底座：`read=false` 时 `mutations/modelRequests` 一律不生效
 * （没读权限就不该有写权限）。`assistantId = null / 空白` 表示不暴露任何会话能力，**不是**全助手。
 */
fun appMcpGates(
    boundAssistantId: String?,
    allowRead: Boolean,
    allowMutations: Boolean,
    allowModelRequests: Boolean,
    allowDevice: Boolean,
    allowAutomation: Boolean,
): AppMcpGates = AppMcpGates(
    boundAssistantId = boundAssistantId?.trim()?.takeIf { it.isNotEmpty() },
    read = allowRead,
    mutations = allowMutations,
    modelRequests = allowModelRequests,
    device = allowDevice,
    automation = allowAutomation,
)
