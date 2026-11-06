package me.rerere.rikkahub.data.ai.clientprofile

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.model.ConversationClientProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientProfileResolverTest {

    private val gpt = Model(modelId = "gpt-5-codex", displayName = "GPT-5 Codex")
    private val thirdParty = Model(modelId = "deepseek-v3", displayName = "DeepSeek V3")

    private fun openAi(useResponses: Boolean) = ProviderSetting.OpenAI(
        name = "OpenAI",
        apiKey = "sk-user-key",
        useResponseApi = useResponses,
    )

    private fun params(model: Model = gpt) = TextGenerationParams(
        model = model,
        temperature = 0.7f,
        maxTokens = 2048,
        sessionId = "session-1",
        tools = listOf(Tool(name = "read_file", description = "read", execute = { emptyList() })),
    )

    @Test
    fun nativeProfileChangesNothingAtAll() {
        val input = params()
        val profiled = applyProfile(input, openAi(useResponses = true), ConversationClientProfile.NATIVE)

        assertEquals(ConversationClientProfile.NATIVE, profiled.effectiveProfile)
        assertSame(input, profiled.params)
        assertEquals("", profiled.guidance)
        assertNull(profiled.providerSetting)
        assertTrue(profiled.addedHeaders.isEmpty())
        assertTrue(profiled.addedBodyKeys.isEmpty())
    }

    @Test
    fun codexOnResponsesAddsPublicHeadersAndResponsesParams() {
        val profiled = applyProfile(params(), openAi(useResponses = true), ConversationClientProfile.CODEX)

        assertEquals(ConversationClientProfile.CODEX, profiled.effectiveProfile)
        assertEquals(ProfileSupport.SUPPORTED, profiled.support)
        assertEquals(ProfileWireDialect.RESPONSES, profiled.dialect)

        val headers = profiled.params.customHeaders.associate { it.name.lowercase() to it.value }
        assertEquals("codex_cli_rs", headers["originator"])
        assertTrue("authorization" !in headers)
        assertTrue("x-api-key" !in headers)

        val bodies = profiled.params.customBody.associate { it.key to it.value }
        assertEquals(JsonPrimitive(false), bodies["store"])
        assertEquals(JsonPrimitive("session-1"), bodies["prompt_cache_key"])
        assertTrue(profiled.guidance.isNotBlank())
    }

    @Test
    fun codexOnChatCompletionsKeepsProfileButSkipsResponsesOnlyParams() {
        val profiled = applyProfile(params(), openAi(useResponses = false), ConversationClientProfile.CODEX)

        assertEquals(ProfileSupport.PARTIAL, profiled.support)
        assertTrue(profiled.addedBodyKeys.isEmpty())
        assertTrue(profiled.params.customBody.isEmpty())
        assertNotNull(profiled.suggestedApiChange)
        assertTrue(profiled.suggestedApiChange!!.contains("Responses"))
        assertTrue(profiled.guidance.isNotBlank())
        assertTrue(profiled.addedHeaders.none { it.name.equals("authorization", ignoreCase = true) })
    }

    @Test
    fun manualClaudeCodeOnOpenAiFallsBackToNativeWithExplanation() {
        val input = params()
        val profiled = applyProfile(input, openAi(useResponses = true), ConversationClientProfile.CLAUDE_CODE)

        assertEquals(ConversationClientProfile.NATIVE, profiled.effectiveProfile)
        assertEquals(ProfileSupport.UNSUPPORTED, profiled.support)
        assertTrue(profiled.fellBack)
        assertSame(input, profiled.params)
        assertEquals("", profiled.guidance)
        assertTrue(profiled.reason.contains("Anthropic"))
        assertTrue(profiled.reason.contains("原生"))
        assertFalse(profiled.reason.contains("x-app"))
    }

    @Test
    fun claudeProviderSupportsClaudeCodeAndPiOnly() {
        val claude = ProviderSetting.Claude(apiKey = "sk-ant")

        assertEquals(ProfileSupport.SUPPORTED, ClientProfileResolver.support(claude, ConversationClientProfile.CLAUDE_CODE))
        assertEquals(ProfileSupport.SUPPORTED, ClientProfileResolver.support(claude, ConversationClientProfile.PI))
        assertEquals(ProfileSupport.UNSUPPORTED, ClientProfileResolver.support(claude, ConversationClientProfile.CODEX))

        val profiled = applyProfile(params(), claude, ConversationClientProfile.CLAUDE_CODE)
        val headers = profiled.params.customHeaders.associate { it.name.lowercase() to it.value }
        assertEquals("cli", headers["x-app"])
        assertEquals("claude-cli", headers["user-agent"])
        assertTrue(headers["user-agent"]!!.none { it.isDigit() })
        assertTrue("anthropic-version" !in headers)
    }

    @Test
    fun autoPicksCodexForOpenAiFrontierOnResponses() {
        val decision = resolveClientProfile(openAi(useResponses = true), gpt, ConversationClientProfile.AUTO)
        assertEquals(ConversationClientProfile.CODEX, decision.effective)
        assertTrue(decision.auto)
        assertTrue(decision.reason.isNotBlank())

        val thirdPartyDecision = resolveClientProfile(openAi(useResponses = true), thirdParty, ConversationClientProfile.AUTO)
        assertEquals(ConversationClientProfile.PI, thirdPartyDecision.effective)
    }

    @Test
    fun autoPicksPiOnChatCompletionsAndClaudeCodeOnClaudeModels() {
        assertEquals(
            ConversationClientProfile.PI,
            resolveClientProfile(openAi(useResponses = false), gpt, ConversationClientProfile.AUTO).effective,
        )
        assertEquals(
            ConversationClientProfile.CLAUDE_CODE,
            resolveClientProfile(
                ProviderSetting.Claude(apiKey = "k"),
                Model(modelId = "claude-sonnet-4-5", displayName = "Sonnet"),
                ConversationClientProfile.AUTO,
            ).effective,
        )
        assertEquals(
            ConversationClientProfile.CODEX,
            resolveClientProfile(
                ProviderSetting.ChatGPT(credential = OAuthCredential(accessToken = "t")),
                gpt,
                ConversationClientProfile.AUTO,
            ).effective,
        )
    }

    @Test
    fun autoFallsBackToNativeForGoogleAndIsStableAcrossCalls() {
        val google = ProviderSetting.Google(apiKey = "g")
        val first = resolveClientProfile(google, gpt, ConversationClientProfile.AUTO)
        assertEquals(ConversationClientProfile.NATIVE, first.effective)
        assertEquals(ProfileSupport.UNSUPPORTED, ClientProfileResolver.support(google, ConversationClientProfile.CODEX))

        val second = resolveClientProfile(google, gpt, ConversationClientProfile.AUTO)
        assertEquals(first, second)
        val openAiFirst = resolveClientProfile(openAi(useResponses = true), gpt, ConversationClientProfile.AUTO)
        assertEquals(openAiFirst, resolveClientProfile(openAi(useResponses = true), gpt, ConversationClientProfile.AUTO))
        assertEquals(
            openAiFirst.effective,
            resolveClientProfile(openAi(useResponses = true), gpt, ConversationClientProfile.CODEX).effective,
        )
    }

    @Test
    fun profileNeverTouchesToolsSamplingOrSession() {
        val input = params()
        for (profile in ConversationClientProfile.entries) {
            val profiled = applyProfile(input, openAi(useResponses = true), profile)
            assertEquals(input.tools, profiled.params.tools)
            assertEquals(input.temperature, profiled.params.temperature)
            assertEquals(input.maxTokens, profiled.params.maxTokens)
            assertEquals(input.sessionId, profiled.params.sessionId)
            assertEquals(input.model, profiled.params.model)
            assertNull(profiled.providerSetting)
        }
    }

    @Test
    fun userCustomBodyWinsOverProfileBody() {
        val input = params().copy(customBody = listOf(CustomBody("store", JsonPrimitive(true))))
        val profiled = applyProfile(input, openAi(useResponses = true), ConversationClientProfile.CODEX)

        assertEquals(JsonPrimitive(true), profiled.params.customBody.first { it.key == "store" }.value)
        assertEquals(1, profiled.params.customBody.count { it.key == "store" })
        assertTrue(profiled.addedBodyKeys.none { it == "store" })
    }

    @Test
    fun matrixListsEveryProfileWithHonestLimitations() {
        val matrix = clientProfileMatrix()
        assertEquals(ConversationClientProfile.entries.toSet(), matrix.map { it.profile }.toSet())
        assertTrue(matrix.all { it.limitation.isNotBlank() })
        assertTrue(matrix.none { it.description.contains("任何客户端") })
    }
}
