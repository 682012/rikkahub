package me.rerere.rikkahub.data.ai.tools.local

import com.dokar.quickjs.binding.asyncFunction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.TOOL_OUTPUT_READ_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.ToolOutputStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Code Mode v1a：`run_script`。
 *
 * 模型写一段 JS，在一次工具调用里按需调用多个**只读**工具，只有 `console` 输出和 `return` 值回到上下文，
 * 中间结果留在脚本里。与 `tool_batch` 的分工是它做不到的那部分：依赖链、循环、条件、取字段。
 *
 * 边界（都不打算在 v1 里突破）：
 *  - 只注入 [SCRIPT_TOOL_ALLOWLIST] 里的只读工具。写操作 / shell / MCP / 子代理 / `tool_batch` / `run_script`
 *    自己在脚本里根本不可见，原因是审批：`Tool.needsApproval` 是执行前的一次性二值判断，
 *    脚本一旦跑起来就没有「停下来等人点」的挂起点（见 R4 §2、§4.3）。
 *  - 每次嵌套调用执行前**再判一次**该工具的 `needsApproval`：白名单只是第一道闸，工作区工具是参数级审批，
 *    工具名本身不能代表这次调用的语义。
 *  - 嵌套调用走工具**未包装**的 `execute`，自己只做「> 16K 交给 [ToolOutputStore] 外置」，
 *    避免 run_script 被外层 wrap 一次、嵌套再包一次，模型拿到「回执的回执」。
 */

/** 工具名。Code Mode 一次工具调用 = 一段脚本。 */
internal const val SCRIPT_TOOL_NAME = "run_script"

/** 脚本里可以直接 `await tools.<name>(...)` 的工具名。只读、无副作用、不需要审批。 */
internal val SCRIPT_TOOL_ALLOWLIST = setOf(
    // 工作区（workspace_grep / workspace_glob 由另一个人添加，名字先认下来，不存在就不会注入）
    "workspace_read_file",
    "workspace_grep",
    "workspace_glob",
    // 记忆
    "memory_read",
    "memory_list",
    // 搜索 / 抓取
    "search_web",
    "scrape_web",
    // 会话历史
    "recent_chats",
    "conversation_search",
    "conversation_read",
    // 技能与时间
    "use_skill",
    "get_time_info",
    "get_current_time",
)

/** 单脚本嵌套调用次数上限。比 `tool_batch` 的 16 宽一倍，覆盖「读 20 个文件」这类场景，超了就报错不静默截断。 */
private const val SCRIPT_MAX_CALLS = 32

/** 脚本总墙钟 / 单次嵌套调用上限。 */
private const val SCRIPT_TOTAL_TIMEOUT_MS = 120_000L
private const val SCRIPT_PER_CALL_TIMEOUT_MS = 120_000L

/** console 日志与 return 值各自的上限：8K 已相当于一次普通工具输出，比 16K 外置阈值留一倍余量。 */
private const val SCRIPT_MAX_LOG_CHARS = 8 * 1024
private const val SCRIPT_MAX_RESULT_CHARS = 8 * 1024

/** 嵌套调用并发闸门，对齐 `ToolBatch` 的 8。 */
private const val SCRIPT_MAX_CONCURRENT_CALLS = 8

/** 单次嵌套调用的错误消息上限；成功调用的审计只记元数据，不回传中间正文。 */
private const val SCRIPT_MAX_ERROR_CHARS = 240

/** 与 [ToolOutputStore] 的预览块首行保持一致，用来判断这次输出是否已被外置。 */
private const val OFFLOAD_NOTICE_PREFIX = "[Tool output offloaded: "

/** `run_script` 的硬上限。测试会传入更小的值，生产一律用默认值。 */
internal data class ScriptToolLimits(
    val totalTimeoutMillis: Long = SCRIPT_TOTAL_TIMEOUT_MS,
    val perCallTimeoutMillis: Long = SCRIPT_PER_CALL_TIMEOUT_MS,
    val maxCalls: Int = SCRIPT_MAX_CALLS,
    val maxLogChars: Int = SCRIPT_MAX_LOG_CHARS,
    val maxResultChars: Int = SCRIPT_MAX_RESULT_CHARS,
    val maxConcurrentCalls: Int = SCRIPT_MAX_CONCURRENT_CALLS,
    val maxErrorChars: Int = SCRIPT_MAX_ERROR_CHARS,
) {
    val javascript: JavaScriptLimits
        get() = JavaScriptLimits(
            timeoutMillis = totalTimeoutMillis,
            maxLogChars = maxLogChars,
            maxResultChars = maxResultChars,
        )
}

