package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Exact parser → executed tool → wire serializer regressions; no external request. */
class ChatCompletionsReasoningReplayTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private fun parsed(): UIMessage {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("parseMessage", JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(api, buildJsonObject {
            put("role", "assistant")
            put("reasoning_content", "inspect first\nthen use tool")
            put("content", "I'll inspect the file.")
            putJsonArray("tool_calls") { add(buildJsonObject {
                put("id", "call_read"); put("type", "function")
                putJsonObject("function") { put("name", "read"); put("arguments", "{}") }
            }) }
        }) as UIMessage
    }
    private fun executed(message: UIMessage): UIMessage = message.copy(parts = message.parts.map {
        if (it is UIMessagePart.Tool) it.copy(output = listOf(UIMessagePart.Text("file contents"))) else it
    })
    private fun wire(messages: List<UIMessage>, history: Boolean = true): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, List::class.java)
        method.isAccessible = true
        return method.invoke(api, messages, history, false, listOf(Modality.TEXT)) as JsonArray
    }
    @Test fun `nonstream tool preamble stays on the tool call assistant message`() {
        val result = wire(listOf(UIMessage.user("inspect"), executed(parsed())))
        assertEquals(listOf("user", "assistant", "tool"), result.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("I'll inspect the file.", result[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("inspect first\nthen use tool", result[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }
    @Test fun `history off cannot strip the current tool chain reasoning`() {
        val result = wire(listOf(UIMessage.user("inspect"), executed(parsed())), history = false)
        val assistant = result.first { it.jsonObject.containsKey("tool_calls") }.jsonObject
        assertEquals("inspect first\nthen use tool", assistant["reasoning_content"]?.jsonPrimitive?.content)
    }
    @Test fun `all reasoning segments before one tool boundary are replayed verbatim`() {
        val message = executed(parsed()).copy(parts = listOf(
            UIMessagePart.Reasoning(reasoning = "first\n"), UIMessagePart.Text("preamble"),
            UIMessagePart.Reasoning(reasoning = "second"), parsed().parts.filterIsInstance<UIMessagePart.Tool>().single()
                .copy(output = listOf(UIMessagePart.Text("done"))),
        ))
        val assistant = wire(listOf(UIMessage.user("inspect"), message))[1].jsonObject
        assertEquals("first\nsecond", assistant["reasoning_content"]?.jsonPrimitive?.content)
    }
    @Test fun `whitespace reasoning is not silently removed`() {
        val message = executed(parsed()).copy(parts = listOf(
            UIMessagePart.Reasoning(reasoning = " \n"),
            parsed().parts.filterIsInstance<UIMessagePart.Tool>().single().copy(output = listOf(UIMessagePart.Text("done"))),
        ))
        assertEquals(" \n", wire(listOf(UIMessage.user("inspect"), message))[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }
    @Test fun `history preference still strips completed unrelated turns`() {
        val old = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(reasoning = "old private reasoning"), UIMessagePart.Text("old answer")))
        val result = wire(listOf(UIMessage.user("old"), old, UIMessage.user("inspect"), executed(parsed())), history = false)
        assertFalse(result[1].jsonObject.containsKey("reasoning_content"))
    }
}
