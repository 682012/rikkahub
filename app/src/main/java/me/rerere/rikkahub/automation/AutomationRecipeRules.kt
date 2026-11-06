package me.rerere.rikkahub.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * recipe 的纯校验：在保存和运行前各跑一次。
 *
 * 规则：
 * - 名字非空、步骤 1..40；
 * - 只允许 runner 当前真的提供的工具名（没有白名单工具就直接拒绝，不存在"跑跑看"）；
 * - 禁止 automation_* 自调用（避免递归把设备点成一团）和 run_script（任意脚本不在白名单内）；
 * - 参数必须是 JSON 对象且不超过 8000 字符；
 * - 周期只能是 0（手动）或 >= 15 分钟（WorkManager 周期下限）。
 */
object AutomationRecipeRules {
    const val MAX_STEPS = 40
    const val MIN_INTERVAL_MINUTES = 15
    const val MAX_NAME_LENGTH = 60
    const val MAX_ARGUMENT_CHARS = 8_000
    private const val MAX_NOTE_LENGTH = 200

    private val forbiddenTools = setOf("run_script", "automation_run", "automation_save", "automation_delete")
    private val forbiddenPrefixes = listOf("automation_")

    fun normalizeInterval(minutes: Int): Int =
        if (minutes <= 0) 0 else minutes.coerceAtLeast(MIN_INTERVAL_MINUTES)

    fun validate(recipe: AutomationRecipe, catalog: List<AutomationToolSpec>): List<AutomationRecipeIssue> {
        val issues = mutableListOf<AutomationRecipeIssue>()
        val name = recipe.name.trim()
        if (name.isEmpty()) {
            issues += AutomationRecipeIssue(AutomationRecipeErrorCode.NAME_BLANK, message = "name is blank")
        } else if (name.length > MAX_NAME_LENGTH) {
            issues += AutomationRecipeIssue(AutomationRecipeErrorCode.NAME_TOO_LONG, message = "name is longer than $MAX_NAME_LENGTH")
        }
        if (recipe.steps.isEmpty()) {
            issues += AutomationRecipeIssue(AutomationRecipeErrorCode.NO_STEPS, message = "at least one step is required")
        }
        if (recipe.steps.size > MAX_STEPS) {
            issues += AutomationRecipeIssue(AutomationRecipeErrorCode.TOO_MANY_STEPS, message = "more than $MAX_STEPS steps")
        }
        if (recipe.intervalMinutes in 1 until MIN_INTERVAL_MINUTES) {
            issues += AutomationRecipeIssue(
                AutomationRecipeErrorCode.INTERVAL_TOO_SHORT,
                message = "periodic runs must be at least $MIN_INTERVAL_MINUTES minutes",
            )
        }
        val available = catalog.map { it.name }.toSet()
        recipe.steps.forEachIndexed { index, step ->
            val tool = step.tool.trim()
            if (tool.isEmpty() || tool !in available) {
                issues += AutomationRecipeIssue(
                    AutomationRecipeErrorCode.UNKNOWN_TOOL,
                    stepIndex = index,
                    tool = tool,
                    message = "tool is not available: $tool",
                )
            } else if (tool in forbiddenTools || forbiddenPrefixes.any { tool.startsWith(it) }) {
                issues += AutomationRecipeIssue(
                    AutomationRecipeErrorCode.TOOL_FORBIDDEN,
                    stepIndex = index,
                    tool = tool,
                    message = "tool cannot be used inside automation: $tool",
                )
            }
            val size = step.arguments.toString().length
            if (size > MAX_ARGUMENT_CHARS) {
                issues += AutomationRecipeIssue(
                    AutomationRecipeErrorCode.ARGUMENTS_TOO_LARGE,
                    stepIndex = index,
                    tool = tool,
                    message = "arguments are ${size} chars, limit $MAX_ARGUMENT_CHARS",
                )
            }
            if (step.note.length > MAX_NOTE_LENGTH) {
                issues += AutomationRecipeIssue(
                    AutomationRecipeErrorCode.BAD_ARGUMENTS,
                    stepIndex = index,
                    tool = tool,
                    message = "note is longer than $MAX_NOTE_LENGTH",
                )
            }
        }
        return issues
    }

    sealed class ParseResult {
        data class Ok(val recipe: AutomationRecipe) : ParseResult()
        data class Invalid(val message: String) : ParseResult()
    }

    /**
     * 从用户粘贴的 JSON 解析。允许两种写法：
     * 1. 完整 recipe 对象（name/steps/intervalMinutes/enabled）；
     * 2. 只给 steps 数组，外层字段用传入的默认值补。
     * id 由调用方保证（编辑现有配方时沿用原 id）。
     */
    fun parse(
        text: String,
        id: String,
        fallbackName: String = "",
        intervalMinutes: Int = 0,
        enabled: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): ParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ParseResult.Invalid("recipe JSON is empty")
        val element = runCatching { Json.parseToJsonElement(trimmed) }.getOrElse { error ->
            return ParseResult.Invalid("invalid JSON: ${error.message ?: "parse error"}")
        }
        val obj: JsonObject = when {
            element is JsonObject -> element
            element is kotlinx.serialization.json.JsonArray -> JsonObject(mapOf("steps" to element))
            else -> return ParseResult.Invalid("recipe must be a JSON object or a steps array")
        }
        // AutomationRecipe 的 id/name 是必填项：只给 steps 数组、或对象里少了 id/name 时，
        // 直接 decode 必然失败。所以先把调用方给的 id 和 fallbackName 填进对象，再解码。
        val withId = JsonObject(obj + ("id" to JsonPrimitive(id)))
        val prepared = if (withId.containsKey("name")) {
            withId
        } else {
            JsonObject(withId + ("name" to JsonPrimitive(fallbackName)))
        }
        // JSON 里显式写的 enabled/intervalMinutes 优先于调用方的默认值：
        // 导出的配方 enabled=false 再导入，不能因为外层默认 true 就被悄悄打开。
        val explicitEnabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull
        val explicitInterval = (obj["intervalMinutes"] as? JsonPrimitive)?.intOrNull
        return runCatching {
            val decoded = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(
                AutomationRecipe.serializer(),
                prepared,
            )
            ParseResult.Ok(
                decoded.copy(
                    id = id,
                    name = decoded.name.ifBlank { fallbackName },
                    intervalMinutes = normalizeInterval(explicitInterval ?: intervalMinutes),
                    enabled = explicitEnabled ?: enabled,
                    createdAt = if (decoded.createdAt > 0) decoded.createdAt else now,
                    updatedAt = now,
                ),
            )
        }.getOrElse { error ->
            ParseResult.Invalid("recipe fields are invalid: ${error.message ?: "decode error"}")
        }
    }

    /** 导出给用户看的规范 JSON（不含 id/时间戳，便于复制分享）。 */
    fun exportJson(recipe: AutomationRecipe): String =
        Json { prettyPrint = true; encodeDefaults = true }.encodeToString(
            AutomationRecipe.serializer(),
            recipe.copy(id = "", createdAt = 0, updatedAt = 0),
        )

    fun stepArgumentsObject(raw: JsonObject): JsonObject = raw.jsonObject
}
