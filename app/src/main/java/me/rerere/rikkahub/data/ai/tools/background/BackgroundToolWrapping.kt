package me.rerere.rikkahub.data.ai.tools.background

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.BackgroundToolSettings

/** 显式后台入口的工具名。 */
const val BACKGROUND_CONTROL_TOOL_NAME = "background_tool"

/**
 * 转后台这件事**只在这个工具的 systemPrompt 里说一次**。
 *
 * 以前是给每个被包装的工具都追加一段英文说明：几十个工具 = 几十段重复文本，system prompt 显著变长，
 * 每轮都得多付这份 token，正好抵消掉转后台想省的时间。固定指引是全局语义，说一次就够。
 */
internal const val BACKGROUND_CONTROL_HINT =
    "[background] A tool call that runs longer than the foreground wait keeps running in the background: " +
        "you receive a JSON receipt with a task_id and the same execution continues (never replayed). " +
        "Keep working, then use background_tool (action=result pages the output, list, wait, cancel)."

/**
 * 永远不自动转后台的工具：
 *
 * - `background_tool`：控制入口自己，转了就没法控制；
 * - `ask_user`：交互式，答案要等用户，"等下再给答案"没有意义；
 * - `tool_batch`：批量入口。它的**成员**已经是包装过的工具，包它自己只会套两层回执；
 * - `run_script`：脚本工具的嵌套 catalog 是未包装的原始工具，包外层会把整段脚本变成后台任务，
 *   脚本里的顺序语义就断了；
 * - `workspace_shell_output` / `workspace_shell_kill`：已经有自己一套后台作业控制在管。
 *
 * 另外 `subagent_*` 本身已经是异步 + 自带完成通知，再包一层只是多一层回执。
 */
private val AUTO_BACKGROUND_EXCLUDED_NAMES = setOf(
    BACKGROUND_CONTROL_TOOL_NAME,
    "ask_user",
    "tool_batch",
    "run_script",
    "workspace_shell_output",
    "workspace_shell_kill",
)

/**
 * 自动转后台的资格：**按耗时**判断，不按读写判断。
 *
 * 以前只放 `SERIAL`（写/未知副作用），结果所有慢的只读工具（大仓库 grep、慢 MCP 查询）永远卡在
 * 前台等待里——这才是用户实际抱怨的那类。现在 `SERIAL` / `PARALLEL` / `PARALLEL_DEDUPLICATED`
 * 都按同一个时间阈值处理，被排除的是"转后台没意义或更糟"的：
 *
 * - 交互式（`ask_user`）、批量入口（`tool_batch`）、脚本（`run_script`）、控制入口自己；
 * - `subagent_*`：本身已经异步 + 自带完成通知；
 * - `repeatable` 轮询类（`workspace_shell_output` / `workspace_job_status` / `subagent_wait`…）：
 *   它们的语义就是"过会儿再看一眼"，转后台会把一次轮询变一串任务，也没必要每秒重复读；
 * - 显式后台作业控制（`workspace_shell_output` / `workspace_shell_kill`）与 `background_` 前缀。
 *
 * 只读工具转后台不改变任何写语义：`PARALLEL*` 本来就不排队，仍不排队（见 manager 的 `SERIAL` 闸门）。
 */
internal fun isAutoBackgroundEligible(tool: Tool): Boolean =
    !tool.repeatable &&
        tool.name !in AUTO_BACKGROUND_EXCLUDED_NAMES &&
        !tool.name.startsWith("subagent_") &&
        !tool.name.startsWith("background_")

/**
 * 把一次前台工具调用包成「先等一会儿、超时就转后台」。
 *
 * `copy(execute = ...)` 保留 name/description/parameters/systemPrompt/needsApproval/executionMode/repeatable：
 * 审批仍然在 AgentLoop 调 execute 之前发生，包装不会绕过任何审批；调度模式（`SERIAL` 排队 /
 * `PARALLEL` 重叠）也是原样保留的，转后台只是在 `execute` 外面加了一层时间阈值。工具自己的 timeout 也不变。
 *
 * systemPrompt **一个字都不动**（见 [BACKGROUND_CONTROL_HINT]）。
 *
 * 嵌套调用（`tool_batch` 的成员、工具内部再调工具）带着 [BackgroundBarrierHeld] 上下文，
 * 直接执行内层，不再叠一层任务和屏障。
 */
internal fun wrapToolWithBackground(
    tool: Tool,
    manager: BackgroundToolManager,
    owner: BackgroundToolOwner,
    settings: BackgroundToolSettings,
): Tool {
    val normalized = settings.normalized()
    if (!normalized.enabled || !isAutoBackgroundEligible(tool)) return tool
    val waitMillis = normalized.foregroundWaitMillis
    return tool.copy(
        execute = { input ->
            if (currentCoroutineContext()[BackgroundBarrierHeld.Key] != null) {
                tool.execute(input)
            } else {
                manager.execute(tool, input, owner, waitMillis)
            }
        },
    )
}