/**
 * 组装 `run_script`。
 *
 * @param catalog 已组装好的、**未包装**的工具列表（`ChatToolFactory` 在最后一步传入）。
 *   只读白名单之外的工具不会被注入，脚本里连名字都看不到。
 * @param outputStore 复用现成的输出外置：单次嵌套输出超过 16K 时脚本拿到「回执 + 头尾预览」，
 *   模型事后可以用 `tool_output_read` 分页读回。
 * @return 一个可注入的工具都没有时返回 null：那种配置下给模型一个空壳脚本工具只会骗它写一段废代码。
 */
internal fun buildScriptTool(
    catalog: List<Tool>,
    outputStore: ToolOutputStore,
    limits: ScriptToolLimits = ScriptToolLimits(),
): Tool? {
    val tools = catalog
        .filter { it.name in SCRIPT_TOOL_ALLOWLIST || isToolOutputReaderName(it.name) }
        .distinctBy { it.name }
    if (tools.isEmpty()) return null
    val byName = tools.associateBy { it.name }
    // 外置闸门：小输出原样透传（同一个 List 实例），超过阈值才换成回执，不改 ToolOutputStore 内部
    val offloading = tools.associate { it.name to outputStore.wrap(it) }
    val toolNames = byName.keys.sorted()

    return Tool(
        name = SCRIPT_TOOL_NAME,
        description = scriptToolDescription(toolNames),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("code", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "JavaScript source. It runs inside an async function: use `await` and `return`."
                        )
                    })
                },
                required = listOf("code"),
            )
        },
        // 只碰只读工具，执行前不需要审批
        needsApproval = { false },
        // 脚本内部自己控制并发（Promise.all + 信号量）；整批调度里它是一个屏障，不该被当成独立只读并行
        executionMode = ToolExecutionMode.SERIAL,
        execute = { input ->
            val code = requireNotNull(input.jsonObject["code"]?.jsonPrimitive?.contentOrNull) {
                "Script code is required"
            }
            listOf(
                UIMessagePart.Text(
                    runScript(
                        code = code,
                        byName = byName,
                        offloading = offloading,
                        limits = limits,
                    )
                )
            )
        },
    )
}

/** 描述要短：1-2 次简单调用直接调工具，3 次以上/有依赖/要循环筛选时才用它。 */
private fun scriptToolDescription(toolNames: List<String>): String = buildString {
    append("Run one JavaScript (ES2020) script that calls several read-only tools, and get back only its ")
    append("console output and return value: intermediate results stay inside the script.\n")
    append("Use it for 3+ tool calls, a result that feeds the next call, loops, or filtering/extracting fields ")
    append("from large outputs. For 1-2 simple calls, call the tools directly.\n")
    append("Inside the script: `const x = await tools.<name>(args)` returns the tool text output (already ")
    append("parsed into an object when the tool returned JSON); a failed call throws an Error carrying ")
    append("`e.tool` / `e.code` / `e.message`, so wrap it in try/catch; `console.log(...)` and `return <value>` ")
    append("are what you get back; `Promise.all([...])` runs independent calls in parallel.\n")
    append("Limits: ${SCRIPT_MAX_CALLS} calls, ${SCRIPT_TOTAL_TIMEOUT_MS / 1000}s, logs and return value ")
    append("truncated to ${SCRIPT_MAX_LOG_CHARS / 1024}K chars each. Available: ")
    append(toolNames.joinToString(", ") { "tools.$it" }.ifEmpty { "(none)" })
    append(".\n")
    append("Write, edit, shell, MCP, subagent and batch tools cannot be called here: call them directly.")
}.replace("\n", " ")

