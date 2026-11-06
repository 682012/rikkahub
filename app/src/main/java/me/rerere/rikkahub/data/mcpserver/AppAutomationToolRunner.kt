package me.rerere.rikkahub.data.mcpserver

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.automation.AutomationToolResult
import me.rerere.rikkahub.automation.AutomationToolOutcomes
import me.rerere.rikkahub.automation.AutomationToolRunner
import me.rerere.rikkahub.automation.AutomationToolSpec
import me.rerere.rikkahub.data.devicecontrol.DeviceToolCatalog

/**
 * 本地自动化的工具执行器：把 recipe 的步骤接到和 MCP 完全同一个 [AppMcpToolHost.call]。
 *
 * 刻意与 MCP 服务器的 **enabled / 端口 / token 无关**（关掉 MCP 端口，自动化照样能本地跑），
 * 但**相同的每能力开关、绑定助手、设备白名单**一律照旧：
 * - 开关来自 [gatesSource]（每次调用重新读，用户中途收权立刻生效）；
 * - 绑定助手是唯一的会话范围，recipe 参数里写 `assistant_id` 会被 host 直接拒绝；
 * - 设备动作仍要过设备桥自己的授权与包白名单。
 *
 * 两条额外红线：
 * 1. **不递归**：`automation_*` 工具不出现在 [tools] 里，直接调用也会被 [execute] 拒绝。
 *    自动化去改自己的配方会形成不可控的自激循环。
 * 2. **不代替用户批准**：所有 [AutomationToolSpec] 都是 `needsApproval = false`，因为这里的每个工具
 *    的"批准"就是用户在设置里打开的那个开关本身；需要逐次审批的动作在这个端点里根本没有暴露。
 *
 * 幂等：MCP 侧对写工具要求 `idempotency_key`。自动化每跑一步是新的一次真实意图，这里自动生成
 * 一次性 key；自动化路径的重放保护由 `AutomationEngine` / `AutomationRunGuard` 负责
 * （中断的写步骤会被判为 `unknown_side_effect` 并阻塞），不靠账本回放。
 */
