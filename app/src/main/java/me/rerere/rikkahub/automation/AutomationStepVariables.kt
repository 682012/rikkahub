package me.rerere.rikkahub.automation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 步骤之间的真实数据接续：把前面步骤的输出绑到后面步骤的参数里。
 *
 * 为什么必须要有：设备桥的写动作要 `snapshot_id` + `node_id`，这两个都是**运行时**值。
 * 没有变量引用时，recipe 只能写死参数——那就只能在"节点路径和快照 id 恰好不变"的情况下工作，
 * 属于静态脚本，不是通用自动化。这里给出最小但够用的接续方式：
 *
 * ```
 * {"tool":"device_snapshot","arguments":{}}
 * {"tool":"device_tap_node","arguments":{"snapshot_id":"{{0.snapshot_id}}","node_id":"{{0.nodes.2.id}}"}}
 * ```
 *
 * 规则（刻意简单，可预测）：
 * - `{{<步骤下标>.<字段路径>}}`，下标从 0 开始，字段路径用 `.` 分段，数字段视为数组下标；
 * - 只引用**已经成功执行过**的步骤输出，引用未来步骤/不存在的字段 → 整条参数原样保留并返回错误，
 *   由 engine 记为该步骤失败（不猜、不静默变成空串）；
 * - 字符串整体就是一个占位符时保留原始 JSON 类型（数字还是数字），否则按字符串插值；
 * - 不做表达式求值，不做条件/循环。
 */
object AutomationStepVariables {

    private val pattern = Regex("""\{\{\s*([0-9]+)\s*\.\s*([A-Za-z0-9_.\-]+)\s*}}""")

    /** 解析结果：要么全部替换成功，要么带上未解析的占位符说明。 */
    data class Resolution(
        val arguments: JsonObject,
        val unresolved: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = unresolved.isEmpty()
    }

    fun resolve(arguments: JsonObject, outputs: List<JsonObject>): Resolution {
        val unresolved = mutableListOf<String>()
        val resolved = resolveObject(arguments, outputs, unresolved)
        return Resolution(resolved, unresolved.distinct())
    }

    private fun resolveObject(
        source: JsonObject,
        outputs: List<JsonObject>,
        unresolved: MutableList<String>,
    ): JsonObject = buildJsonObject {
        source.forEach { (key, value) -> put(key, resolveElement(value, outputs, unresolved)) }
    }

    private fun resolveElement(
        element: JsonElement,
        outputs: List<JsonObject>,
        unresolved: MutableList<String>,
    ): JsonElement = when (element) {
        is JsonObject -> resolveObject(element, outputs, unresolved)
        is JsonArray -> buildJsonArray { element.forEach { add(resolveElement(it, outputs, unresolved)) } }
        is JsonPrimitive -> resolvePrimitive(element, outputs, unresolved)
    }

    private fun resolvePrimitive(
        primitive: JsonPrimitive,
        outputs: List<JsonObject>,
        unresolved: MutableList<String>,
    ): JsonElement {
        if (!primitive.isString) return primitive
        val raw = primitive.content
        val matches = pattern.findAll(raw).toList()
        if (matches.isEmpty()) return primitive

        // 整串就是一个占位符：保留原始类型（例如节点下标是数字）
        if (matches.size == 1 && matches.first().range.first == 0 && matches.first().range.last == raw.lastIndex) {
            val match = matches.first()
            val value = lookup(match.groupValues[1].toIntOrNull(), match.groupValues[2], outputs)
            if (value == null) {
                unresolved += match.value
                return primitive
            }
            return value
        }

        val builder = StringBuilder()
        var cursor = 0
        for (match in matches) {
            builder.append(raw, cursor, match.range.first)
            val value = lookup(match.groupValues[1].toIntOrNull(), match.groupValues[2], outputs)
            if (value == null) {
                unresolved += match.value
                builder.append(match.value)
            } else {
                builder.append((value as? JsonPrimitive)?.content ?: value.toString())
            }
            cursor = match.range.last + 1
        }
        builder.append(raw, cursor, raw.length)
        return JsonPrimitive(builder.toString())
    }

    private fun lookup(stepIndex: Int?, path: String, outputs: List<JsonObject>): JsonElement? {
        if (stepIndex == null) return null
        val output = outputs.getOrNull(stepIndex) ?: return null
        var current: JsonElement = output
        for (segment in path.split('.')) {
            if (segment.isEmpty()) return null
            current = when (val node = current) {
                is JsonObject -> node[segment] ?: return null
                is JsonArray -> node.getOrNull(segment.toIntOrNull() ?: return null) ?: return null
                else -> return null
            }
        }
        return current
    }
}