private suspend fun runScript(
    code: String,
    byName: Map<String, Tool>,
    offloading: Map<String, Tool>,
    limits: ScriptToolLimits,
): String {
    val state = ScriptState(limits)
    val outcome = evaluateInSandbox(
        body = scriptBody(code, byName.keys.sorted(), limits),
        limits = limits.javascript,
    ) {
        // 唯一的宿主通道：JS 侧的 tools.<name>(args) 全部落到这里，一次调用一个 Promise
        asyncFunction("__rikkahubCall") { args ->
            val name = args.getOrNull(0) as? String
            if (name == null) {
                errorEnvelope("bad_call", "tool name is required")
            } else {
                callTool(
                    name = name,
                    argsJson = args.getOrNull(1) as? String ?: "{}",
                    byName = byName,
                    offloading = offloading,
                    state = state,
                    limits = limits,
                )
            }
        }
    }
    val calls = state.records()

    val envelope = (outcome as? JavaScriptOutcome.Success)?.let { parseEnvelope(it.result) }
    val failure = when (outcome) {
        is JavaScriptOutcome.Failure -> outcome.message
        is JavaScriptOutcome.Success -> when {
            envelope == null -> "run_script produced an unreadable result"
            !envelope.ok -> envelope.errorText()
            else -> null
        }
    }
    val logs = when (outcome) {
        is JavaScriptOutcome.Failure -> outcome.logs
        is JavaScriptOutcome.Success -> outcome.logs
    }

    return buildJsonObject {
        if (failure != null) {
            put("error", failure)
            val failed = envelope?.takeUnless { it.ok }
            val failedCode = failed?.code
            val failedTool = failed?.tool
            if (failedCode != null) put("code", failedCode)
            if (failedTool != null) put("tool", failedTool)
        } else {
            envelope?.result?.let { put("result", it) }
        }
        if (logs != null) put("logs", logs)
        put("calls", JsonArray(calls))
    }.toString()
}

/**
 * 一次嵌套调用。**所有**失败都折成信封返回给 JS，由 JS 侧抛 `Error`——
 * 这样 `try/catch` 在脚本里可用，错误也不会打断整段脚本。
 */
