package me.rerere.ai.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json

// 模型没给 toolCallId 时补的 id 前缀；只在本机使用，回给 provider 的仍是普通字符串 id
private const val GENERATED_TOOL_CALL_ID_PREFIX = "tool-call"

/** Object key order is not significant; arrays and primitive representations stay intact. */
internal fun canonicalToolArguments(input: String): String? = runCatching {
    canonicalToolJson(json.parseToJsonElement(input.ifBlank { "{}" })).toString()
}.getOrNull()

internal fun canonicalToolJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalToolJson(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalToolJson))
    else -> value
}

/**
 * 一次运行内复用的规范化参数结果。
 *
 * 模型的每一步都会把同一条历史里所有工具调用的参数重新规范化一遍，而参数越长（比如写入整个文件的内容）
 * 这一步越贵；结果只取决于原始输入的字符串，同一次运行里重复算没有意义。
 *
 * 缓存按原始输入做键、有上限，超过上限时整体清空而不是逐条淘汰：参数种类数本来就不多，
 * 长运行也不会让缓存无限涨（存的是已经存在于消息里的字符串引用，不重复保存内容）。
 */
internal class CanonicalArgumentsCache(private val maxEntries: Int = 256) {
    // 解析失败时存 null：原始输入仍会每一步重复出现，不记住就会每次都重新 parse 一遍
    private val entries = HashMap<String, String?>()

    fun canonical(input: String): String? {
        if (entries.containsKey(input)) return entries[input]
        val canonical = canonicalToolArguments(input)
        if (entries.size >= maxEntries) entries.clear()
        entries[input] = canonical
        return canonical
    }
}

/** Only local, explicit metadata permits overlapping calls. Unknown tools remain barriers. */
internal fun ToolExecutionMode.allowsParallelExecution() = this != ToolExecutionMode.SERIAL

/**
 * 把本步新增的 part 接到已有内容后面之前，先保证新工具调用的 id 在这条助手消息里唯一。
 *
 * 不同模型、不同 step 之间 toolCallId 可能重复，有的模型干脆返回空 id；工具结果按 id 写回，
 * 重复的 id 会让后执行的调用覆盖之前步骤已经执行完的结果。这里只处理冲突和空 id：正常 id 原样保留，
 * 同一次响应里重复或为空的调用各拿一个新 id，非工具 part 和 metadata 一律不动。
 */
internal fun normalizeNewToolCallIds(
    existingParts: List<UIMessagePart>,
    newParts: List<UIMessagePart>,
): List<UIMessagePart> {
    if (newParts.none { it is UIMessagePart.Tool }) return newParts
    // 已有的 id 都占住，包括 provider 侧执行的工具，避免同一个 id 在请求里指两个调用
    val taken = existingParts.mapNotNullTo(LinkedHashSet()) { it.reservedToolCallId() }
    var changed = false
    val normalized = newParts.map { part ->
        val tool = part as? UIMessagePart.Tool ?: return@map part
        // 正常 id：非空，且这条消息里还没有人用过
        if (tool.toolCallId.isNotBlank() && taken.add(tool.toolCallId)) return@map tool
        changed = true
        val fresh = freshToolCallId(tool.toolCallId, taken)
        taken += fresh
        tool.copy(toolCallId = fresh)
    }
    return if (changed) normalized else newParts
}

// 保留原来的 id 方便对照，只追加序号；候选逐个检查，不会和已有或本步更早的调用撞上
private fun freshToolCallId(preferred: String, taken: Set<String>): String {
    val base = preferred.trim().ifBlank { GENERATED_TOOL_CALL_ID_PREFIX }
    var suffix = 1
    while ("$base-$suffix" in taken) suffix++
    return "$base-$suffix"
}

private fun UIMessagePart.reservedToolCallId(): String? = when (this) {
    is UIMessagePart.Tool -> toolCallId
    is UIMessagePart.ServerTool -> toolCallId
    else -> null
}
