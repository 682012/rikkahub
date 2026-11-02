package me.rerere.ai.provider

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolCallPolicyTest {
    @Test fun `normal calls preserve their bodies`() {
        val body = buildJsonObject { put("model", "m"); put("tools", JsonArray(emptyList())) }
        assertEquals(body, body.applyToolCallPolicy(TextGenerationParams(Model())))
    }
    @Test fun `summary preserves schemas and overrides any prior tool choice`() {
        val tools = Json.parseToJsonElement("""[{"type":"function","name":"inspect"}]""")
        val body = buildJsonObject { put("tools", tools); put("tool_choice", "required"); put("prompt_cache_key", "chat-1") }
        val summary = body.applyToolCallPolicy(TextGenerationParams(Model(), disableToolCalls = true))
        assertEquals(tools, summary["tools"])
        assertEquals(JsonPrimitive("none"), summary["tool_choice"])
        assertEquals(body["prompt_cache_key"], summary["prompt_cache_key"])
    }
    @Test fun `no schema means no unsupported extra option`() {
        val body = buildJsonObject { put("model", "m") }
        assertEquals(body, body.applyToolCallPolicy(TextGenerationParams(Model(), disableToolCalls = true)))
    }
    @Test fun `web accounts do not pretend to have a native tool choice`() {
        assertFalse(ProviderSetting.GeminiWeb().canDisableToolCallsWithDefinitions)
        assertFalse(ProviderSetting.ClaudeWeb().canDisableToolCallsWithDefinitions)
        assertTrue(ProviderSetting.Claude().canDisableToolCallsWithDefinitions)
        assertTrue(ProviderSetting.OpenAI().canDisableToolCallsWithDefinitions)
    }
}
