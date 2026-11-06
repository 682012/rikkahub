package me.rerere.rikkahub.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r22 final automation fixes, pure-JVM only.
 *
 * Covers the three实锤 bugs that live entirely in pure code:
 * - recipe JSON import (a bare steps array / an object without id+name used to be *always* rejected,
 *   and `decoded.enabled || enabled` used to switch an explicitly disabled export back on);
 * - host result classification (write polarity was inverted; ok=true+status=unknown was silently
 *   SUCCEEDED; not_granted/pending were reported as side effects);
 * - the runner scope contract (freeze at save, validate per step) stays backward compatible.
 */
class AutomationRuntimeContractTest {

    private fun AutomationRecipeRules.ParseResult.ok(): AutomationRecipe =
        (this as AutomationRecipeRules.ParseResult.Ok).recipe

    // ------------------------------------------------------------ recipe JSON import

    @Test
    fun `a bare steps array decodes with the given id and fallback name`() {
        val parsed = AutomationRecipeRules.parse(
            text = """[{"tool":"device_snapshot","arguments":{}}]""",
            id = "r-array",
            fallbackName = "Pasted steps",
        )
        val recipe = parsed.ok()
        assertEquals("r-array", recipe.id)
        assertEquals("Pasted steps", recipe.name)
        assertEquals(1, recipe.steps.size)
        assertEquals("device_snapshot", recipe.steps[0].tool)
        assertFalse(recipe.enabled)
    }

    @Test
    fun `an object without id or name is accepted`() {
        val recipe = AutomationRecipeRules.parse(
            text = """{"steps":[{"tool":"device_snapshot"}]}""",
            id = "r-no-id",
            fallbackName = "Fallback name",
        ).ok()
        assertEquals("r-no-id", recipe.id)
        assertEquals("Fallback name", recipe.name)
        assertEquals(1, recipe.steps.size)
    }

    @Test
    fun `an explicit enabled false in the json wins over the caller default`() {
        val recipe = AutomationRecipeRules.parse(
            text = """{"name":"Quiet","enabled":false,"steps":[{"tool":"device_snapshot"}]}""",
            id = "r-off",
            enabled = true,
        ).ok()
        assertFalse("explicit JSON enabled=false must not be OR-ed away", recipe.enabled)
    }

    @Test
    fun `an explicit enabled true still enables the recipe`() {
        val recipe = AutomationRecipeRules.parse(
            text = """{"name":"Loud","enabled":true,"steps":[{"tool":"device_snapshot"}]}""",
            id = "r-on",
        ).ok()
        assertTrue(recipe.enabled)
    }

    @Test
    fun `an explicit zero interval stays manual instead of inheriting the caller value`() {
        val recipe = AutomationRecipeRules.parse(
            text = """{"name":"Manual","intervalMinutes":0,"steps":[{"tool":"device_snapshot"}]}""",
            id = "r-manual",
            intervalMinutes = 30,
        ).ok()
        assertEquals(0, recipe.intervalMinutes)
    }

    @Test
    fun `export then import round trips the recipe including its frozen scope`() {
        val original = AutomationRecipe(
            id = "r-round",
            name = "Round trip",
            steps = listOf(
                AutomationStep("device_tap_node", buildJsonObject { put("node_id", "n-1") }, note = "tap it"),
            ),
            intervalMinutes = 30,
            enabled = true,
            scope = "assistant-1",
        )
        val exported = AutomationRecipeRules.exportJson(original)
        val imported = AutomationRecipeRules.parse(exported, id = "r-imported", fallbackName = "ignored").ok()
        assertEquals("r-imported", imported.id)
        assertEquals(original.name, imported.name)
        assertEquals(original.intervalMinutes, imported.intervalMinutes)
        assertTrue(imported.enabled)
        assertEquals("assistant-1", imported.scope)
        assertEquals(1, imported.steps.size)
        assertEquals(original.steps[0].arguments, imported.steps[0].arguments)
    }

