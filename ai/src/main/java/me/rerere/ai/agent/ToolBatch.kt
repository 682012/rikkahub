package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart

private const val MAX_BATCH_CALLS = 16

/**
 * 不能出现在批内的工具名：
 * - `tool_batch`：批自身，批内批会形成递归并绕过上限/顺序语义；
 * - `ask_user`：交互式工具，答案要等用户，批内无法表达"等一半"；
 * - `run_script`：脚本执行工具，参数是自由文本脚本，批内一次性提交无法逐条审批与审计。
 *
 * 这里按字面名字排除（ai 模块不依赖 app 模块的工具常量）；新增同类工具时必须同步这个集合。
 */
private val BATCH_EXCLUDED_TOOL_NAMES = setOf("tool_batch", "ask_user", "run_script")

/**
 * 批内成员粒度审批的结论。
 *
 * 约定：整批只弹一次审批（任一成员需要审批就整批问），但结论可以按成员给出：
 * - [approved] = 整批结论；[members] 里出现的下标优先，未出现的跟随 [approved]；
 * - 只批准一条 -> `BatchMemberApproval.only(index)`；全部批准 -> [All]；整批拒绝 -> [None]；
 * - 执行阶段只跑被批准的成员，被拒绝的成员返回结构化 `tool_denied`，
 *   模型能明确看出"这条没执行、原因是没批准"，不会把它当成成功或空结果。
 */
data class BatchMemberApproval(
    val approved: Boolean = false,
    val members: Map<Int, Boolean> = emptyMap(),
) {
    fun isApproved(index: Int): Boolean = members[index] ?: approved

    companion object {
        val All = BatchMemberApproval(approved = true)
        val None = BatchMemberApproval(approved = false)

        /** 只批准一条，其余成员不执行。 */
        fun only(index: Int): BatchMemberApproval =
            BatchMemberApproval(approved = false, members = mapOf(index to true))

        fun of(vararg approvals: Pair<Int, Boolean>): BatchMemberApproval =
            BatchMemberApproval(approved = false, members = approvals.toMap())
    }
}

/**
 * tool_batch 在工具目录里的字面名（ai 模块不依赖 app 常量，见 [BATCH_EXCLUDED_TOOL_NAMES]）。
 */
internal const val TOOL_BATCH_NAME = "tool_batch"

/**
 * 执行期的成员审批结论载体。
 *
 * 为什么用协程上下文：`Tool.execute` 是 suspend 函数，AgentLoop 一次只跑一个工具，
 * 把结论挂在 coroutineContext 上既不用改 `Tool` 的签名（几十个工具），
 * 也不会写进消息/入参里污染历史记录。批外工具读不到这个元素，行为不变。
 */
data class BatchApprovalContext(
    val approval: BatchMemberApproval,
) : kotlin.coroutines.AbstractCoroutineContextElement(BatchApprovalContext) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<BatchApprovalContext>
}

/**
 * 解析 tool_batch 的 `calls` 参数；解析不出来返回 null（交给工具自己的错误路径，绝不放行）。
 */
