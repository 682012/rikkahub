package me.rerere.rikkahub.data.ai.tools.local

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal fun buildJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        Execute JavaScript code using QuickJS engine (ES2020).
        The result is the value of the last expression in the code.
        For calculations with decimals, use toFixed() to control precision.
        Console output (log/info/warn/error) is captured and returned in 'logs' field.
        No DOM or Node.js APIs available.
        Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
            },
            required = listOf("code")
        )
    },
    execute = {
        val code = requireNotNull(it.jsonObject["code"]?.jsonPrimitive?.contentOrNull) {
            "JavaScript code is required"
        }
        listOf(UIMessagePart.Text(evaluateJavascript(code)))
    }
)

internal suspend fun evaluateJavascript(
    code: String,
    timeoutMillis: Long = JS_EXECUTION_TIMEOUT_MS,
): String {
    val limits = JavaScriptLimits(timeoutMillis = timeoutMillis)
    return when (val outcome = evaluateInSandbox(jsEvaluateScript(code, limits), limits)) {
        is JavaScriptOutcome.Success -> buildJsonObject {
            val logs = outcome.logs
            if (logs != null) {
                put("logs", logs)
            }
            put("result", outcome.result?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()

        // 老行为：报错时不带 logs，只回一条 error
        is JavaScriptOutcome.Failure -> errorPayload(outcome.message)
    }
}

// ---------------------------------------------------------------- 公共核（eval_javascript / run_script 共用）

/** QuickJS 运行参数。脚本工具与 eval_javascript 的差别只在这几个数字和外面那层 JS 包装。 */
internal data class JavaScriptLimits(
    val timeoutMillis: Long = JS_EXECUTION_TIMEOUT_MS,
    val memoryLimitBytes: Long = JS_MEMORY_LIMIT_BYTES,
    val maxStackBytes: Long = JS_MAX_STACK_BYTES,
    val maxLogChars: Int = JS_MAX_LOG_CHARS,
    val maxResultChars: Int = JS_MAX_RESULT_CHARS,
)

/** 一次求值的结果：日志与被求值表达式返回的字符串（已经由 JS 侧 stringify 过）。 */
internal sealed interface JavaScriptOutcome {
    data class Success(val logs: String?, val result: String?) : JavaScriptOutcome

    /** @param logs 崩溃前已经捕获到的 console 输出，脚本工具会把它一起回给模型 */
    data class Failure(val message: String, val logs: String?) : JavaScriptOutcome
}

/**
 * console 收集器。
 *
 * 总量封顶在 [maxChars]：每条日志进来时按剩余额度截断，超出的部分丢掉并留一个截断标记，
 * 避免 `while (true) console.log(...)` 把内存和上下文一起撑爆。
 */
internal class JavaScriptLogSink(private val maxChars: Int) {
    private val builder = StringBuilder()
    private var truncated = false

    fun append(line: String) {
        val remaining = maxChars - builder.length
        if (remaining <= 0) {
            truncated = true
            return
        }
        val entry = if (builder.isEmpty()) line else "\n$line"
        builder.append(entry.take(remaining))
        if (entry.length > remaining) truncated = true
    }

    /** 没有日志时返回 null，调用方据此决定要不要输出 `logs` 字段。 */
    fun render(): String? = when {
        builder.isEmpty() -> null
        truncated -> builder.toString() + "\n[Logs truncated]"
        else -> builder.toString()
    }
}

/**
 * 建 runtime、注入日志宿主函数、在受超时保护的同一次求值里跑 [body]。
 *
 * 超时/中断/引擎异常统一折成 [JavaScriptOutcome.Failure]，调用方只负责把结果摆成自己的格式。
 * 注意 [body] 自己负责劫持 console 并做结果序列化——值必须在这次求值内部转成字符串，
 * 因为 getter / toJSON / 大对象一旦交回 Kotlin 就既可能执行 JS，也可能绕过输出上限。
 */
internal suspend fun evaluateInSandbox(
    body: String,
    limits: JavaScriptLimits = JavaScriptLimits(),
    bindings: suspend QuickJs.() -> Unit = {},
): JavaScriptOutcome = withContext(Dispatchers.Default) {
    val logs = JavaScriptLogSink(limits.maxLogChars)
    fun timedOut() = JavaScriptOutcome.Failure(
        message = "JavaScript execution timed out after ${limits.timeoutMillis}ms",
        logs = logs.render(),
    )

    val started = TimeSource.Monotonic.markNow()
    try {
        val outcome = withTimeoutOrNull(limits.timeoutMillis.milliseconds) {
            // This binding interrupts native evaluation on timeout/cancellation and closes
            // the runtime only after execution stops, including result serialization.
            quickJs(Dispatchers.Default) {
                memoryLimit = limits.memoryLimitBytes
                maxStackSize = limits.maxStackBytes
                evaluationTimeoutMillis = limits.timeoutMillis
                function("__rikkahubLog") { args ->
                    logs.append(args[0] as String)
                }
                bindings()
                val result = evaluate<String?>(body)
                JavaScriptOutcome.Success(logs.render(), result)
            }
        }
        // JS can catch the native interrupt and return an error envelope before the coroutine
        // timeout is dispatched. The host deadline still wins, regardless of what JS returned.
        if (outcome == null || started.elapsedNow() >= limits.timeoutMillis.milliseconds) timedOut()
        else outcome
    } catch (_: QuickJsInterruptedException) {
        timedOut()
    } catch (e: QuickJsException) {
        JavaScriptOutcome.Failure(
            message = (e.message ?: "JavaScript execution failed").take(limits.maxLogChars),
            logs = logs.render(),
        )
    }
}

/**
 * 劫持 console 的 JS 片段。
 *
 * 在求值开始时就取走 `__rikkahubLog` 并从全局删掉，用户代码拿不到这个宿主函数；
 * 每行日志在 JS 侧先按 [maxLogChars] 截断，Kotlin 侧再按总量截断。
 */
internal fun jsConsoleHijackScript(maxLogChars: Int): String = """
    const log = globalThis.__rikkahubLog;
    delete globalThis.__rikkahubLog;
    const stringify = JSON.stringify;
    const toString = String;
    const format = value => {
        if (value !== null && typeof value === 'object') {
            try { return stringify(value); } catch (_) {}
        }
        return toString(value);
    };
    globalThis.console = {};
    for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
        console[level] = (...args) => {
            let line = '[' + (level === 'debug' ? 'LOG' : level.toUpperCase()) + ']';
            for (const arg of args) {
                line += ' ' + format(arg);
                if (line.length > $maxLogChars) {
                    line = line.slice(0, $maxLogChars) + ' [truncated]';
                    break;
                }
            }
            log(line);
        };
    }
""".trimIndent()

/**
 * 结果序列化片段：读取 `__value`，把 `__text` 写进当前作用域。
 *
 * @param truncate true 时超限截断（脚本工具：模型拿到多少算多少），
 *   false 时抛错（eval_javascript 的老行为：宁可报错也不给半截数据）
 */
internal fun jsSerializeValueScript(maxResultChars: Int, truncate: Boolean): String = buildString {
    appendLine("const __type = typeof __value;")
    appendLine(
        "let __text = __value == null ? null " +
            ": (__type === 'object' || __type === 'function') ? stringify(__value) : toString(__value);"
    )
    appendLine("if (__text === undefined) __text = null;")
    if (truncate) {
        appendLine(
            "if (__text != null && __text.length > $maxResultChars) " +
                "__text = __text.slice(0, $maxResultChars) + ' [truncated]';"
        )
    } else {
        appendLine(
            "if (__text != null && __text.length > $maxResultChars) " +
                "throw new Error('JavaScript result exceeds output limit');"
        )
    }
}

/** eval_javascript 的求值体：同步 IIFE，返回值就是最后一个表达式的值。 */
internal fun jsEvaluateScript(code: String, limits: JavaScriptLimits): String = """
    (() => {
    ${jsConsoleHijackScript(limits.maxLogChars)}
        const __value = (0, eval)(${JsonPrimitive(code)});
    ${jsSerializeValueScript(limits.maxResultChars, truncate = false)}
        return __text;
    })()
""".trimIndent()

private fun errorPayload(message: String) = buildJsonObject {
    put("error", message)
}.toString()

private const val JS_EXECUTION_TIMEOUT_MS = 10_000L
private const val JS_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
private const val JS_MAX_STACK_BYTES = 256L * 1024
private const val JS_MAX_LOG_CHARS = 64 * 1024
private const val JS_MAX_RESULT_CHARS = 1024 * 1024
