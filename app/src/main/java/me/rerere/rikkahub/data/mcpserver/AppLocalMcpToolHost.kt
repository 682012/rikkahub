package me.rerere.rikkahub.data.mcpserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.deviceprivilege.HighPrivilegeMcpProxy

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
    /**
     * m1 高权限中心工具（deviceprivilege）的代理面。`null` = 这个 build 不暴露它们。
     * 只有同时满足「MCP 开了 allowDevice」「MCP 绑定的助手 == 权限中心绑定的助手」「权限中心自动化已启用」
     * 才会出现在 `tools()` 里；`call()` 每次都重新核这三件事，不看旧快照。
     */
    private val highPrivilegeHost: HighPrivilegeMcpProxy? = null,
    /** 本机 MCP 设置（绑定助手 + allowDevice/allowRead）。没给就不暴露高权限工具。 */
    private val settingsStore: LocalMcpSettingsStore? = null,
) : LocalMcpToolHost {

    override suspend fun tools(): List<LocalMcpTool> =
        host.tools().map { exposed ->
            LocalMcpTool(
                name = exposed.name,
                description = exposed.description,
                inputSchema = exposed.inputSchema,
                readOnly = exposed.readOnly,
                category = categoryOf(exposed),
            )
        } + privilegedTools()

    override suspend fun call(name: String, arguments: JsonObject): JsonObject {
        val proxy = highPrivilegeHost
        if (proxy != null && proxy.handles(name)) return callPrivileged(proxy, name, arguments)
        return host.call(name, arguments)
    }

    /** 高权限工具的暴露条件：能力开关 + 绑定助手一致 + 权限中心已启用并绑定同一助手。 */
    private suspend fun privilegedTools(): List<LocalMcpTool> {
        val proxy = highPrivilegeHost ?: return emptyList()
        val bound = boundAssistant() ?: return emptyList()
        return proxy.exposedTools(bound).map { descriptor ->
            LocalMcpTool(
                name = descriptor.name,
                description = descriptor.description,
                inputSchema = descriptor.inputSchema,
                readOnly = descriptor.readOnly,
                category = LocalMcpToolCategory.DEVICE,
            )
        }
    }

    private suspend fun callPrivileged(
        proxy: HighPrivilegeMcpProxy,
        name: String,
        arguments: JsonObject,
    ): JsonObject {
        val bound = boundAssistant()
        if (bound == null) {
            return buildJsonObject {
                put("ok", false)
                put("error", "no_bound_assistant")
                put(
                    "message",
                    "No assistant is bound in the local MCP settings, so on-device privileges are disabled.",
                )
            }
        }
        return proxy.callAsBoundAssistant(bound, name, arguments)
    }

    /**
     * 当前允许用高权限工具的助手 id；不允许时 null。
     * `read` 是所有能力的底座（没读权限就不该有写权限），`allowDevice` 仍然照旧必须打开。
     */
    private fun boundAssistant(): String? {
        val settings = settingsStore?.current() ?: return null
        if (!settings.allowRead || !settings.allowDevice) return null
        return settings.assistantId?.trim()?.takeIf { it.isNotEmpty() }
    }

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
