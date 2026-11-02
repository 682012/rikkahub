package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class WebToolApprovalTest {
    @Test fun `web tools always stop for approval even when ordinary tool policy says automatic`() = runTest {
        var executed = 0
        val tool = Tool("write", "effect", needsApproval = { false }, execute = { executed++; emptyList() })
        val provider = object : Provider<ProviderSetting> {
            override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
            override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams) =
                TextGenerationResult("id", "m", UIMessage(role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Tool("call1", "write", "{}"))))
            override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = error("not used")
        }
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(messages, TextGenerationParams(Model()))
        }
        for (setting in listOf(ProviderSetting.GeminiWeb(toolBridgeEnabled = true), ProviderSetting.ClaudeWeb(toolBridgeEnabled = true))) {
            val events = runAgent(AgentRequest(provider, setting, listOf(UIMessage.user("test")), tools = listOf(tool), stream = false), hooks).toList()
            val end = events.filterIsInstance<AgentEvent.Finished>().last()
            assertTrue(end.reason is AgentFinishReason.AwaitingApproval)
            assertEquals(ToolApprovalState.Pending, end.message.getTools().single().approvalState)
            assertEquals(0, executed)
        }
    }
}
