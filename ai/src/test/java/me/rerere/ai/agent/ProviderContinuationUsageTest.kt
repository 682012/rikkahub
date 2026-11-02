package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class ProviderContinuationUsageTest {
    private val aggregate = TokenUsage(promptTokens = 270_000, completionTokens = 800, cachedTokens = 200_000, totalTokens = 270_800)
    private val last = TokenUsage(promptTokens = 90_000, completionTokens = 200, cachedTokens = 80_000, totalTokens = 90_200)
    private val provider = object : Provider<ProviderSetting> {
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams) =
            TextGenerationResult("id", "m", UIMessage(role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("final")), usage = aggregate, lastUsage = last), usage = aggregate)
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = flow {
            emit(StreamChunk.Usage(aggregate, completeSnapshot = true, lastRequestUsage = last))
            emit(StreamChunk.TextStart("t"));emit(StreamChunk.TextDelta("t", "final"));emit(StreamChunk.TextEnd("t"))
            emit(StreamChunk.Finish("stop"))
        }
    }
    private val hooks = object : AgentHooks {
        override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(messages, TextGenerationParams(Model()))
    }
    @Test fun nonStreamingContinuationKeepsActualContextSeparateFromAggregateCost() = runTest {
        val end = runAgent(AgentRequest(provider, ProviderSetting.Claude(), listOf(UIMessage.user("test")), stream = false), hooks)
            .toList().filterIsInstance<AgentEvent.Finished>().last().message
        assertEquals(aggregate, end.usage)
        assertEquals(last, end.lastUsage)
        assertEquals(90_200, end.lastUsage!!.totalTokens)
    }
    @Test fun streamingContinuationKeepsActualContextSeparateFromAggregateCost() = runTest {
        val end = runAgent(AgentRequest(provider, ProviderSetting.Claude(), listOf(UIMessage.user("test")), stream = true), hooks)
            .toList().filterIsInstance<AgentEvent.Finished>().last().message
        assertEquals(aggregate, end.usage)
        assertEquals(last, end.lastUsage)
    }
}
