package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.rikkahub.data.ai.tools.MCP_CALL_TOOL_NAME
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 网关代理调用的真实目标工具。
 *
 * MCP 目录过大时，外部工具会被压缩成 `tool_search` + `tool_call` 两件套，模型真正调用的
 * `mcp__files__read_file` 只出现在 `tool_call` 的**参数里**。凡是"这是哪个工具 / 传了什么参数"
 * 的判断都必须先过这里，否则聊天卡片、审批悬浮窗、导出图片会全部显示成 `tool_call` 加一层嵌套 JSON，
 * 排查并发与失败时只能靠猜。
 *
 * 注意名字可能被 [me.rerere.rikkahub.data.ai.tools.uniqueToolName] 去重成 `tool_call_2`，
 * 所以判断用前缀而不是相等。
 *
 * 这个文件刻意不依赖任何 Compose 类型：它是纯解析逻辑，单元测试可以随便调。
 *
 * @return 真实工具名与内层 arguments；不是网关调用（或参数坏掉）时为 null
 */
internal fun UIMessagePart.Tool.effectiveTarget(): Pair<String, JsonElement>? {
    if (!toolName.isMcpGatewayCall()) return null
    val params = runCatching { inputAsJson().jsonObjectOrNull }.getOrNull() ?: return null
    val name = params["name"]?.jsonPrimitiveOrNull?.contentOrNull?.trim().orEmpty()
    if (name.isEmpty()) return null
    return name to (params["arguments"] ?: JsonObject(emptyMap()))
}

/** 展示用的工具名：网关调用取被代理的真实工具名，其它原样返回。 */
internal fun UIMessagePart.Tool.effectiveToolName(): String = effectiveTarget()?.first ?: toolName

/** 展示用的入参：网关调用取内层 arguments，其它原样返回。 */
internal fun UIMessagePart.Tool.effectiveArguments(): JsonElement =
    effectiveTarget()?.second ?: inputAsJson()

/** `tool_call`，以及撞车后退化出来的 `tool_call_2`。 */
private fun String.isMcpGatewayCall(): Boolean =
    this == MCP_CALL_TOOL_NAME || startsWith("${MCP_CALL_TOOL_NAME}_")