internal fun parseBatchCalls(@Suppress("UNUSED_PARAMETER") toolName: String, args: JsonObject): List<Pair<String, JsonObject>>? {
    val items = args["calls"] as? JsonArray ?: return null
    return runCatching {
        items.map { item ->
            val obj = item as? JsonObject ?: error("calls[] must be an object")
            val name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("calls[].name must be a string")
            val arguments = when (val raw = obj["arguments"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> error("calls[].arguments must be an object")
            }
            name to arguments
        }
    }.getOrNull()
}

/**
 * 计算批内成员的审批计划。每个成员按**它自己那个工具**的 `needsApproval` 判定，
 * 与执行阶段的判定同源，所以"界面说这批要不要问"和"执行时到底拦谁"不会出现两套结论。
 *
 * 未知成员（不在当前目录）与判定抛异常一律当作需要审批：失败关闭，
 * 不能因为查不到工具就静默放行。批内批等被排除的工具同样按需审批处理。
 */
internal fun approvalPlan(
    calls: List<Pair<String, JsonObject>>,
    toolOf: (String) -> Tool?,
): BatchApprovalPlan = BatchApprovalPlan(
    calls.mapIndexed { index, (name, args) ->
        val member = toolOf(name)
        BatchApprovalPlan.Member(
            index = index,
            name = name,
            needsApproval = member == null || name in BATCH_EXCLUDED_TOOL_NAMES ||
                runCatching { member.needsApproval(args) }.getOrDefault(true),
        )
    }
)

/**
 * One model round trip for a preplanned batch. All arguments/permissions are checked before effects.
 *
 * 参数 [memberApproval] 是本次调用的成员审批结论来源；null 表示"调用方已经按 tool 级审批
 * 过滤过，全部允许"（保持旧行为）。
 */
fun createToolBatch(
    tools: List<Tool>,
    memberApproval: (() -> BatchMemberApproval)? = null,
): Tool {
    val catalog = tools.filter { it.name !in BATCH_EXCLUDED_TOOL_NAMES }.associateBy { it.name }
    fun parse(input: JsonElement): List<BatchCall> {
        val items = (input as? JsonObject)?.get("calls") as? JsonArray
            ?: error("calls must be an array of {name, arguments} objects")
        require(items.size in 1..MAX_BATCH_CALLS) { "calls must contain 1..$MAX_BATCH_CALLS items" }
        return items.mapIndexed { index, item ->
            val objectInput = item as? JsonObject ?: error("calls[$index] must be an object")
            val name = (objectInput["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("calls[$index].name must be a string")
            val tool = catalog[name] ?: error("Tool '$name' is not available for batching; nothing was executed")
            val arguments = when (val args = objectInput["arguments"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> args
                else -> error("calls[$index].arguments must be an object; nothing was executed")
            }
            BatchCall(index, tool, arguments)
        }
    }
    return Tool(
        name = "tool_batch",
        description = "Execute 1..16 already planned tool calls in one round trip. Each item has name and arguments. " +
            "Use only tools currently advertised and arguments already known; dependent results require a later call. " +
            "Independent reads overlap (max 8); writes/unknown tools remain ordered barriers. " +
            "Approval applies per member: members that need approval block the batch once, and after approval " +
            "only the approved members run — a rejected member returns {\"error\":\"tool_denied\"} and does NOT execute. " +
            "Results preserve call order; member failures come back as an error field " +
            "and the remaining calls still run. Do not batch ask_user run_script or tool_batch itself.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("calls", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", MAX_BATCH_CALLS)
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                            put("arguments", buildJsonObject {
                                put("type", "object")
                                put("additionalProperties", true)
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("name")) })
                    })
                })
            }, required = listOf("calls"))
        },
        // 两类失败要分开：解析失败（输入非法）不会执行任何成员，执行阶段 parse 会再抛一次，
        // 变成错误输出且没有副作用；而成员审批判定失败意味着"不知道要不要审批"，
        // 必须失败关闭（要求审批），否则一个抛异常的 needsApproval 会让整批静默放行。
        needsApproval = { input ->
            when (val calls = runCatching { parse(input) }.getOrNull()) {
                null -> false
                else -> calls.any { call ->
                    runCatching { call.tool.needsApproval(call.arguments) }.getOrDefault(true)
                }
            }
        },
        execute = { input ->
            // 优先用 AgentLoop 通过协程上下文传来的、用户对**这一条**调用的结论；
            // 没有就退回构造时给的回调；都没有才当作"整批已放行"（旧行为）。
            val approval = kotlin.coroutines.coroutineContext[BatchApprovalContext]?.approval
                ?: memberApproval?.invoke()
                ?: BatchMemberApproval.All
            executeBatch(parse(input), approval)
        },
    )
}

/** 批内每个成员是否需要审批。 */
data class BatchApprovalPlan(val members: List<Member>) {
    data class Member(val index: Int, val name: String, val needsApproval: Boolean)

    val anyNeedsApproval: Boolean get() = members.any { it.needsApproval }
    val requiringApproval: List<Member> get() = members.filter { it.needsApproval }
}

internal data class BatchCall(val index: Int, val tool: Tool, val arguments: JsonObject)