/** 显式后台入口不能拿来跑的工具：自身、控制家族、交互式工具、批量入口。 */
private val CONTROL_FORBIDDEN_NAMES = setOf(
    BACKGROUND_CONTROL_TOOL_NAME,
    "ask_user",
    "tool_batch",
)

internal fun isBackgroundControlTarget(name: String): Boolean =
    name !in CONTROL_FORBIDDEN_NAMES && !name.startsWith("background_")

/**
 * 显式后台入口。**一个**工具带 `action`，省 schema 也省一次选择：
 * `start` / `list` / `result` / `wait` / `cancel`。
 *
 * `start` 只能跑**本轮已经授权**的 catalog 里的工具（[catalog] 是本轮真实工具清单，不是名字空间），
 * 目标工具的 `needsApproval` 原样透传：需要审批的工具照样先问用户，后台入口不扩权。
 */
internal fun createBackgroundControlTools(
    manager: BackgroundToolManager,
    owner: BackgroundToolOwner,
    settings: BackgroundToolSettings,
    catalog: List<Tool>,
): List<Tool> {
    // Keep result/cancel available after the user disables new background calls.
    val targets: Map<String, Tool> = (if (settings.normalized().enabled) catalog else emptyList())
        .filter { isBackgroundControlTarget(it.name) }
        .associateBy { it.name }

    val tool = Tool(
        name = BACKGROUND_CONTROL_TOOL_NAME,
        description = "Control long-running tool calls that were moved to the background or started there. " +
            "action=start runs an advertised tool with `tool` and `arguments` in the background and returns a " +
            "task_id right away; action=list shows this conversation's background tasks; action=result reads " +
            "one task's output page by page (`task_id`, `offset`, `limit`); action=wait waits up to " +
            "`timeout_seconds` for a task; action=cancel stops it. You cannot start ask_user, tool_batch or " +
            "this tool, and the target tool's own approval still applies.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "action",
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("start"))
                                    add(JsonPrimitive("list"))
                                    add(JsonPrimitive("result"))
                                    add(JsonPrimitive("wait"))
                                    add(JsonPrimitive("cancel"))
                                },
                            )
                        },
                    )
                    put("tool", buildJsonObject { put("type", "string") })
                    put(
                        "arguments",
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", true)
                        },
                    )
                    put("task_id", buildJsonObject { put("type", "string") })
                    put("offset", buildJsonObject { put("type", "integer") })
                    put("limit", buildJsonObject { put("type", "integer") })
                    put("timeout_seconds", buildJsonObject { put("type", "integer") })
                },
                required = listOf("action"),
            )
        },
        // 整轮只在这里说一次"超时会转后台"，不往每个工具上贴一遍
        systemPrompt = { _, _ -> BACKGROUND_CONTROL_HINT },
        // 只有 start 可能触发目标工具的审批；判定失败按"要审批"处理，绝不静默放行
        needsApproval = { input ->
            val params = input as? JsonObject
            if (params == null || params.string("action") != "start") {
                false
            } else {
                val name = params.string("tool")
                val target = name?.let(targets::get)
                if (target == null) false
                else runCatching { target.needsApproval(params.objectOf("arguments")) }.getOrDefault(true)
            }
        },
        executionMode = ToolExecutionMode.SERIAL,
        repeatable = true,
        execute = { input ->
            val params = input as? JsonObject ?: error("background_tool expects an object")
            when (val action = params.string("action")) {
                "start" -> startDetachedTool(manager, owner, targets, params)
                "list" -> listTasks(manager, owner)
                "result" -> readTaskResult(manager, owner, params)
                "wait" -> waitTask(manager, owner, params)
                "cancel" -> cancelTask(manager, owner, params)
                else -> error("Unknown action '$action'; expected start, list, result, wait or cancel")
            }
        },
    )
    return listOf(tool)
}

private fun startDetachedTool(
    manager: BackgroundToolManager,
    owner: BackgroundToolOwner,
    targets: Map<String, Tool>,
    params: JsonObject,
): List<UIMessagePart> {
    val name = params.string("tool") ?: error("tool is required for action=start")
    val target = targets[name]
        ?: error("Tool '$name' is not available for background start in this run; nothing was started")
    val arguments = params.objectOf("arguments")
    val task = manager.startDetached(target, arguments, owner, explicit = true)
    val text = buildJsonObject {
        put("status", "started")
        put("task_id", task.id)
        put("tool", task.toolName)
        put(
            "note",
            "Running in the background. Continue with other work, then read it with action=result " +
                "(paged), action=wait, or stop it with action=cancel.",
        )
    }.toString()
    return listOf(UIMessagePart.Text(text))
}

