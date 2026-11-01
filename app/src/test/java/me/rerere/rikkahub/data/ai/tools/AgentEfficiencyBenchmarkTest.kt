package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.agent.createToolBatch
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionMode
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Deterministic synthetic replay: schema characters and virtual scheduling time, NOT provider token bills. */
class AgentEfficiencyBenchmarkTest {
    @Test fun `large MCP catalog has a stable small advertised schema`() {
        val catalog = (1..120).map { n -> Tool(
            name = "mcp__fixture__tool_$n",
            description = "Read fixture $n. " + "Return targeted data for the selected resource. ".repeat(8),
            parameters = { InputSchema.Obj(buildJsonObject {
                put("path", buildJsonObject { put("type", "string"); put("description", "Absolute fixture path") })
                put("limit", buildJsonObject { put("type", "integer"); put("description", "Maximum items to read") })
            }, listOf("path")) },
            execute = { listOf(UIMessagePart.Text("fixture")) },
        ) }
        fun size(tools: List<Tool>) = tools.sumOf { it.name.length + it.description.length + it.parameters().toJsonSchema().toString().length }
        val before = size(catalog); val compact = compactMcpTools(catalog); val after = size(compact)
        assertEquals(2, compact.size); assertTrue(after.toDouble()/before < 0.15)
        println("BENCHMARK_JSON=" + buildJsonObject {
            put("case", "120_synthetic_mcp_tools"); put("unit", "characters_not_tokens")
            put("nativeTools", catalog.size); put("compactTools", compact.size)
            put("nativeSchemaChars", before); put("compactSchemaChars", after)
            put("savedRatio", 1.0-after.toDouble()/before)
        })
    }
    @Test fun `four known independent reads are one batch instead of four model steps`() = runTest {
        suspend fun run(mode: ToolExecutionMode): Long {
            val reader = Tool(name="read",description="Read fixture",executionMode=mode,execute={ delay(250);listOf(UIMessagePart.Text("ok")) })
            val calls = Json.parseToJsonElement("""{"calls":[{"name":"read"},{"name":"read"},{"name":"read"},{"name":"read"}]}""")
            val start=testScheduler.currentTime; createToolBatch(listOf(reader)).execute(calls)
            return testScheduler.currentTime-start
        }
        val serial=run(ToolExecutionMode.SERIAL);val parallel=run(ToolExecutionMode.PARALLEL)
        assertEquals(1_000,serial);assertEquals(250,parallel)
        println("BENCHMARK_JSON=" + buildJsonObject {
            put("case","four_preplanned_independent_reads");put("virtualSerialMillis",serial);put("virtualParallelMillis",parallel)
            put("sequentialModelRequestsIncludingFinalReply",5);put("batchedModelRequestsIncludingFinalReply",2)
            put("scope","Preplanned independent arguments only; provider latency and actual token usage not measured")
        })
    }
    @Test fun `large output is retained and initial model context is bounded`() = runTest {
        val dir=Files.createTempDirectory("agent-output-bench").toFile()
        try {
            val full="0123456789".repeat(4_000)
            val source=Tool(name="fixture",description="Fixture",execute={listOf(UIMessagePart.Text(full))})
            val output=ToolOutputStore(dir,"benchmark").wrap(source).execute(JsonObject(emptyMap()))
            val preview=(output.single() as UIMessagePart.Text).text
            assertTrue(preview.length < 6_000);assertEquals(full,dir.listFiles()!!.single { it.extension=="txt" }.readText())
            println("BENCHMARK_JSON=" + buildJsonObject {
                put("case","40k_character_output");put("fullChars",full.length);put("initialContextChars",preview.length)
                put("fullTextRetained",true);put("unit","characters_not_tokens")
            })
        } finally { dir.deleteRecursively() }
    }
}
