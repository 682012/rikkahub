package me.rerere.ai.provider.web

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class WebChatExchangeSafetyTest {
    private val nonce = "1234567890abcdef1234567890abcdef"
    private fun exchange(schema: String) = WebChatExchange(listOf(UIMessage.user("test")),
        TextGenerationParams(Model(modelId = "test"), tools = listOf(Tool("echo", "test", parameters = {
            InputSchema.Obj(Json.parseToJsonElement(schema).jsonObject, listOf("x"))
        }, execute = { error("must never execute") }))), true).apply { nonceFactory = { nonce } }
    private fun reply(args: String) = """{"protocol":"rikkahub-tools-v1","nonce":"$nonce","text":"","tool_calls":[{"name":"echo","arguments":$args}]}"""

    @Test fun `boolean schema is usable`() {
        val e = exchange("""{"x":{"type":"boolean"}}""")
        assertEquals(1, e.complete(reply("""{"x":true}""")).filterIsInstance<StreamChunk.ToolCallStart>().size)
    }
    @Test fun `union constraints fail closed for objects`() {
        val e = exchange("""{"x":{"anyOf":[{"type":"string"},{"type":"number"}]}}""")
        assertThrows(WebChatExchangeException::class.java) { e.complete(reply("""{"x":{}}""")) }
        assertEquals(1, e.complete(reply("""{"x":"safe"}""")).filterIsInstance<StreamChunk.ToolCallStart>().size)
    }
    @Test fun `unimplemented reference constraints never silently authorize calls`() {
        val e = exchange("""{"x":{"${'$'}ref":"#/unknown"}}""")
        assertThrows(WebChatExchangeException::class.java) { e.complete(reply("""{"x":"safe"}""")) }
    }
    @Test fun `undeclared root properties rejected`() {
        val e = exchange("""{"x":{"type":"string"}}""")
        assertThrows(WebChatExchangeException::class.java) { e.complete(reply("""{"x":"safe","delete":true}""")) }
    }
    @Test fun `same response maps to same call ids in both output paths`() {
        val e = exchange("""{"x":{"type":"string"}}""");val r = reply("""{"x":"safe"}""")
        val streamed = e.complete(r).filterIsInstance<StreamChunk.ToolCallStart>().single().id
        val result = e.result(r).message.parts.filterIsInstance<UIMessagePart.Tool>().single().toolCallId
        assertEquals(streamed, result)
    }
    @Test fun `plain chat has size and empty limits without interpreting arbitrary JSON as tools`() {
        val e = WebChatExchange(listOf(UIMessage.user("chat")), TextGenerationParams(Model()), false)
        assertThrows(WebChatExchangeException::class.java) { e.complete(" ") }
        assertThrows(WebChatExchangeException::class.java) { e.complete("x".repeat(1024 * 1024 + 1)) }
        assertTrue(e.complete(reply("{}" )).none { it is StreamChunk.ToolCallStart })
    }
}