class AppAutomationToolRunner(
    private val host: AppMcpToolHost,
    private val gatesSource: AppMcpGatesSource,
) : AutomationToolRunner {

    override fun tools(): List<AutomationToolSpec> {
        val gates = gatesSource.current()
        val appTools = AppMcpToolCatalog.visible(gates)
            .filter { spec -> spec.gates.none { it == AppMcpGate.AUTOMATION } }
            .map { spec -> AutomationToolSpec(name = spec.name, description = spec.description, readOnly = spec.readOnly) }
        if (!gates.grants(AppMcpGate.DEVICE)) return appTools
        val deviceTools = runCatching { DeviceToolCatalog.descriptors() }.getOrDefault(emptyList())
            .map { descriptor ->
                AutomationToolSpec(
                    name = descriptor.name,
                    description = descriptor.description,
                    readOnly = descriptor.readOnly,
                )
            }
        return appTools + deviceTools + privilegedTools(gates)
    }

    /**
     * 高权限中心的 `android_*` 工具（deviceprivilege）。
     *
     * 之前这里只做到了"按名字执行能到达"，步骤下拉里看不见 —— 因为拿不到 suspend 的授权查询。
     * 现在用 [HighPrivilegeMcpProxy.automationCatalog]（非 suspend，内部仍然现查控制器，
     * 不缓存、不弹框），过滤条件与执行时**同一口径**：
     *
     * - MCP/自动化自己的开关：`allowRead` 是底座，`allowDevice` 必须显式打开；
     * - 必须有绑定助手（null/空白 ≠ 全部助手）；
     * - 权限中心自动化已开启，且绑定的是**同一个**助手。
     *
     * 写开关与 Shizuku 就绪刻意不在这里过滤：它们是每次调用都要重核的门禁，先让用户能把配方写出来；
     * 真跑的时候该拒的照拒（`not_granted`，不重试、不降级、不 fallback）。
     * `readOnly` 直接取工具自己的声明，避免把 `android_settings_get` 误判成写、把后续步骤卡成未知副作用。
     */
    private fun privilegedTools(gates: AppMcpGates): List<AutomationToolSpec> {
        if (!gates.read || !gates.grants(AppMcpGate.DEVICE)) return emptyList()
        val proxy = host.privilegedProxy() ?: return emptyList()
        val bound = gates.boundAssistantId?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return runCatching { proxy.automationCatalog(bound) }.getOrDefault(emptyList()).map { descriptor ->
            AutomationToolSpec(
                name = descriptor.name,
                description = descriptor.description,
                readOnly = descriptor.readOnly,
            )
        }
    }

    override suspend fun execute(tool: String, arguments: JsonObject): AutomationToolResult {
        if (tool.startsWith("automation_")) {
            return AutomationToolResult(
                ok = false,
                message = "Automation recipes may not call automation_* tools (recursion is not allowed).",
                output = buildJsonObject {
                    put("ok", false)
                    put("error", "tool_forbidden")
                },
            )
        }
        val gates = gatesSource.current()
        val privileged = host.privilegedProxy()
        // 只做静态元数据读取（不是授权）：用来判"这个名字是不是写"，以及给结果分类。
        val privilegedTool = privileged?.descriptorOrNull(tool)
        val known = AppMcpToolCatalog.find(tool) != null ||
            runCatching { DeviceToolCatalog.descriptors().any { it.name == tool } }.getOrDefault(false) ||
            // 高权限中心工具（deviceprivilege）：只认名字白名单，其余仍然 unknown_tool。
            (privileged?.handles(tool) == true)
        if (!known) {
            // 清单外的工具一律拒绝：不会把参数丢给"所有 App 工具"试一遍。
            return AutomationToolResult(
                ok = false,
                message = "Unknown tool: $tool",
                output = buildJsonObject {
                    put("ok", false)
                    put("error", "unknown_tool")
                },
            )
        }
        val spec = AppMcpToolCatalog.find(tool)
        val requiresKey = spec?.requiresIdempotencyKey
            ?: (tool.startsWith("device_") && isDeviceWrite(tool)) ||
            (privileged?.requiresIdempotencyKey(tool) == true)
        val withKey = if (requiresKey && arguments[AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD] == null) {
            JsonObject(
                arguments + (
                    AppMcpToolCatalog.IDEMPOTENCY_KEY_FIELD to
                        kotlinx.serialization.json.JsonPrimitive("auto-${UUID.randomUUID()}")
                    ),
            )
        } else {
            arguments
        }
        if (spec != null && !gates.allows(spec)) {
            return AutomationToolResult(
                ok = false,
                message = "${spec.name} is disabled by the current local MCP/automation switches.",
                output = buildJsonObject {
                    put("ok", false)
                    put("error", "not_granted")
                },
            )
        }
        val result = host.call(tool, withKey)
        // 结果归类（ok / 未执行 / 未知副作用 / 需要审批）走纯函数，别在这里拍死 ok=false。
        // readOnly 读不出来时按"写"处理：宁可多要一次确认，也不能漏掉可能的副作用。
        // android_* 的只读标记取工具自己的声明（静态元数据，不是授权）；最后兜底仍是"当写处理"。
        return AutomationToolOutcomes.fromHostResult(
            tool = tool,
            readOnly = spec?.readOnly ?: privilegedTool?.readOnly ?: deviceReadOnly(tool),
            result = result,
        )
    }

    /**
     * 保存配方时固化的权限域 = 当前绑定助手（没绑定就是"未绑定"域）。
     * 只读绑定，不动任何授权开关：用户已经给了设备授权就继续用，不会因为固化域把人踢掉。
     */
    override fun currentScope(): String? {
        val gates = runCatching { gatesSource.current() }.getOrNull() ?: return null
        val bound = gates.boundAssistantId?.trim()?.takeIf { it.isNotEmpty() }
        return bound ?: AppMcpRecipeOwnership.UNBOUND_SCOPE
    }

    /**
     * 运行中每步校验：固化域必须还是当前的绑定助手。
     * 读不到绑定时不误判（返回 true）；绑定真的换了就返回 false，由引擎停下后面的步骤。
     */
    override fun validateRunScope(scope: String?): Boolean {
        if (scope == null) return true
        val current = currentScope() ?: return true
        return scope == current
    }

    private fun deviceReadOnly(tool: String): Boolean? = runCatching {
        DeviceToolCatalog.descriptors().firstOrNull { it.name == tool }?.readOnly
    }.getOrNull()

    private fun isDeviceWrite(tool: String): Boolean = runCatching {
        DeviceToolCatalog.descriptors().firstOrNull { it.name == tool }?.readOnly == false
    }.getOrDefault(true)
}
