package me.rerere.ai.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class AgentEmptyImageTest {
    private val model = Model(modelId = "image-test")
    private var captured = emptyList<UIMessage>()
    private val hooks = object : AgentHooks {
        override suspend fun prepareStep(messages: List<UIMessage>): StepRequest {
            captured = messages
            return StepRequest(messages, TextGenerationParams(model = model))
        }
    }
    private fun provider(payload: Boolean) = object : Provider<ProviderSetting> {
        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult = error("not used")
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = flow {
            emit(StreamChunk.ImageStart("image"))
            if (payload) emit(StreamChunk.ImageDelta("image", "bytes"))
            // EOF without ImageEnd or Finish, as seen on failed/incomplete gateways.
        }
    }
    private suspend fun result(payload: Boolean, messages: List<UIMessage> = listOf(UIMessage.user("draw"))): UIMessage {
        val request = AgentRequest(provider(payload), ProviderSetting.OpenAI(), messages, retry = RetryPolicy.NONE)
        return runAgent(request, hooks).toList().filterIsInstance<AgentEvent.Finished>().single().message
    }
    @Test fun eofWithOnlyImageStartDoesNotLeaveImage() = runTest {
        assertTrue(result(false).parts.none { it is UIMessagePart.Image })
    }
    @Test fun eofWithImageBytesPreservesImage() = runTest {
        val p = result(true).parts.filterIsInstance<UIMessagePart.Image>().single()
        assertEquals("data:image/png;base64,bytes", p.url)
    }
    @Test fun oldAssistantEmptyImagesAreNotSentToModel() = runTest {
        val old = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("old text"), UIMessagePart.Image("data:image/png;base64,")))
        result(false, listOf(UIMessage.user("old"), old, UIMessage.user("new")))
        assertTrue(captured[1].parts.none { it is UIMessagePart.Image })
        assertEquals("old text", captured[1].toText())
    }
}