/**
 * 执行批准过的成员，未获批准的成员不执行、只产出结构化拒绝。
 *
 * 未获批准的成员一律先填好输出再去跑别的：这样即使批准的成员抛异常/被取消，
 * 被拒绝的那几条也已经有明确结论，不会被误读成"跑了但没输出"。
 */
private suspend fun executeBatch(
    calls: List<BatchCall>,
    approval: BatchMemberApproval,
): List<UIMessagePart> {
    val outputs = arrayOfNulls<List<UIMessagePart>>(calls.size)
    val allowed = calls.associate { call -> call.index to
        (!runCatching { call.tool.needsApproval(call.arguments) }.getOrDefault(true) || approval.isApproved(call.index)) }
    calls.forEach { call ->
        if (allowed[call.index] != true) {
            outputs[call.index] = listOf(
                UIMessagePart.Text(deniedMemberJson(call, reason = "not approved by user"))
            )
        }
    }
    val runnable = calls.filter { allowed[it.index] == true }
    var from = 0
    while (from < runnable.size) {
        if (runnable[from].tool.executionMode == ToolExecutionMode.SERIAL) {
            outputs[runnable[from].index] = executeBatchCall(runnable[from])
            from++
            continue
        }
        var until = from + 1
        while (until < runnable.size && runnable[until].tool.executionMode.allowsParallelExecution()) until++
        val group = runnable.subList(from, until)
        // 同名的成员才可能撞成同一组；名字在这一段里只出现一次时不必规范化它的参数（见 groupParallelCalls）
        val grouped = groupParallelCalls(
            calls = group,
            nameOf = { it.tool.name },
            deduplicated = { it.tool.executionMode == ToolExecutionMode.PARALLEL_DEDUPLICATED },
            canonicalKey = { canonicalToolJson(it.arguments).toString() },
        )
        coroutineScope {
            val permits = Semaphore(8)
            grouped.map { members -> async {
                permits.withPermit {
                    val result = executeBatchCall(members.first())
                    members.forEach { outputs[it.index] = result }
                }
            } }.awaitAll()
        }
        from = until
    }
    val text = buildJsonObject {
        put("results", buildJsonArray {
            calls.forEach { call -> add(buildJsonObject {
                put("index", call.index)
                put("name", call.tool.name)
                if (allowed[call.index] != true) put("error", "tool_denied")
                put("output", buildJsonArray {
                    outputs[call.index].orEmpty().filterIsInstance<UIMessagePart.Text>()
                        .forEach { add(JsonPrimitive(it.text)) }
                })
                val nonText = outputs[call.index].orEmpty().count { it !is UIMessagePart.Text }
                if (nonText > 0) put("attachedParts", nonText)
            }) }
        })
    }.toString()
    return listOf(UIMessagePart.Text(text)) + outputs.flatMap { it.orEmpty().filterNot { part -> part is UIMessagePart.Text } }
}

/**
 * 未获批准成员的结构化输出（与 app 侧 ToolApprovalPolicy.deniedMemberOutput 同形，
 * ai 模块不依赖 app，所以这里自己拼一份）。
 *
 * 关键点：`error` 字段让模型一眼看出这条**没执行**，`output` 里也放同一段 JSON，
 * 这样既能被 `error` 判定的调用方识别，也能被只读 output 的调用方看到原因。
 */
internal fun deniedMemberJson(call: BatchCall, reason: String): String = buildJsonObject {
    put("error", "tool_denied")
    put("index", call.index)
    put("name", call.tool.name)
    put("code", "not_approved")
    put("message", "This call was NOT executed because it was not approved by the user.")
    put("reason", reason.ifBlank { "No reason provided" })
}.toString()

private suspend fun executeBatchCall(call: BatchCall): List<UIMessagePart> = try {
    call.tool.execute(call.arguments).ifEmpty { listOf(UIMessagePart.Text("(no output)")) }
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    // 与 AgentLoop.runTool 一致：Error（模型生成的超大 JSON 导致的 StackOverflowError、超大输出的
    // OutOfMemoryError）也转成错误输出让模型自我修正，不要让一个成员掀掉整轮。取消仍然向上传播。
    listOf(UIMessagePart.Text(buildJsonObject { put("error", "[${e.javaClass.simpleName}] ${e.message}") }.toString()))
}
