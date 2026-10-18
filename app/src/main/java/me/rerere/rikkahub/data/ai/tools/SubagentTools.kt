package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubagentRunner
import me.rerere.rikkahub.data.ai.subagent.SubagentStatus
import me.rerere.rikkahub.data.ai.subagent.SubagentTask
import me.rerere.rikkahub.data.datastore.SubagentSettings
import java.io.File
import kotlin.uuid.Uuid

/**
 * 子代理工具组。
 *
 * [childTools] 必须是"不含 subagent_*"的工具集，否则子代理可以无限嵌套。
 */
fun createSubagentTools(
    runner: SubagentRunner,
    childTools: List<Tool>,
    settings: SubagentSettings,
    allModelIds: List<Uuid>,
    conversationId: Uuid?,
): List<Tool> {
    val modelPool = if (settings.modelIds.isNotEmpty()) settings.modelIds else allModelIds
    val modelHint = if (modelPool.isEmpty()) {
        "当前没有单独配置模型池，省略 model_id 使用默认模型。"
    } else {
        "可选 model_id: " + modelPool.joinToString(", ") { it.toString() }
    }

    fun resolveModelId(raw: String?): Uuid? {
        if (raw.isNullOrBlank()) return null
        val id = runCatching { Uuid.parse(raw.trim()) }.getOrNull()
            ?: error("Invalid model_id: $raw")
        require(modelPool.isEmpty() || id in modelPool) { "model_id is not in the allowed subagent model pool" }
        return id
    }

    val run = Tool(
        name = "subagent_run",
        description = buildString {
            append("Spawn a focused subagent to work on one task in parallel. Returns a task id immediately. ")
            append("Call it multiple times to fan out independent work, then use subagent_wait to collect results. ")
            append("The subagent has its own context and the same workspace/tool access (except subagent tools). ")
            append(modelHint)
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Complete, self-contained task description. The subagent cannot see this conversation.")
                    })
                    put("model_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional model id from the allowed pool")
                    })
                    put("max_steps", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max model steps, default ${settings.maxSteps}")
                    })
                    put("timeout_sec", buildJsonObject {
                        put("type", "integer")
                        put("description", "Task timeout in seconds, default ${settings.timeoutSec}, 0 means no limit")
                    })
                },
                required = listOf("prompt"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val prompt = params.string("prompt") ?: error("prompt is required")
            val modelId = resolveModelId(params.string("model_id"))
            val maxSteps = params.string("max_steps")?.toIntOrNull() ?: settings.maxSteps
            val timeoutSec = params.string("timeout_sec")?.toIntOrNull() ?: settings.timeoutSec
            val task = runner.start(
                prompt = prompt,
                tools = childTools,
                modelId = modelId,
                conversationId = conversationId,
                maxSteps = maxSteps,
                timeoutSec = timeoutSec,
            )
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("taskId", task.id)
                        put("status", task.status.name)
                        put("modelId", task.modelId?.toString() ?: "")
                    }.toString()
                )
            )
        },
    )

    val wait = Tool(
        name = "subagent_wait",
        description = "Wait for a subagent to finish (or until the wait timeout) and return its status and result.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", buildJsonObject { put("type", "string") })
                    put("timeout_sec", buildJsonObject {
                        put("type", "integer")
                        put("description", "How long to wait, default 600 seconds")
                    })
                },
                required = listOf("task_id"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val id = params.string("task_id") ?: error("task_id is required")
            val waitSec = params.string("timeout_sec")?.toIntOrNull() ?: 600
            val task = runner.await(id, waitSec) ?: error("Task not found: $id")
            listOf(UIMessagePart.Text(task.toToolJson()))
        },
    )

    val result = Tool(
        name = "subagent_result",
        description = "Read the full result of a finished subagent task.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", buildJsonObject { put("type", "string") })
                },
                required = listOf("task_id"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val id = params.string("task_id") ?: error("task_id is required")
            val task = runner.get(id) ?: error("Task not found: $id")
            val text = task.result
                ?: task.resultPath?.let { path -> File(path).takeIf { it.isFile }?.readText() }
                ?: ""
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("taskId", task.id)
                        put("status", task.status.name)
                        put("result", text.take(32_000))
                        if (text.length > 32_000) put("truncated", true)
                    }.toString()
                )
            )
        },
    )

    val list = Tool(
        name = "subagent_list",
        description = "List subagent tasks started in this session with their status.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = { false },
        execute = {
            val items = runner.list().joinToString("\n") { t ->
                "${t.id}\t${t.status.name}\t${t.modelId}\t${t.prompt.take(60)}"
            }
            listOf(UIMessagePart.Text(items.ifBlank { "no subagent tasks" }))
        },
    )

    val cancel = Tool(
        name = "subagent_cancel",
        description = "Cancel a running subagent task.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task_id", buildJsonObject { put("type", "string") })
                },
                required = listOf("task_id"),
            )
        },
        needsApproval = { false },
        execute = {
            val params = it.jsonObject
            val id = params.string("task_id") ?: error("task_id is required")
            val ok = runner.cancel(id)
            listOf(UIMessagePart.Text(buildJsonObject { put("ok", ok) }.toString()))
        },
    )

    return listOf(run, wait, result, list, cancel)
}

private fun SubagentTask.toToolJson(): String = buildJsonObject {
    put("taskId", id)
    put("status", status.name)
    put("modelId", modelId?.toString() ?: "")
    put("steps", steps)
    put("error", error ?: "")
    put("result", (result ?: "").take(32_000))
    if (status == SubagentStatus.COMPLETED && (result?.length ?: 0) > 32_000) put("truncated", true)
}.toString()

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull
