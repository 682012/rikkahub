package me.rerere.rikkahub.automation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 一个本地自动化配方：名字 + 一串纯 JSON 工具步骤，可选周期。
 *
 * 刻意不存模型、提示词或脚本：运行时不调模型，步骤就是工具名 + JSON 参数，任何人看得懂、改得动。
 */
@Serializable
data class AutomationRecipe(
    val id: String,
    val name: String,
    val steps: List<AutomationStep> = emptyList(),
    /** 0 = 只手动运行；>=15 = 周期分钟数。 */
    val intervalMinutes: Int = 0,
    /** 只有显式打开才会排 WorkManager 周期任务。 */
    val enabled: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /**
     * 保存时**固化**的权限域（当时绑定的助手）。运行期间绑定变了，后续步骤不许换成另一个助手。
     *
     * null = 旧配方（没有固化的域）：照旧可跑，但在本机 UI 显式保存一次后就会写死当时的作用域。
     */
    val scope: String? = null,
)

@Serializable
data class AutomationStep(
    val tool: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    /** 可选的备注，给用户/模型看，不参与执行。 */
    val note: String = "",
)

enum class AutomationTrigger {
    MANUAL,
    SCHEDULED,
    WORKER,
}

enum class AutomationRunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    /** 有步骤需要审批：自动化绝不代替用户点"同意"，直接停在这。 */
    BLOCKED_NEEDS_APPROVAL,
    /** 上一次运行可能留下了未知副作用：在用户确认前不重跑。 */
    BLOCKED_UNKNOWN_SIDE_EFFECT,
    /** 进程在执行写步骤时被杀，副作用是否发生未知。 */
    UNKNOWN_SIDE_EFFECT,
    /** 步骤/工具校验没通过，一步都没执行。 */
    INVALID,
    /** 同一时间已有别的 run 在进行：这次干脆没跑（不排队、不重叠、不重复副作用）。 */
    SKIPPED_BUSY,
}

enum class AutomationStepStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED,

    /**
     * 写步骤执行中被打断（取消/异常/进程死）：动作**可能**已经生效。
     * 和 FAILED 的区别是 FAILED 是工具明确报错、确定没生效；这里必须等用户确认，绝不自动重跑。
     */
    UNKNOWN_SIDE_EFFECT,
}

@Serializable
data class AutomationStepResult(
    val index: Int,
    val tool: String,
    val status: AutomationStepStatus,
    /** 给用户看的一句话；不含 tool 的完整输出。 */
    val detail: String = "",
    /** 该步骤是否可能改变设备/App 状态。只读步骤不算。 */
    val sideEffect: Boolean = false,
)

@Serializable
data class AutomationRun(
    val id: String,
    val recipeId: String,
    val recipeName: String,
    val trigger: AutomationTrigger,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: AutomationRunStatus,
    val steps: List<AutomationStepResult> = emptyList(),
    val message: String = "",
    /** 用户是否已经确认过"上一轮未知副作用"可以继续。 */
    val acknowledged: Boolean = false,
)

/**
 * 一条"必须用户确认才能再跑"的记录。存在 store 里、**不受清历史影响**：
 * 否则点一下"清除历史"就能把未知副作用抹掉，等于绕过确认。
 */
@Serializable
data class AutomationPendingBlock(
    val runId: String,
    val recipeId: String,
    val recipeName: String = "",
    val at: Long = 0,
    val message: String = "",
)

/** 保存/运行前的结果，UI 据此显示错误而不是静默吞掉。 */
sealed class AutomationSaveResult {
    data class Saved(val recipe: AutomationRecipe) : AutomationSaveResult()
    data class Invalid(val issues: List<AutomationRecipeIssue>, val message: String) : AutomationSaveResult()
}

/** 校验问题。UI 按 code 自己翻译，日志里用 message。 */
data class AutomationRecipeIssue(
    val code: AutomationRecipeErrorCode,
    val stepIndex: Int? = null,
    val tool: String? = null,
    val message: String = "",
)

enum class AutomationRecipeErrorCode {
    NAME_BLANK,
    NAME_TOO_LONG,
    NO_STEPS,
    TOO_MANY_STEPS,
    INTERVAL_TOO_SHORT,
    UNKNOWN_TOOL,
    TOOL_FORBIDDEN,
    BAD_ARGUMENTS,
    ARGUMENTS_TOO_LARGE,
    /** 配方本身没问题，但 WorkManager 没接受定时请求：不能谎称"已排上"。 */
    SCHEDULING_FAILED,
}

/** 主代理注册进来的工具视图（避免 automation 依赖 mcpserver）。 */
data class AutomationToolSpec(
    val name: String,
    val description: String = "",
    val readOnly: Boolean = true,
)

