package me.rerere.rikkahub.data.mcpserver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * App 内 MCP 工具的**声明式清单**（纯 JVM，无 Android 依赖，可单测）。
 *
 * 红线（都在代码里，不是注释承诺）：
 * - 清单里**没有** `assistant_id` 这类参数：绑定助手只来自本机设置，调用方无法用参数越权；
 * - 每个工具都带 [AppMcpToolSpec.gates]，[AppMcpGates] 决定它是否出现在 tools/list、以及 call 时是否放行；
 * - 写工具一律 `requiresIdempotencyKey = true`，schema 里真的声明了必填 `idempotency_key`；
 * - 声明了就一定有实现：[AppMcpToolHost] 的分发覆盖 [catalog] 里的每个名字，单测交叉核对两边。
 *
 * 设备工具（`device_*`）的 schema 由设备桥自己给出（`DeviceToolCatalog`），这里只负责在
 * `allowDevice` 打开时把它们并进清单，并补上 `idempotency_key`（见 [withIdempotencyKey]）。
 */
enum class AppMcpGate {
    /** 会话/归档/任务读取。 */
    READ,

    /** 会改 App 状态（发消息、改名、归档、取消任务……）。 */
    MUTATIONS,

    /** 可能发起计费模型请求（发消息、开压缩）。 */
    MODEL_REQUESTS,

    /** 设备无障碍代理。设备桥自己还有一层授权。 */
    DEVICE,

    /** 本地自动化配方管理与运行。 */
    AUTOMATION,

    /** 需要本机设置里绑定一个助手；未绑定则不暴露，也不做"全助手"视图。 */
    BOUND_ASSISTANT,
}

/**
 * 一次调用可见的开关快照。只从本机设置派生，**没有** per-call 提权路径。
 *
 * [boundAssistantId] 为 null 表示没有绑定助手：此时聊天/归档/任务工具都不出现、也不可调用
 * （只有 `app_status` 和显式打开的设备/自动化工具还在）。
 */
data class AppMcpGates(
    val boundAssistantId: String? = null,
    val read: Boolean = true,
    val mutations: Boolean = false,
    val modelRequests: Boolean = false,
    val device: Boolean = false,
    val automation: Boolean = false,
) {
    fun allows(spec: AppMcpToolSpec): Boolean = spec.gates.all { grants(it) }

    fun grants(gate: AppMcpGate): Boolean = when (gate) {
        AppMcpGate.READ -> read
        AppMcpGate.MUTATIONS -> read && mutations
        AppMcpGate.MODEL_REQUESTS -> read && mutations && modelRequests
        AppMcpGate.DEVICE -> device
        AppMcpGate.AUTOMATION -> automation
        AppMcpGate.BOUND_ASSISTANT -> boundAssistantId != null
    }
}

/** 开关来源。服务器/自动化各自注入自己的读取方式（设置流 or 常量）。 */
fun interface AppMcpGatesSource {
    fun current(): AppMcpGates
}

/** 给 LocalMcpToolHost 适配层用的最小视图（不依赖并行开发的 LocalMcp* 类型）。 */
data class AppMcpExposedTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
)

data class AppMcpToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    /** 只读：不改 App 状态，可以并行、可以随便重试。 */
    val readOnly: Boolean,
    val gates: Set<AppMcpGate>,
    /**
     * 写工具必须带 `idempotency_key`：传输层失败不能重放，
     * 同一个 key + 同样参数只执行一次（见 AppMcpIdempotencyLedger）。
     */
    val requiresIdempotencyKey: Boolean = !readOnly,
)

/** 分页/长度上限。host 和测试共用同一份数字。 */
object AppMcpLimits {
    const val DEFAULT_PAGE = 20
    const val MAX_PAGE = 50
    const val DEFAULT_CHARS = 8000
    const val MAX_CHARS = 30000
    const val MAX_MESSAGE_CHARS = 2000
    const val MAX_SEND_CHARS = 8000
    const val MAX_TITLE_CHARS = 200
    const val MAX_PROMPT_CHARS = 4000
    const val MAX_RECIPE_STEPS = 40
    const val MAX_HISTORY = 50

    fun clampPage(value: Int?): Int = (value ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)

    fun clampChars(value: Int?): Int = (value ?: DEFAULT_CHARS).coerceIn(200, MAX_CHARS)

