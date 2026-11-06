package me.rerere.rikkahub.automation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRecipeRulesTest {

    private val catalog = listOf(
        AutomationToolSpec("device_snapshot", readOnly = true),
        AutomationToolSpec("device_tap_node", readOnly = false),
    )

    private fun recipe(
        name: String = "Morning check",
        steps: List<AutomationStep> = listOf(AutomationStep("device_snapshot")),
        intervalMinutes: Int = 0,
    ) = AutomationRecipe(id = "r1", name = name, steps = steps, intervalMinutes = intervalMinutes)

    @Test
    fun `valid recipe has no issues`() {
        assertTrue(AutomationRecipeRules.validate(recipe(), catalog).isEmpty())
    }

    @Test
    fun `name and step count are enforced`() {
        val issues = AutomationRecipeRules.validate(recipe(name = "   ", steps = emptyList()), catalog)
        assertTrue(issues.any { it.code == AutomationRecipeErrorCode.NAME_BLANK })
        assertTrue(issues.any { it.code == AutomationRecipeErrorCode.NO_STEPS })

        val tooLong = AutomationRecipeRules.validate(recipe(name = "x".repeat(61)), catalog)
        assertTrue(tooLong.any { it.code == AutomationRecipeErrorCode.NAME_TOO_LONG })
    }

    @Test
    fun `unknown tools are rejected before any run`() {
        val issues = AutomationRecipeRules.validate(recipe(steps = listOf(AutomationStep("device_teleport"))), catalog)
        assertEquals(AutomationRecipeErrorCode.UNKNOWN_TOOL, issues.first().code)
        assertEquals(0, issues.first().stepIndex)
    }

    @Test
    fun `automation tools and scripts cannot be nested`() {
        val withAutomation = catalog + AutomationToolSpec("automation_run", readOnly = false) +
            AutomationToolSpec("run_script", readOnly = false)
        val issues = AutomationRecipeRules.validate(
            recipe(steps = listOf(AutomationStep("automation_run"), AutomationStep("run_script"))),
            withAutomation,
        )
        assertTrue(issues.all { it.code == AutomationRecipeErrorCode.TOOL_FORBIDDEN })
    }

    @Test
    fun `period must be zero or at least fifteen minutes`() {
        val issues = AutomationRecipeRules.validate(recipe(intervalMinutes = 5), catalog)
        assertTrue(issues.any { it.code == AutomationRecipeErrorCode.INTERVAL_TOO_SHORT })
        assertEquals(0, AutomationRecipeRules.normalizeInterval(0))
        assertEquals(0, AutomationRecipeRules.normalizeInterval(-3))
        assertEquals(15, AutomationRecipeRules.normalizeInterval(7))
        assertEquals(30, AutomationRecipeRules.normalizeInterval(30))
        assertTrue(AutomationRecipeRules.validate(recipe(intervalMinutes = 15), catalog).isEmpty())
    }

    @Test
    fun `oversized arguments are flagged`() {
        val big = buildJsonObject { put("text", "x".repeat(9_000)) }
        val issues = AutomationRecipeRules.validate(
            recipe(steps = listOf(AutomationStep("device_tap_node", big))),
            catalog,
        )
        assertTrue(issues.any { it.code == AutomationRecipeErrorCode.ARGUMENTS_TOO_LARGE })
    }

    @Test
    fun `parse accepts a steps array and fills the defaults`() {
        val result = AutomationRecipeRules.parse(
            text = """[{"tool":"device_snapshot","arguments":{}}]""",
            id = "r9",
            fallbackName = "Imported",
            intervalMinutes = 5,
            now = 123L,
        )
        assertTrue(result is AutomationRecipeRules.ParseResult.Ok)
        val parsed = (result as AutomationRecipeRules.ParseResult.Ok).recipe
        assertEquals("r9", parsed.id)
        assertEquals("Imported", parsed.name)
        assertEquals(1, parsed.steps.size)
        assertEquals(15, parsed.intervalMinutes) // 5 分钟被抬到下限
        assertEquals(123L, parsed.createdAt)
    }

    @Test
    fun `parse accepts a full recipe object`() {
        val result = AutomationRecipeRules.parse(
            text = """{"name":"Night","intervalMinutes":30,"steps":[{"tool":"device_tap_node","arguments":{"x":1}}]}""",
            id = "r2",
            now = 500L,
        )
        val parsed = (result as AutomationRecipeRules.ParseResult.Ok).recipe
        assertEquals("Night", parsed.name)
        assertEquals(30, parsed.intervalMinutes)
        assertEquals(JsonObject(mapOf("x" to kotlinx.serialization.json.JsonPrimitive(1))), parsed.steps.first().arguments)
    }

    @Test
    fun `parse rejects empty or broken json`() {
        assertTrue(AutomationRecipeRules.parse("", "r1") is AutomationRecipeRules.ParseResult.Invalid)
        assertTrue(AutomationRecipeRules.parse("{oops", "r1") is AutomationRecipeRules.ParseResult.Invalid)
        // 结构不对（steps 不是数组）也要拒绝，而不是当成空配方跑
        assertTrue(
            AutomationRecipeRules.parse("""{"steps":"nope"}""", "r1") is AutomationRecipeRules.ParseResult.Invalid,
        )
    }
}