    @Test
    fun `a legacy recipe without scope decodes to a null scope`() {
        val legacy = """{"id":"old","name":"Old","steps":[{"tool":"device_snapshot"}],""" +
            """"intervalMinutes":0,"enabled":false}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(
            AutomationRecipe.serializer(),
            Json.parseToJsonElement(legacy),
        )
        assertNull(decoded.scope)
    }

    // ------------------------------------------------------------ host result classification

    @Test
    fun `not_granted is recorded as not executed, not as an unknown side effect`() {
        val result = AutomationToolOutcomes.fromHostResult(
            tool = "device_tap_node",
            readOnly = false,
            result = buildJsonObject {
                put("ok", false)
                put("error", "not_granted")
                put("message", "disabled by switches")
            },
        )
        assertFalse(result.ok)
        assertFalse(result.wroteSideEffect)
        assertFalse(result.sideEffectUnknown)
        assertFalse(result.needsApproval)
        assertTrue(result.message.contains("not_granted"))
    }

    @Test
    fun `a pending business result is not reported as a side effect`() {
        val result = AutomationToolOutcomes.fromHostResult(
            tool = "chat_send",
            readOnly = false,
            result = buildJsonObject {
                put("ok", false)
                put("error", "pending")
            },
        )
        assertFalse(result.ok)
        assertFalse(result.wroteSideEffect)
        assertFalse(result.sideEffectUnknown)
    }

    @Test
    fun `ok true with status unknown maps to an unknown side effect`() {
        val result = AutomationToolOutcomes.fromHostResult(
            tool = "chat_send",
            readOnly = false,
            result = buildJsonObject {
                put("ok", true)
                put("status", "unknown")
                put("note", "enqueue receipt timed out")
            },
        )
        assertTrue("the host did accept it", result.ok)
        assertTrue("but the write may already have happened", result.sideEffectUnknown)
        assertTrue(result.wroteSideEffect)
    }

    @Test
    fun `write_outcome_unknown maps to an unknown side effect`() {
        val result = AutomationToolOutcomes.fromHostResult(
            tool = "device_tap_node",
            readOnly = false,
            result = buildJsonObject {
                put("ok", false)
                put("error", "write_outcome_unknown")
                put("message", "fatal OOM; side effect unknown")
            },
        )
        assertFalse(result.ok)
        assertTrue(result.sideEffectUnknown)
    }

    @Test
    fun `an unreadable result is never reported as a clean failure`() {
        val result = AutomationToolOutcomes.fromHostResult("device_tap_node", false, JsonObject(emptyMap()))
        assertFalse(result.ok)
        assertTrue(result.sideEffectUnknown)
        assertFalse(result.needsApproval)
    }

    @Test
    fun `an explicit approval requirement is preserved instead of hardcoded false`() {
        val byCode = AutomationToolOutcomes.fromHostResult(
            "device_tap_node",
            false,
            buildJsonObject {
                put("ok", false)
                put("error", "needs_approval")
            },
        )
        assertTrue(byCode.needsApproval)
        assertFalse(byCode.wroteSideEffect)
        assertFalse(byCode.sideEffectUnknown)

        val byFlag = AutomationToolOutcomes.fromHostResult(
            "device_tap_node",
            false,
            buildJsonObject {
                put("ok", true)
                put("needs_approval", true)
            },
        )
        assertTrue(byFlag.needsApproval)
        assertFalse(byFlag.ok)
    }

    @Test
    fun `a successful write is a side effect while a successful read is not`() {
        val write = AutomationToolOutcomes.fromHostResult("device_tap_node", false, buildJsonObject { put("ok", true) })
        assertTrue(write.ok)
        assertTrue("a successful device write must be recorded as a side effect", write.wroteSideEffect)
        assertFalse(write.sideEffectUnknown)

        val read = AutomationToolOutcomes.fromHostResult("device_snapshot", true, buildJsonObject { put("ok", true) })
        assertTrue(read.ok)
        assertFalse("a read must not be recorded as a side effect", read.wroteSideEffect)
    }

    @Test
    fun `an unknown read-only flag is treated as a write`() {
        val result = AutomationToolOutcomes.fromHostResult("device_mystery", null, buildJsonObject { put("ok", true) })
        assertTrue(result.wroteSideEffect)
    }

    // ------------------------------------------------------------ runner scope contract

    @Test
    fun `the default runner contract keeps legacy recipes runnable`() {
        val runner = object : AutomationToolRunner {
            override fun tools(): List<AutomationToolSpec> = emptyList()
            override suspend fun execute(tool: String, arguments: JsonObject): AutomationToolResult =
                AutomationToolResult(ok = true)
        }
        assertNull(runner.currentScope())
        assertTrue(runner.validateRunScope(null))
        assertTrue(runner.validateRunScope("assistant-1"))
    }

    @Test
    fun `a scope aware runner reports drift without touching anything else`() {
        val runner = object : AutomationToolRunner {
            var scope: String? = "assistant-1"
            override fun tools(): List<AutomationToolSpec> = emptyList()
            override suspend fun execute(tool: String, arguments: JsonObject): AutomationToolResult =
                AutomationToolResult(ok = true)
            override fun currentScope(): String? = scope
            override fun validateRunScope(scope: String?): Boolean = scope == null || scope == this.scope
        }
        assertTrue(runner.validateRunScope("assistant-1"))
        runner.scope = "assistant-2"
        assertFalse("binding drift must be detectable per step", runner.validateRunScope("assistant-1"))
        assertTrue(runner.validateRunScope(null))
    }
}
