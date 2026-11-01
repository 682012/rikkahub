package me.rerere.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class ToolBatchTest {
    private fun tool(name: String, mode: ToolExecutionMode = ToolExecutionMode.SERIAL,
        approval: (JsonElement) -> Boolean = { false },
        execute: suspend (JsonElement) -> List<UIMessagePart> = { listOf(UIMessagePart.Text(name)) },
    ) = Tool(name = name, description = name, needsApproval = approval, executionMode = mode, execute = execute)
    private fun args(vararg names: String) = buildJsonObject {
        put("calls", buildJsonArray { names.forEach { name -> add(buildJsonObject { put("name", name) }) } })
    }
    private fun output(parts: List<UIMessagePart>) = Json.parseToJsonElement((parts.first() as UIMessagePart.Text).text)
        .jsonObject["results"]!!.jsonArray

    @Test fun `independent reads overlap and outputs preserve order`() = runTest {
        var active = 0; var peak = 0
        val tools = (1..16).map { n -> tool("read$n", ToolExecutionMode.PARALLEL) {
            active++; peak = maxOf(peak, active); delay(100); active--; listOf(UIMessagePart.Text("$n"))
        } }
        val results = output(createToolBatch(tools).execute(args(*tools.map { it.name }.toTypedArray())))
        assertEquals(8, peak)
        assertEquals((1..16).map { "read$it" }, results.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }
    @Test fun `serial tools are barriers and unknown tools fail before any effects`() = runTest {
        val log = mutableListOf<String>()
        val r = tool("read", ToolExecutionMode.PARALLEL) { log += "r"; delay(20); log += "R"; emptyList() }
        val w = tool("write") { log += "w"; emptyList() }
        createToolBatch(listOf(r,w)).execute(args("read","write","read"))
        assertEquals(listOf("r","R","w","r","R"), log)
        log.clear()
        assertTrue(runCatching { createToolBatch(listOf(w)).execute(args("write","missing")) }.isFailure)
        assertTrue(log.isEmpty())
    }
    @Test fun `same read arguments merge within batch but not across executions`() = runTest {
        var runs = 0
        val r = tool("read", ToolExecutionMode.PARALLEL_DEDUPLICATED) { runs++; listOf(UIMessagePart.Text("fresh")) }
        val calls = buildJsonObject { put("calls", buildJsonArray {
            listOf(buildJsonObject { put("a",1);put("b",2) }, buildJsonObject { put("b",2);put("a",1) }).forEach {
                add(buildJsonObject { put("name","read");put("arguments",it) })
            }
        }) }
        val batch = createToolBatch(listOf(r))
        assertEquals(2, output(batch.execute(calls)).size); assertEquals(1,runs)
        batch.execute(calls); assertEquals(2,runs)
    }
    @Test fun `parallel effects do not merge and inner approval is preserved`() = runTest {
        var runs = 0
        val effect = tool("spawn",ToolExecutionMode.PARALLEL, approval = { it.jsonObject["danger"] == JsonPrimitive(true) }) {
            runs++; emptyList()
        }
        val batch = createToolBatch(listOf(effect))
        batch.execute(args("spawn","spawn")); assertEquals(2,runs)
        val guarded = buildJsonObject { put("calls",buildJsonArray {
            add(buildJsonObject { put("name","spawn");put("arguments",buildJsonObject { put("danger",true) }) })
        }) }
        assertTrue(batch.needsApproval(guarded))
        assertFalse(batch.needsApproval(args("spawn")))
    }
    @Test fun `invalid self interactive and oversized calls execute nothing`() = runTest {
        var runs = 0
        val t = tool("write") { runs++; emptyList() }
        val batch = createToolBatch(listOf(t,tool("ask_user")))
        listOf(args(),args("tool_batch"),args("ask_user"),args(*Array(17) { "write" }),buildJsonObject {
            put("calls",buildJsonArray { add(buildJsonObject { put("name","write");put("arguments","not-object") }) })
        }).forEach { assertTrue(runCatching { batch.execute(it) }.isFailure) }
        assertEquals(0,runs)
    }
    @Test fun `partial failure retains other results and attachments`() = runTest {
        val image = UIMessagePart.Image("file:///image.png")
        val batch = createToolBatch(listOf(tool("bad") { error("failed") }, tool("image") { listOf(image) }, tool("empty") { emptyList() }))
        val parts = batch.execute(args("bad","image","empty")); val results = output(parts)
        assertTrue(results[0].toString().contains("failed"))
        assertSame(image,parts[1]); assertTrue(results[2].toString().contains("(no output)"))
    }
    @Test fun `cancellation propagates instead of becoming a tool error`() = runTest {
        val batch = createToolBatch(listOf(tool("cancel",ToolExecutionMode.PARALLEL) { throw CancellationException("stop") }))
        assertTrue(runCatching { batch.execute(args("cancel")) }.exceptionOrNull() is CancellationException)
    }
}
