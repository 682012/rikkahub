package me.rerere.rikkahub.automation

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationStepVariablesTest {

    private val snapshotOutput = buildJsonObject {
        put("ok", true)
        put("snapshot_id", "snap-100-1")
        put("package", "com.example.notes")
        put(
            "nodes",
            buildJsonArray {
                add(buildJsonObject { put("id", "0/0"); put("text", "Search") })
                add(buildJsonObject { put("id", "0/1"); put("text", "New note") })
            },
        )
    }

    @Test
    fun `snapshot id and node id from a previous step are bound into the next step`() {
        val args = buildJsonObject {
            put("snapshot_id", "{{0.snapshot_id}}")
            put("node_id", "{{0.nodes.1.id}}")
            put("text", "hello {{0.package}}")
        }
        val resolved = AutomationStepVariables.resolve(args, listOf(snapshotOutput))
        assertTrue(resolved.ok)
        assertEquals("snap-100-1", resolved.arguments["snapshot_id"]!!.jsonPrimitive.content)
        assertEquals("0/1", resolved.arguments["node_id"]!!.jsonPrimitive.content)
        assertEquals("hello com.example.notes", resolved.arguments["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `whole placeholder keeps the original json type`() {
        val output = buildJsonObject { put("count", 3) }
        val args = buildJsonObject { put("limit", "{{0.count}}") }
        val resolved = AutomationStepVariables.resolve(args, listOf(output))
        assertTrue(resolved.ok)
        assertEquals(JsonPrimitive(3), resolved.arguments["limit"])
    }

    @Test
    fun `referencing a future step or missing field is reported instead of silently emptied`() {
        val args = buildJsonObject { put("snapshot_id", "{{1.snapshot_id}}") }
        val resolved = AutomationStepVariables.resolve(args, listOf(snapshotOutput))
        assertFalse(resolved.ok)
        assertEquals(listOf("{{1.snapshot_id}}"), resolved.unresolved)
        // 原样保留，绝不变成空串去点奇怪的位置
        assertEquals("{{1.snapshot_id}}", resolved.arguments["snapshot_id"]!!.jsonPrimitive.content)

        val missing = AutomationStepVariables.resolve(
            buildJsonObject { put("node_id", "{{0.nodes.9.id}}") },
            listOf(snapshotOutput),
        )
        assertFalse(missing.ok)
    }

    @Test
    fun `plain arguments are untouched`() {
        val args = buildJsonObject { put("x", 10); put("y", 20) }
        val resolved = AutomationStepVariables.resolve(args, emptyList())
        assertTrue(resolved.ok)
        assertEquals(args, resolved.arguments)
    }
}