private suspend fun callTool(
    name: String,
    argsJson: String,
    byName: Map<String, Tool>,
    offloading: Map<String, Tool>,
    state: ScriptState,
    limits: ScriptToolLimits,
): String {
    val tool = byName[name] ?: return errorEnvelope(
        code = "unknown_tool",
        message = "Unknown tool `$name`. Available: ${byName.keys.sorted().joinToString(", ") { "tools.$it" }}.",
        tool = name,
    )
    if (!state.reserveCall()) {
        return errorEnvelope(
            code = "call_limit",
            message = "Script already made ${limits.maxCalls} tool calls. Split the work into more run_script calls.",
            tool = name,
        )
    }

    val args: JsonObject = try {
        val trimmed = argsJson.ifBlank { "{}" }
        Json.parseToJsonElement(trimmed) as? JsonObject
            ?: return errorEnvelope("bad_arguments", "Arguments for `$name` must be a JSON object.", name)
    } catch (e: Exception) {
        return errorEnvelope("bad_arguments", "Arguments for `$name` are not valid JSON: ${e.message}", name)
    }

    // 白名单只是第一道闸：工作区工具按参数审批（写根之外、越权路径），工具名代表不了这次调用
    val needsApproval = try {
        tool.needsApproval(args)
    } catch (_: Exception) {
        true // 判断本身出错就当成需要审批
    }
    if (needsApproval) {
        return errorEnvelope(
            code = "needs_approval",
            message = "`$name` needs the user's approval and cannot run inside a script. Call `$name` directly instead.",
            tool = name,
        )
    }

    val target = offloading[name] ?: tool
    val parts = try {
        state.withSlot {
            withTimeout(limits.perCallTimeoutMillis) { target.execute(args) }
        }
    } catch (e: TimeoutCancellationException) {
        // 先确认不是外层整段脚本被取消，是的话照常往上抛
        currentCoroutineContext().ensureActive()
        return errorEnvelope(
            code = "timeout",
            message = "`$name` did not finish within ${limits.perCallTimeoutMillis / 1000}s.",
            name,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return errorEnvelope(
            code = "tool_error",
            message = "${e::class.simpleName ?: "Error"}: ${e.message ?: "tool failed"}".take(limits.maxErrorChars),
            name,
        )
    }

    val textParts = parts.filterIsInstance<UIMessagePart.Text>()
    val text = textParts.joinToString("\n") { it.text }
    val attachments = parts.size - textParts.size
    val offloaded = textParts.any { it.text.startsWith(OFFLOAD_NOTICE_PREFIX) }
    state.record(
        buildJsonObject {
            put("tool", name)
            put("chars", text.length)
            if (attachments > 0) put("attachments", attachments)
            if (offloaded) put("offloaded", true)
        }
    )
    // 空输出不能变成空串：模型会把「没输出」当成没执行
    return successEnvelope(text.ifEmpty { "(no output)" })
}

/** 脚本里能看到的外置读工具：名字可能被 ChatToolFactory 加了序号（`tool_output_read_2`）。 */
private fun isToolOutputReaderName(name: String): Boolean =
    name == TOOL_OUTPUT_READ_TOOL_NAME || name.startsWith("${TOOL_OUTPUT_READ_TOOL_NAME}_")

/**
 * 调用计数 + 并发闸门 + `calls[]` 记录。
 *
 * JS 只有一个线程，但 `Promise.all` 会让多个嵌套调用同时在 Kotlin 侧挂着，
 * 所以计数和记录都要能并发安全。
 */
private class ScriptState(private val limits: ScriptToolLimits) {
    private val issued = AtomicInteger()
    private val records = ConcurrentLinkedQueue<JsonObject>()
    private val gate = Semaphore(limits.maxConcurrentCalls)

    fun reserveCall(): Boolean = issued.incrementAndGet() <= limits.maxCalls

    suspend fun <T> withSlot(block: suspend () -> T): T = gate.withPermit { block() }

    fun record(call: JsonObject) {
        records.add(call)
    }

    fun records(): List<JsonObject> = records.toList()
}

// ---------------------------------------------------------------- 信封与求值体

private fun successEnvelope(text: String): String = buildJsonObject {
    put("ok", true)
    put("text", text)
}.toString()

private fun errorEnvelope(code: String, message: String, tool: String? = null): String = buildJsonObject {
    put("ok", false)
    put("code", code)
    put("message", message)
    if (tool != null) put("tool", tool)
}.toString()

private class ScriptEnvelope(
    val ok: Boolean,
    val result: String?,
    val error: String?,
    val tool: String?,
    val code: String?,
) {
    fun errorText(): String = buildString {
        append(error ?: "script failed")
        if (tool != null || code != null) {
            append(" (")
            if (tool != null) append("tool `$tool`")
            if (tool != null && code != null) append(", ")
            if (code != null) append("code `$code`")
            append(")")
        }
    }
}

private fun parseEnvelope(raw: String?): ScriptEnvelope? {
    if (raw == null) return null
    val element = try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return null

    fun field(key: String): String? =
        (element[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    return ScriptEnvelope(
        ok = field("ok") == "true",
        result = field("result"),
        error = field("error"),
        tool = field("tool"),
        code = field("code"),
    )
}

/**
 * 求值体：一个 async IIFE。
 *
 * 整段包在 async 函数里，是因为用户脚本要写 `await` 和 `return`。
 * IIFE 前面那个顶层 `await` 不能省：quickjs-kt 1.0.15 只展开顶层 await 本身，求值结果是 Promise 时
 * 会把 Promise 对象原样转成字符串（`Promise { <state>: "fulfilled" }`），Kotlin 就拿不到信封。
 * 用户代码用**间接** eval 执行（`(0, eval)`）：拿到全局作用域，读得到挂在 `globalThis` 上的 `tools`，
 * 又不会看到这段包装里的局部变量。
 */
private fun scriptBody(code: String, toolNames: List<String>, limits: ScriptToolLimits): String {
    val js = limits.javascript
    val names = JsonArray(toolNames.map { JsonPrimitive(it) })
    return """
        await (async () => {
        ${jsConsoleHijackScript(js.maxLogChars)}
            const call = globalThis.__rikkahubCall;
            delete globalThis.__rikkahubCall;
            const tools = {};
            for (const name of $names) {
                tools[name] = async (args) => {
                    let payload;
                    try {
                        payload = (args === undefined || args === null) ? '{}' : stringify(args);
                    } catch (_) {
                        payload = '{}';
                    }
                    const reply = await call(name, payload);
                    let envelope;
                    try {
                        envelope = JSON.parse(reply);
                    } catch (_) {
                        envelope = { ok: false, code: 'bad_reply', message: 'the script host returned an unreadable result' };
                    }
                    if (envelope && envelope.ok) {
                        const text = envelope.text;
                        if (typeof text !== 'string') return text;
                        try {
                            const parsed = JSON.parse(text);
                            if (parsed !== null && typeof parsed === 'object') return parsed;
                        } catch (_) {}
                        return text;
                    }
                    const error = new Error((envelope && envelope.message) || ('tool ' + name + ' failed'));
                    error.tool = (envelope && envelope.tool) || name;
                    error.code = (envelope && envelope.code) || 'tool_error';
                    throw error;
                };
            }
            globalThis.tools = tools;
            try {
                const __value = await (0, eval)(${JsonPrimitive("(async () => {\n$code\n})()")});
        ${jsSerializeValueScript(js.maxResultChars, truncate = true)}
                return stringify({ ok: true, result: __text });
            } catch (e) {
                const message = (e && e.message) ? toString(e.message) : toString(e);
                return stringify({
                    ok: false,
                    error: message,
                    tool: (e && e.tool) || null,
                    code: (e && e.code) || 'script_error',
                });
            }
        })()
    """.trimIndent()
}
