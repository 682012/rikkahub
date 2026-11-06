package me.rerere.rikkahub.data.mcpserver

import kotlinx.serialization.json.JsonObject

/**
 * 本机 MCP 工具的能力分类。设置页的授权开关就是按这个维度开关的：
 *
 * - [READ]：只读（会话列表/消息/归档/任务查询等），默认开；
 * - [MUTATION]：改 App 内数据（发消息、改标题、归档、删会话等），默认关；
 * - [MODEL]：触发模型请求（生成回复、摘要等），默认关；
 * - [DEVICE]：跨 App 设备操作（依赖无障碍授权），默认关；
 * - [AUTOMATION]：跑本地自动化配方，默认关。
 *
 * 分类只决定"是否暴露"，真正的副作用仍由宿主适配器在执行前再检查一次。
 */
enum class LocalMcpToolCategory { READ, MUTATION, MODEL, DEVICE, AUTOMATION }

/**
 * 一个暴露给外部 MCP 客户端的工具。
 *
 * 这个类型**只在当前文件定义一次**，Protocol / App 适配器 / UI 都 import 它。
 * 前四个参数与 `LocalMcpProtocol.kt` 里原有的临时定义保持位置兼容（name, description, inputSchema, readOnly），
 * [category] 追加在最后，供设置页按能力开关过滤。
 */
data class LocalMcpTool(
    val name: String,
    val description: String = "",
    val inputSchema: JsonObject = JsonObject(emptyMap()),
    val readOnly: Boolean = true,
    val category: LocalMcpToolCategory = if (readOnly) {
        LocalMcpToolCategory.READ
    } else {
        LocalMcpToolCategory.MUTATION
    },
)

/**
 * 主代理实现的 App 内工具适配器。
 *
 * - [tools] 返回当前真实可用的工具清单（已按当前设置过滤过更好，未过滤也没关系，
 *   [LocalMcpSettings.visibleTools] 会再按 [LocalMcpToolCategory] 过一遍）；
 * - [call] 真正执行，返回结构化 JSON。**不要抛异常**，统一返回
 *   `{"ok":false,"error":"<code>","message":"..."}`，Protocol 会原样回给客户端；
 * - 需要用户审批的动作在没有 UI 的情况下不能自动通过，返回 `ok=false` + 说明；
 * - 长执行（生成、设备长动作）走宿主自己的任务入口，立刻返回 taskId，不要占住 HTTP 请求。
 */
interface LocalMcpToolHost {
    suspend fun tools(): List<LocalMcpTool>
    suspend fun call(name: String, arguments: JsonObject): JsonObject
}
