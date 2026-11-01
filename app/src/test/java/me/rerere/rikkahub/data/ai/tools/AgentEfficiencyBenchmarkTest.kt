package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.core.MessageRole
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
        suspend fun replay(batched: Boolean): Pair<Long, Int> {
            val reader = Tool(name="read",description="Read fixture",executionMode=ToolExecutionMode.PARALLEL,
                execute={ delay(250);listOf(UIMessagePart.Text("ok")) })
            val calls = """{"calls":[{"name":"read"},{"name":"read"},{"name":"read"},{"name":"read"}]}"""
            fun assistant(part: UIMessagePart) = UIMessage(role=MessageRole.ASSISTANT,parts=listOf(part))
            val responses = if (batched) listOf(assistant(UIMessagePart.Tool("batch", "tool_batch", calls)))
                else (1..4).map { assistant(UIMessagePart.Tool("read$it", "read", "{}")) }
            val provider = EfficiencyReplayProvider(responses + assistant(UIMessagePart.Text("done")))
            val model=Model(modelId="benchmark")
            val hooks=object:AgentHooks {
                override suspend fun prepareStep(messages:List<UIMessage>)=StepRequest(messages,TextGenerationParams(model=model))
            }
            val start=testScheduler.currentTime
            runAgent(AgentRequest(provider=provider,providerSetting=ProviderSetting.OpenAI(),messages=listOf(UIMessage.user("read")),
                tools=listOf(reader,createToolBatch(listOf(reader))),stream=false,maxSteps=8),hooks).toList()
            return (testScheduler.currentTime-start) to provider.requests
        }
        val serial=replay(false);val parallel=replay(true)
        assertEquals(1_000L,serial.first);assertEquals(250L,parallel.first)
        assertEquals(5,serial.second);assertEquals(2,parallel.second)
        println("BENCHMARK_JSON=" + buildJsonObject {
            put("case","four_preplanned_independent_reads");put("virtualSerialMillis",serial.first);put("virtualParallelMillis",parallel.first)
            put("sequentialModelRequestsIncludingFinalReply",serial.second);put("batchedModelRequestsIncludingFinalReply",parallel.second)
            put("scope","Fake provider replay; preplanned independent arguments only. Live provider latency and token usage not measured")
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

private class EfficiencyReplayProvider(messages:List<UIMessage>):Provider<ProviderSetting> {
    private val responses=ArrayDeque(messages)
    var requests=0
    override suspend fun listModels(providerSetting:ProviderSetting):List<Model> = emptyList()
    override suspend fun generateText(providerSetting:ProviderSetting,messages:List<UIMessage>,params:TextGenerationParams):TextGenerationResult {
        requests++
        return TextGenerationResult(id="fixture-$requests",model="benchmark",message=responses.removeFirst())
    }
    override suspend fun streamText(providerSetting:ProviderSetting,messages:List<UIMessage>,params:TextGenerationParams):Flow<StreamChunk> = error("nonstream replay")
}
