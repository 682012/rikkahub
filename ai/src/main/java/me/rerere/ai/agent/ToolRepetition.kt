package me.rerere.ai.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 跨步骤的重复工具调用处理。只看工具名、规范化后的参数和文本输出，不按工具名猜语义。
 *
 * 两件事：
 * - [dedupeRepeatedToolOutputs]：发给模型的请求里，和更早一次调用（同名、同参数）结果完全相同的输出
 *   换成一行指向那次调用的说明，消息本身与界面仍保留完整结果。只依赖更早的内容，同一段历史每次得到
 *   同样的结果，不破坏提示词缓存的前缀。
 * - [blockedRepeatCalls]：同一次运行里最近的一串调用在原地打转时（同一个调用连续重复，或者几个调用
 *   按固定顺序来回振荡），下一次同样的调用不再执行，直接回错误让模型换路。
 *
 * 两者都只在纯文本输出上成立：带图片、文件的结果可能悄悄变化，一律原样保留。
 * 轮询类工具（等待子代理、查后台任务状态）结果相同是正常的，这类工具用
 * [me.rerere.ai.core.Tool.repeatable] 豁免两者。
 */

/** 连续执行几次相同的调用后拦下下一次；同时也是判定循环所需的最少完整周期数 */
internal const val MAX_IDENTICAL_REPEATS = 4

/** 能识别的循环最多包含几种调用：ABAB 这种交替振荡是 2，最多看到四个调用的轮转 */
private const val MAX_LOOP_PERIOD = 4

/** 至少要看到几个完整周期才认定无进展 */
private const val MIN_LOOP_CYCLES = MAX_IDENTICAL_REPEATS

/** 比这短的输出不去重：省下的 token 不值得多一行说明 */
internal const val DEDUPE_MIN_OUTPUT_CHARS = 400

/** 请求里第几次出现同一结果起提示换路 */
private const val REPEAT_HINT_FROM = 3

internal const val REPEAT_BLOCKED_MESSAGE =
    "Not executed: this call already ran $MAX_IDENTICAL_REPEATS times in a row with identical results, or is " +
        "repeating a cycle of calls whose results have not changed. Use the earlier result, change the " +
        "arguments or approach, or stop and report what is blocking you."

private val REPEAT_BLOCKED_TEXT = buildJsonObject { put("error", REPEAT_BLOCKED_MESSAGE) }.toString()

/** 被拦下的调用写进消息的输出，用来在后续判定里认出来（见 [isRepeatBlocked]） */
private val REPEAT_BLOCKED_OUTPUT: List<UIMessagePart> = listOf(UIMessagePart.Text(REPEAT_BLOCKED_TEXT))

/** 被拦下的调用的输出，格式与其他工具错误一致；每次给一份新列表，不共享可变的 metadata */
internal fun repeatBlockedOutput(): List<UIMessagePart> = listOf(UIMessagePart.Text(REPEAT_BLOCKED_TEXT))

private data class ToolCallKey(val name: String, val arguments: String)

private fun UIMessagePart.Tool.callKey(arguments: CanonicalArgumentsCache): ToolCallKey? =
    arguments.canonical(input)?.let { ToolCallKey(toolName, it) }

/**
 * 纯文本输出的总长度；带图片、文件等输出的调用返回 null，不参与比较。
 * 只累加各段长度、不拼接内容：短输出在这一步就能被排除掉。
 */
private fun UIMessagePart.Tool.textOutputLength(): Int? {
    if (output.isEmpty()) return null
    var length = 0
    for (part in output) {
        if (part !is UIMessagePart.Text) return null
        length += part.text.length
    }
    return length
}

/** 上一次被拦下的调用留下的是固定错误文本，不算新一轮进展，也不打断计数 */
private fun UIMessagePart.Tool.isRepeatBlocked(): Boolean = output == REPEAT_BLOCKED_OUTPUT

/** 同一个 key 下已经出现过的一次输出 */
private class Occurrence(val toolCallId: String, val output: List<UIMessagePart>, var count: Int)