private fun listTasks(
    manager: BackgroundToolManager,
    owner: BackgroundToolOwner,
): List<UIMessagePart> {
    val tasks = manager.list(owner)
    if (tasks.isEmpty()) return listOf(UIMessagePart.Text("no background tasks in this conversation"))
    val text = tasks.joinToString("\n") { task ->
        buildString {
            append(task.id).append('\t')
            append(task.toolName).append('\t')
            append(task.status.name.lowercase()).append('\t')
            append(task.resultChars).append(" chars\t")
            if (task.truncated) append("truncated:").append(task.storedChars).append('\t')
            if (!task.bodyRetained) append("body_not_retained\t")
            if (task.hasAttachments) append("attachments\t")
            append(task.arguments.take(80))
        }
    }
    return listOf(UIMessagePart.Text(text))
}

private fun readTaskResult(
    manager: BackgroundToolManager,
    owner: BackgroundToolOwner,
    params: JsonObject,
): List<UIMessagePart> {
    val id = params.string("task_id") ?: error("task_id is required for action=result")
    val page = manager.readResult(
        id = id,
        owner = owner,
        offset = params.int("offset") ?: 0,
        limit = params.int("limit") ?: BACKGROUND_RESULT_DEFAULT_LIMIT,
    ) ?: error("No background task $id in this conversation")
    val header = pageHeader(page)
    return buildList {
        add(UIMessagePart.Text(header))
        if (page.text.isNotEmpty()) add(UIMessagePart.Text(page.text))
        addAll(page.attachments)
    }
}

private suspend fun waitTask(
    manager: BackgroundToolManager,
    owner: BackgroundToolOwner,
    params: JsonObject,
): List<UIMessagePart> {
    val id = params.string("task_id") ?: error("task_id is required for action=wait")
    val seconds = (params.int("timeout_seconds") ?: BACKGROUND_WAIT_DEFAULT_SECONDS)
        .coerceIn(1, BACKGROUND_WAIT_MAX_SECONDS)
    val task = manager.waitFor(id, owner, seconds * 1000L)
        ?: error("No background task $id in this conversation")
    val pending = buildJsonObject {
        put("task_id", task.id)
        put("status", task.status.name.lowercase())
        put("note", "Still running; continue independent work. Completion will be announced.")
    }
    if (!task.isFinished) return listOf(UIMessagePart.Text(pending.toString()))
    val page = manager.readResult(id, owner, offset = 0)
    return buildList {
        if (page == null) {
            add(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("task_id", task.id)
                        put("tool", task.toolName)
                        put("status", task.status.name.lowercase())
                        put("total_chars", task.resultChars)
                        task.error?.let { error -> put("error", error) }
                    }.toString(),
                ),
            )
        } else {
            // 等到结束了就顺手把第一页正文带上：模型通常正要读它，省一轮
            add(UIMessagePart.Text(pageHeader(page)))
            if (page.text.isNotEmpty()) add(UIMessagePart.Text(page.text))
            addAll(page.attachments)
        }
    }
}

private fun cancelTask(
    manager: BackgroundToolManager,
    owner: BackgroundToolOwner,
    params: JsonObject,
): List<UIMessagePart> {
    val id = params.string("task_id") ?: error("task_id is required for action=cancel")
    val cancelled = manager.cancel(id, owner)
    val text = buildJsonObject {
        put("task_id", id)
        put("cancelled", cancelled)
        if (!cancelled) put("note", "Task not found in this conversation, or it already finished.")
    }.toString()
    return listOf(UIMessagePart.Text(text))
}

private fun pageHeader(page: BackgroundToolResultPage): String {
    val task = page.task
    val notes = buildList {
        if (task.truncated) {
            add(
                "Stored output is capped at ${task.storedChars} of ${task.resultChars} chars; the tail is NOT " +
                    "readable in this record. Inspect the target or saved logs; do NOT replay side-effecting calls.",
            )
        }
        if (!task.bodyRetained) {
            add("This call finished in the foreground: its output was delivered to you directly and is not stored.")
        }
        if (task.hasAttachments && page.attachments.isEmpty() && page.offset == 0) {
            add("Attachments (images/documents) are not kept across a restart; only the text body is readable.")
        }
        task.persistenceError?.let {
            add("The task record could not be written to disk ($it); it may be gone after a restart.")
        }
    }
    return buildJsonObject {
        put("task_id", task.id)
        put("tool", task.toolName)
        put("status", task.status.name.lowercase())
        put("total_chars", page.totalChars)
        put("offset", page.offset)
        put("end_offset", page.endOffset)
        put("eof", page.eof)
        if (task.truncated) {
            put("truncated", true)
            put("source_chars", task.resultChars)
            put("dropped_chars", (task.resultChars - task.storedChars).coerceAtLeast(0))
        }
        if (!task.bodyRetained) put("body_retained", false)
        if (page.attachments.isNotEmpty()) put("attached_parts", page.attachments.size)
        if (task.hasAttachments && page.attachments.isEmpty() && page.offset == 0) put("attachments_lost", true)
        task.persistenceError?.let { put("persistence_error", it) }
        task.error?.let { put("error", it) }
        if (notes.isNotEmpty()) put("notes", notes.joinToString(" "))
    }.toString()
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonObject.objectOf(name: String): JsonObject =
    (this[name] as? JsonObject) ?: JsonObject(emptyMap())