    fun clampOffset(value: Int?): Int = (value ?: 0).coerceIn(0, Int.MAX_VALUE - MAX_PAGE)
}

object AppMcpToolCatalog {

    const val IDEMPOTENCY_KEY_FIELD = "idempotency_key"

    private fun schema(builder: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { builder() }
        put("additionalProperties", false)
    }

    private fun JsonObjectBuilder.str(name: String, description: String) {
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
        }
    }

    private fun JsonObjectBuilder.integer(name: String, description: String) {
        putJsonObject(name) {
            put("type", "integer")
            put("description", description)
        }
    }

    private fun JsonObjectBuilder.bool(name: String, description: String) {
        putJsonObject(name) {
            put("type", "boolean")
            put("description", description)
        }
    }

    /** 写工具的 schema：`idempotency_key` 进 properties 且进 required（外部写一份给模型看）。 */
    private fun writeSchema(builder: JsonObjectBuilder.() -> Unit): JsonObject = schema {
        builder()
        str(
            IDEMPOTENCY_KEY_FIELD,
            "Required for state-changing tools. A transport retry MUST reuse the same key: same key + same " +
                "arguments returns the recorded result and never re-executes; a different argument set under " +
                "the same key is rejected.",
        )
    }

    private fun JsonObjectBuilder.stepsArray() {
        putJsonObject("steps") {
            put("type", "array")
            put(
                "description",
                "Ordered steps, max ${AppMcpLimits.MAX_RECIPE_STEPS}. Each step is " +
                    "{\"tool\":\"<name>\",\"arguments\":{...}}; automation_* tools are not allowed inside recipes.",
            )
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("tool") { put("type", "string") }
                    putJsonObject("arguments") { put("type", "object") }
                    putJsonObject("note") { put("type", "string") }
                }
                putJsonArray("required") { add("tool") }
            }
        }
    }

    private fun withRequired(base: JsonObject, vararg names: String): JsonObject {
        val existing = (base["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        val merged = (existing + names).distinct()
        return buildJsonObject {
            base.forEach { (k, v) -> if (k != "required") put(k, v) }
            putJsonArray("required") { merged.forEach { add(it) } }
        }
    }

    private fun JsonObjectBuilder.conversationId() {
        str("conversation_id", "Conversation id inside the bound assistant.")
    }

    private val CHAT_READ_GATES = setOf(AppMcpGate.READ, AppMcpGate.BOUND_ASSISTANT)

    private val CHAT_WRITE_GATES = setOf(
        AppMcpGate.READ,
        AppMcpGate.MUTATIONS,
        AppMcpGate.BOUND_ASSISTANT,
    )

    private val MODEL_WRITE_GATES = setOf(
        AppMcpGate.READ,
        AppMcpGate.MUTATIONS,
        AppMcpGate.MODEL_REQUESTS,
        AppMcpGate.BOUND_ASSISTANT,
    )

    private val AUTOFILE_WRITE_GATES = setOf(
        AppMcpGate.READ,
        AppMcpGate.MUTATIONS,
        AppMcpGate.BOUND_ASSISTANT,
    )

    private val AUTOMATION_READ_GATES = setOf(AppMcpGate.AUTOMATION)

    private val AUTOMATION_WRITE_GATES = setOf(AppMcpGate.AUTOMATION, AppMcpGate.MUTATIONS)

    /** 完整清单（未过滤）。顺序稳定，便于日志比对与测试。 */
    fun catalog(): List<AppMcpToolSpec> = buildList {
        // ---------- 状态 ----------
        add(
            AppMcpToolSpec(
                name = "app_status",
                description = "Local RikkaHub status: which capabilities this MCP endpoint may use right now, " +
                    "whether an assistant is bound, and whether device/automation are granted. " +
                    "Never returns tokens, provider keys or assistant system prompts.",
                inputSchema = schema { },
                readOnly = true,
                gates = emptySet(),
            )
        )

        // ---------- 会话读取 ----------
        add(
            AppMcpToolSpec(
                name = "chat_list",
                description = "List conversations of the assistant this endpoint is bound to, newest first, paged. " +
                    "There is no way to list another assistant's conversations.",
                inputSchema = schema {
                    integer("offset", "Zero-based conversation offset, default 0.")
                    integer("limit", "Page size, 1..${AppMcpLimits.MAX_PAGE}, default ${AppMcpLimits.DEFAULT_PAGE}.")
                    bool("unfiled_only", "Only conversations not in any folder.")
                },
                readOnly = true,
                gates = CHAT_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "chat_search",
                description = "Search conversation titles of the bound assistant, paged.",
                inputSchema = withRequired(
                    schema {
                        str("query", "Title keyword, 1..80 chars.")
                        integer("offset", "Zero-based conversation offset, default 0.")
                        integer("limit", "Page size, 1..${AppMcpLimits.MAX_PAGE}, default ${AppMcpLimits.DEFAULT_PAGE}.")
                    },
                    "query",
                ),
                readOnly = true,
                gates = CHAT_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "chat_read",
                description = "Read the tail of one conversation of the bound assistant with bounded message count " +
                    "and bounded characters. Messages come back as plain text; tool calls appear as text and are " +
                    "NEVER executed by this endpoint. System messages are omitted and nothing secret is exported.",
                inputSchema = withRequired(
                    schema {
                        conversationId()
                        integer(
                            "limit",
                            "Messages from the tail, 1..${AppMcpLimits.MAX_PAGE}, default ${AppMcpLimits.DEFAULT_PAGE}.",
                        )
                        integer(
                            "max_chars",
                            "Total character budget, 200..${AppMcpLimits.MAX_CHARS}, default ${AppMcpLimits.DEFAULT_CHARS}.",
                        )
                    },
                    "conversation_id",
                ),
                readOnly = true,
                gates = CHAT_READ_GATES,
            )
        )

        // ---------- 会话写入 ----------
        add(
            AppMcpToolSpec(
                name = "chat_send",
                description = "Send a text message into a conversation of the bound assistant. Returns only after the " +
                    "message queue has durably accepted it (receipt), not after the reply. Poll chat_read for the " +
                    "answer. This can start a model request that spends the user's quota, so it needs the " +
                    "model-request switch on top of the write switch.",
                inputSchema = withRequired(
                    writeSchema {
                        conversationId()
                        str("text", "Message text, 1..${AppMcpLimits.MAX_SEND_CHARS} chars.")
                        bool("answer", "Whether the assistant should answer, default true.")
                    },
                    "conversation_id",
                    "text",
                    IDEMPOTENCY_KEY_FIELD,
                ),
                readOnly = false,
                gates = MODEL_WRITE_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "chat_rename",
                description = "Rename a conversation of the bound assistant.",
                inputSchema = withRequired(
                    writeSchema {
                        conversationId()
                        str("title", "New title, 1..${AppMcpLimits.MAX_TITLE_CHARS} chars.")
                    },
                    "conversation_id",
                    "title",
                    IDEMPOTENCY_KEY_FIELD,
                ),
                readOnly = false,
                gates = CHAT_WRITE_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "chat_move_folder",
                description = "Move a conversation of the bound assistant into one of that assistant's folders, or " +
                    "out of any folder with unfile=true. Folders are never created implicitly.",
                inputSchema = withRequired(
                    writeSchema {
                        conversationId()
                        str("folder_id", "Target folder id owned by the same assistant.")
                        bool("unfile", "Set true to remove the conversation from its folder.")
                    },
                    "conversation_id",
                    IDEMPOTENCY_KEY_FIELD,
                ),
                readOnly = false,
                gates = CHAT_WRITE_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "chat_stop",
                description = "Stop the running generation of one conversation of the bound assistant. Deletes " +
                    "nothing and does not silently resume the queue.",
                inputSchema = withRequired(
                    writeSchema { conversationId() },
                    "conversation_id",
                    IDEMPOTENCY_KEY_FIELD,
                ),
                readOnly = false,
                gates = CHAT_WRITE_GATES,
            )
        )

        // ---------- 上下文压缩 ----------
        add(
            AppMcpToolSpec(
                name = "compression_status",
                description = "Real compression status of a conversation of the bound assistant: phase, generated " +
                    "characters, chunk count, outcome. Values are measured, never fabricated percentages.",
                inputSchema = withRequired(schema { conversationId() }, "conversation_id"),
                readOnly = true,
                gates = CHAT_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "compression_start",
                description = "Start context compression for a conversation of the bound assistant. Returns once the " +
                    "job is registered; poll compression_status for progress. Needs the model-request switch " +
                    "because compression asks a model.",
                inputSchema = withRequired(
                    writeSchema {
                        conversationId()
                        str(
                            "additional_prompt",
                            "Optional extra instruction for the summary, max ${AppMcpLimits.MAX_PROMPT_CHARS} chars.",
                        )
                        integer("keep_recent_messages", "Trailing messages to keep uncompressed, 0..200.")
                    },
                    "conversation_id",
                    IDEMPOTENCY_KEY_FIELD,
                ),
                readOnly = false,
                gates = MODEL_WRITE_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "compression_cancel",
                description = "Cancel the running background compression of a conversation of the bound assistant.",
                inputSchema = withRequired(
                    writeSchema { conversationId() },
                    "conversation_id",
                    IDEMPOTENCY_KEY_FIELD,
                ),
                readOnly = false,
                gates = CHAT_WRITE_GATES,
            )
        )

        // ---------- 自动归档 ----------
        add(
            AppMcpToolSpec(
                name = "autofile_status",
                description = "Auto-file status for the bound assistant: enabled, mode, idle hours, running, moved " +
                    "count, undo availability, last error.",
                inputSchema = schema { },
                readOnly = true,
                gates = CHAT_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "autofile_history",
                description = "Recent auto-file batches of the bound assistant, newest first: ids, titles, counts and " +
                    "status only. No message bodies, never another assistant's data.",
                inputSchema = schema { integer("limit", "Batch count, 1..10, default 5.") },
                readOnly = true,
                gates = CHAT_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "autofile_details",
                description = "Per-conversation detail of one auto-file batch of the bound assistant.",
                inputSchema = withRequired(
                    schema { str("batch_id", "Batch id from autofile_history.") },
                    "batch_id",
                ),
                readOnly = true,
                gates = CHAT_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "autofile_run",
                description = "Queue one auto-file pass for the bound assistant. Returns immediately with a work id; " +
                    "the pass keeps running in the background and is observable via autofile_status. Still honours " +
                    "the idle threshold and pinned/manual/locked protection.",
                inputSchema = writeSchema {
                    bool(
                        "confirm_external_model_usage",
                        "Required true when opening AI-mode auto-file from off: it sends a bounded summary to the " +
                            "fast model and spends quota.",
                    )
                },
                readOnly = false,
                gates = AUTOFILE_WRITE_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "autofile_cancel",
                description = "Cancel the in-flight auto-file batch of the bound assistant.",
                inputSchema = writeSchema { },
                readOnly = false,
                gates = AUTOFILE_WRITE_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "autofile_undo",
                description = "Undo the last auto-file batch of the bound assistant. Conversations chatted with, moved " +
                    "manually, pinned or locked meanwhile are skipped.",
                inputSchema = writeSchema { },
                readOnly = false,
                gates = AUTOFILE_WRITE_GATES,
            )
        )

        // ---------- 子代理任务 ----------
        add(
            AppMcpToolSpec(
                name = "subagent_list",
                description = "Subagent tasks belonging to conversations of the bound assistant. Tasks started from " +
                    "other assistants are never listed.",
                inputSchema = schema { integer("limit", "Task count, 1..${AppMcpLimits.MAX_PAGE}, default 20.") },
                readOnly = true,
                gates = CHAT_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "subagent_result",
                description = "Paged result text of one subagent task of the bound assistant. Status is always " +
                    "returned; results are re-readable and never consumed by reading.",
                inputSchema = withRequired(
                    schema {
                        str("task_id", "Subagent task id.")
                        integer("offset", "Character offset, default 0.")
                        integer(
                            "limit",
                            "Characters to return, 200..${AppMcpLimits.MAX_CHARS}, default ${AppMcpLimits.DEFAULT_CHARS}.",
                        )
                    },
                    "task_id",
                ),
                readOnly = true,
                gates = CHAT_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "subagent_cancel",
                description = "Cancel one subagent task of the bound assistant.",
                inputSchema = withRequired(
                    writeSchema { str("task_id", "Subagent task id.") },
                    "task_id",
                    IDEMPOTENCY_KEY_FIELD,
                ),
                readOnly = false,
                gates = CHAT_WRITE_GATES,
            )
        )

        // ---------- 本地自动化 ----------
        add(
            AppMcpToolSpec(
                name = "automation_list",
                description = "Local automation recipes plus the active run. Recipes are plain JSON steps; running " +
                    "them involves no model.",
                inputSchema = schema { },
                readOnly = true,
                gates = AUTOMATION_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "automation_recipe",
                description = "One local automation recipe by id, with steps and scheduling state.",
                inputSchema = withRequired(schema { str("recipe_id", "Recipe id.") }, "recipe_id"),
                readOnly = true,
                gates = AUTOMATION_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "automation_history",
                description = "Recent local automation runs with per-step status. Runs with unknown side effects are " +
                    "reported as such and never replayed automatically.",
                inputSchema = schema { integer("limit", "Run count, 1..${AppMcpLimits.MAX_HISTORY}, default 10.") },
                readOnly = true,
                gates = AUTOMATION_READ_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "automation_save",
                description = "Create or replace one local automation recipe. Steps may only use tools exposed right " +
                    "now; automation_* tools are refused inside a recipe (no recursion).",
                inputSchema = withRequired(
                    writeSchema {
                        str("recipe_id", "Existing recipe id to replace; omit to create a new one.")
                        str("name", "Recipe name, 1..60 chars.")
                        stepsArray()
                        integer("interval_minutes", "0 = manual only, otherwise >= 15.")
                        bool("enabled", "Schedule the interval automatically, default false.")
                    },
                    "name",
                    "steps",
                    IDEMPOTENCY_KEY_FIELD,
                ),
                readOnly = false,
                gates = AUTOMATION_WRITE_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "automation_schedule",
                description = "Turn scheduled execution of one recipe on or off. Turning on needs " +
                    "interval_minutes >= 15, otherwise nothing is scheduled.",
                inputSchema = withRequired(
                    writeSchema {
                        str("recipe_id", "Recipe id.")
                        bool("enabled", "true = schedule, false = cancel scheduling.")
                    },
                    "recipe_id",
                    "enabled",
                    IDEMPOTENCY_KEY_FIELD,
                ),
                readOnly = false,
                gates = AUTOMATION_WRITE_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "automation_run",
                description = "Run one recipe now in the background (WorkManager once-worker) and return a work id " +
                    "immediately; poll automation_history for the outcome. Never blocks the transport.",
                inputSchema = withRequired(
                    writeSchema { str("recipe_id", "Recipe id.") },
                    "recipe_id",
                    IDEMPOTENCY_KEY_FIELD,
                ),
                readOnly = false,
                gates = AUTOMATION_WRITE_GATES,
            )
        )
        add(
            AppMcpToolSpec(
                name = "automation_cancel",
                description = "Cancel the active local automation run (best effort: the running step finishes and is " +
                    "recorded truthfully).",
                inputSchema = writeSchema { },
                readOnly = false,
                gates = AUTOMATION_WRITE_GATES,
            )
        )
    }

    fun find(name: String): AppMcpToolSpec? = catalog().firstOrNull { it.name == name }

    fun isKnown(name: String): Boolean = find(name) != null

    /** tools/list 用：按当前开关过滤。call 时必须再核验一次。 */
    fun visible(gates: AppMcpGates): List<AppMcpToolSpec> = catalog().filter { gates.allows(it) }

    fun names(): List<String> = catalog().map { it.name }

    /**
     * 给设备桥的 schema 补上必有 `idempotency_key`（写动作）。
     * 设备工具 schema 由设备桥维护，这里只追加，不改它的语义。
     */
    fun withIdempotencyKey(schema: JsonObject): JsonObject {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val merged = buildJsonObject {
            properties.forEach { (k, v) -> put(k, v) }
            putJsonObject(IDEMPOTENCY_KEY_FIELD) {
                put("type", "string")
                put(
                    "description",
                    "Required for state-changing device actions. Reuse the same key when retrying a transport " +
                        "failure; the action is executed at most once.",
                )
            }
        }
        val rebuilt = buildJsonObject {
            schema.forEach { (k, v) -> if (k != "properties") put(k, v) }
            put("properties", merged)
        }
        return withRequired(rebuilt, IDEMPOTENCY_KEY_FIELD)
    }
}