internal fun dedupeRepeatedToolOutputs(
    messages: List<UIMessage>,
    arguments: CanonicalArgumentsCache = CanonicalArgumentsCache(),
    repeatable: (String) -> Boolean,
): List<UIMessage> {
    // 同一个调用（同名同参数）下按输出逐段比较：既不哈希大输出，也不拼接内容
    val seen = HashMap<ToolCallKey, MutableList<Occurrence>>()
    var changed = false
    val result = messages.map { message ->
        var messageChanged = false
        val parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || !part.isExecuted || repeatable(part.toolName)) return@map part
            // 先看长度：短输出不参与去重，这一步省掉规范化参数和比较的全部工作
            val length = part.textOutputLength() ?: return@map part
            if (length < DEDUPE_MIN_OUTPUT_CHARS) return@map part
            val key = part.callKey(arguments) ?: return@map part
            val occurrences = seen.getOrPut(key) { ArrayList(2) }
            val earlier = occurrences.firstOrNull { it.output == part.output }
            if (earlier == null) {
                occurrences += Occurrence(part.toolCallId, part.output, 1)
                return@map part
            }
            earlier.count++
            messageChanged = true
            val hint = if (earlier.count >= REPEAT_HINT_FROM) {
                " This call has now returned the same result ${earlier.count} times; use that result or change approach."
            } else {
                ""
            }
            part.copy(
                output = listOf(
                    UIMessagePart.Text(
                        "[Same result as earlier call ${earlier.toolCallId} with identical arguments " +
                            "($length chars), not repeated here.$hint]"
                    )
                )
            )
        }
        if (messageChanged) {
            changed = true
            message.copy(parts = parts)
        } else {
            message
        }
    }
    return if (changed) result else messages
}

/**
 * [executed] 是本轮已经执行过的工具调用（按顺序），返回 [pending] 中应拦下的 toolCallId。
 *
 * 只看最近的一小段历史，从短周期往长找：
 * - 周期 1 就是原来的「连续 [MAX_IDENTICAL_REPEATS] 次相同调用、结果都一样」；
 * - 周期 2..4 覆盖 ABAB 这类来回振荡（读 A、读 B、再读 A……）：每次调用的参数都在变，
 *   只数连续相同的调用抓不到这种原地打转。
 *
 * 判定逐位置比较：每个调用都要和它前一个周期里同一位置的调用同名同参数、输出逐段相同；
 * 不要求同一个周期里不同调用的输出相同——交替读两个不同文件本来就是正常的，不是无进展。
 * 周期没走完、输出有变化、出现非文本输出或轮询工具时一律不拦，宁可漏判也不误杀。
 *
 * 被拦下的调用不打断计数，模型坚持重发会一直被拦，直到它换一个调用。
 */
internal fun blockedRepeatCalls(
    executed: List<UIMessagePart.Tool>,
    pending: List<UIMessagePart.Tool>,
    arguments: CanonicalArgumentsCache = CanonicalArgumentsCache(),
    repeatable: (String) -> Boolean,
): Set<String> {
    if (pending.isEmpty()) return emptySet()
    val history = executed.filter { it.isExecuted && !it.isRepeatBlocked() }
    if (history.size < MIN_LOOP_CYCLES) return emptySet()
    // 只回看判定需要的最短尾窗：历史再长，这里的工作量也都是常数级
    val from = maxOf(0, history.size - MAX_LOOP_PERIOD * MIN_LOOP_CYCLES)
    val window = history.subList(from, history.size)
    for (period in 1..MAX_LOOP_PERIOD) {
        val loop = loopTail(window, period, arguments, repeatable) ?: continue
        return pending.mapNotNullTo(LinkedHashSet<String>()) { tool ->
            val key = tool.callKey(arguments)
            if (key != null && key in loop) tool.toolCallId else null
        }
    }
    return emptySet()
}

/**
 * [window] 末尾是否至少有 [MIN_LOOP_CYCLES] 个长度为 [period] 的相同周期；是的话返回周期里出现过的调用。
 * 逐位置和上一个周期比较，不比较同一周期内不同位置的输出。
 */
private fun loopTail(
    window: List<UIMessagePart.Tool>,
    period: Int,
    arguments: CanonicalArgumentsCache,
    repeatable: (String) -> Boolean,
): Set<ToolCallKey>? {
    val span = period * MIN_LOOP_CYCLES
    if (window.size < span) return null
    val tail = window.subList(window.size - span, window.size)
    for (index in period until span) {
        val call = tail[index]
        val previous = tail[index - period]
        // 名字先比：不同工具的参数不能互相比较，也是这里最便宜的一步
        if (call.toolName != previous.toolName) return null
        if (repeatable(call.toolName)) return null
        // 非文本输出可能悄悄变化，看不到就不判
        if (call.textOutputLength() == null || previous.textOutputLength() == null) return null
        val key = call.callKey(arguments) ?: return null
        if (key != previous.callKey(arguments)) return null
        if (call.output != previous.output) return null
    }
    val loop = LinkedHashSet<ToolCallKey>(period * 2)
    for (index in 0 until period) {
        val call = tail[index]
        if (repeatable(call.toolName)) return null
        loop += call.callKey(arguments) ?: return null
    }
    return loop
}