/** 一次工具执行结果。needsApproval=true 表示工具要求用户确认，自动化必须停下。 */
data class AutomationToolResult(
    val ok: Boolean,
    val message: String = "",
    val output: JsonObject = JsonObject(emptyMap()),
    val needsApproval: Boolean = false,
    /** 工具自己声明"可能改了状态"，用于历史展示；readOnly 工具可忽略。 */
    val wroteSideEffect: Boolean = false,
    /**
     * 结果本身不明（例如"已交出去但没等到回执"）：写动作**可能**已经生效。
     * 引擎必须按未知副作用处理、等用户确认，绝不能记成 SUCCEEDED。
     */
    val sideEffectUnknown: Boolean = false,
)

/**
 * 执行器回调。主代理实现它，把 MCP / 内部工具统一接进来：
 *
 *  - tools() 返回当前真正可用的工具（含设备桥的 device_*），自动化只允许调用这些；
 *  - execute() 负责真正执行，不能自动通过审批。
 */
interface AutomationToolRunner {
    fun tools(): List<AutomationToolSpec>
    suspend fun execute(tool: String, arguments: JsonObject): AutomationToolResult

    /**
     * 此刻的权限域（绑定助手）。配方保存时会固化这个值；纯测试/未接入的实现返回 null 即可。
     */
    fun currentScope(): String? = null

    /**
     * 运行中校验：固化时的 [scope] 是否仍然是当前的权限域。
     *
     * 引擎在**每一步**执行前调用。返回 false 表示绑定/授权已经漂移，这一步以及后面的步骤一律不执行，
     * 绝不会"用另一个助手的身份"接着跑。null 表示旧配方没有固化域（照旧可跑）。
     */
    fun validateRunScope(scope: String?): Boolean = true
}

/**
 * 把 `host.call` 的结果 JSON 映射成 [AutomationToolResult]。纯函数（只依赖 JSON），可直接单测。
 *
 * 关键是不能把所有情况都压成 ok/failed 两态：
 * - `status=unknown` / `write_outcome_unknown` / 根本没有结果 → **未知副作用**（写可能已生效，必须等确认）；
 * - `not_granted` / `pending` 这类业务码 → **本次没有执行**（不是副作用，也不是未知）；
 * - 显式审批要求 → `needsApproval`，自动化停在原地，不代替用户点同意。
 */
object AutomationToolOutcomes {
    private val approvalCodes = setOf("needs_approval", "requires_approval", "approval_required")

    /** 明确"这一步没有执行"的业务码：既没有副作用，也不算未知。 */
    private val notExecutedCodes = setOf(
        "not_granted",
        "pending",
        "pending_approval",
        "unknown_tool",
        "tool_forbidden",
        "bad_argument",
        "not_found",
        "not_owned",
        "no_bound_assistant",
        "idempotency_key_required",
        "queue_rejected",
        "ownership_not_persisted",
        "invalid_recipe",
        "interval_too_short",
        "not_implemented",
    )

    /** 结果不明：写操作可能已经生效。 */
    private val unknownCodes = setOf(
        "write_outcome_unknown",
        "unknown",
        "unknown_outcome",
        "timeout",
        "timed_out",
        "receipt_timeout",
    )

    fun fromHostResult(tool: String, readOnly: Boolean?, result: JsonObject): AutomationToolResult {
        fun text(key: String): String? = (result[key] as? JsonPrimitive)?.contentOrNull
        fun flag(key: String): Boolean? = (result[key] as? JsonPrimitive)?.booleanOrNull

        val okField = flag("ok")
        val error = text("error")
        val status = text("status")
        val message = text("message") ?: text("note")
        // readOnly 未知（例如设备清单读不出来）时按写处理：宁可多要确认，不能漏掉副作用。
        val isWrite = readOnly != true
        val needsApproval = flag("needs_approval") == true || error in approvalCodes
        val detail = when {
            error != null -> if (message.isNullOrBlank()) error else "$error: $message"
            !message.isNullOrBlank() -> message
            status != null -> "status=$status"
            else -> ""
        }

        if (needsApproval) {
            return AutomationToolResult(
                ok = false,
                message = "needs_approval: ${detail.ifBlank { tool }}".take(300),
                output = result,
                needsApproval = true,
                wroteSideEffect = false,
                sideEffectUnknown = false,
            )
        }
        if (error != null && error in notExecutedCodes) {
            // 本次没有执行：不是副作用，也不能当成"可能生效"
            return AutomationToolResult(
                ok = false,
                message = detail.take(300),
                output = result,
                needsApproval = false,
                wroteSideEffect = false,
                sideEffectUnknown = false,
            )
        }
        if (status == "unknown" || error in unknownCodes || okField == null) {
            // 交出去了但没等到回执 / 结果读不懂：写操作可能已生效，必须按未知处理
            return AutomationToolResult(
                ok = okField == true,
                message = detail.ifBlank { "outcome unknown" }.take(300),
                output = result,
                needsApproval = false,
                wroteSideEffect = isWrite,
                sideEffectUnknown = isWrite,
            )
        }
        val ok = okField == true
        return AutomationToolResult(
            ok = ok,
            message = (if (ok) detail.ifBlank { "ok" } else detail).take(300),
            output = result,
            needsApproval = false,
            wroteSideEffect = isWrite,
            sideEffectUnknown = false,
        )
    }
}
