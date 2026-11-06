package me.rerere.rikkahub.data.ai.clientprofile

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.rikkahub.data.model.ConversationClientProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientProfileHeadersTest {

    private fun openAi() = ProviderSetting.OpenAI(apiKey = "sk-user-key", useResponseApi = true)

    private fun params(headers: List<CustomHeader> = emptyList()) = TextGenerationParams(
        model = Model(modelId = "gpt-5-codex", displayName = "Codex"),
        customHeaders = headers,
        sessionId = "session-1",
    )

    @Test
    fun userHeaderWinsCaseInsensitively() {
        val user = CustomHeader("Originator", "my-gateway")
        val profiled = applyProfile(params(listOf(user)), openAi(), ConversationClientProfile.CODEX)

        // 档内同名头整条跳过，不产生重复项
        assertTrue(profiled.addedHeaders.isEmpty())
        val originators = profiled.params.customHeaders.filter { it.name.equals("originator", ignoreCase = true) }
        assertEquals(1, originators.size)
        assertEquals("my-gateway", originators.single().value)
        assertTrue(profiled.droppedHeaders.isEmpty())
    }

    @Test
    fun authorizationHeaderIsNeverRewrittenOrAdded() {
        val user = CustomHeader("Authorization", "Bearer user-own-key")
        val profiled = applyProfile(params(listOf(user)), openAi(), ConversationClientProfile.CODEX)

        assertEquals("Bearer user-own-key", profiled.params.customHeaders.filter { it.name == "Authorization" }.single().value)
        assertEquals(1, profiled.params.customHeaders.count { it.name.equals("authorization", ignoreCase = true) })
        assertTrue(profiled.addedHeaders.none { it.name.equals("authorization", ignoreCase = true) })
        // 档内请求头池里根本没有凭证类字段
        for (profile in ConversationClientProfile.entries) {
            for (dialect in ProfileWireDialect.entries) {
                assertTrue(
                    profileHeadersFor(profile, dialect).none {
                        it.name.lowercase() in PROFILE_FORBIDDEN_HEADER_NAMES
                    },
                )
            }
        }
    }

    @Test
    fun headerInjectionWithCrLfIsRejected() {
        val merge = sanitizeProfileHeaders(
            userHeaders = emptyList(),
            profileHeaders = listOf(
                CustomHeader("x-test", "ok"),
                CustomHeader("x-evil", "bad\r\nInjected: 1"),
                CustomHeader("x-evil-2", "bad\nInjected: 1"),
                CustomHeader("  ", "nameless"),
            ),
        )

        assertEquals(listOf("x-test"), merge.merged.map { it.name })
        assertEquals(listOf("x-test"), merge.added.map { it.name })
        assertTrue(merge.dropped.contains("x-evil"))
        assertTrue(merge.dropped.contains("x-evil-2"))
        assertTrue(merge.dropped.contains("  "))
        assertTrue(merge.merged.none { it.value.contains("\r") || it.value.contains("\n") })
    }

    @Test
    fun providerOwnedProtocolHeadersAreNotOverridden() {
        val merge = sanitizeProfileHeaders(
            userHeaders = emptyList(),
            profileHeaders = listOf(
                CustomHeader("anthropic-version", "2023-06-01"),
                CustomHeader("x-api-key", "stolen"),
                CustomHeader("Content-Type", "application/json"),
            ),
        )
        assertTrue(merge.merged.isEmpty())
        assertEquals(3, merge.dropped.size)
    }

    @Test
    fun duplicateProfileHeadersKeepOnlyFirst() {
        val merge = sanitizeProfileHeaders(
            userHeaders = emptyList(),
            profileHeaders = listOf(
                CustomHeader("originator", "codex_cli_rs"),
                CustomHeader("Originator", "duplicate"),
            ),
        )
        assertEquals(1, merge.added.size)
        assertEquals("codex_cli_rs", merge.added.single().value)
    }

    @Test
    fun piAddsNoHeadersOnlyGuidance() {
        val profiled = applyProfile(params(), openAi(), ConversationClientProfile.PI)

        assertEquals(ConversationClientProfile.PI, profiled.effectiveProfile)
        assertTrue(profiled.addedHeaders.isEmpty())
        assertTrue(profiled.addedBodyKeys.isEmpty())
        assertTrue(profiled.guidance.isNotBlank())
        assertTrue(profiled.guidance.startsWith("Tool-use style"))
    }

    @Test
    fun applyToIsIdempotent() {
        val profiled = applyProfile(params(), openAi(), ConversationClientProfile.CODEX)
        val first = profiled.applyTo(params())
        val second = profiled.applyTo(first)

        assertEquals(first.customHeaders, second.customHeaders)
        assertEquals(first.customBody, second.customBody)
        assertEquals(1, second.customHeaders.count { it.name.equals("originator", ignoreCase = true) })
        assertEquals(1, second.customBody.count { it.key == "store" })
    }

    @Test
    fun guidanceNeverClaimsIdentityOrDropsPermissions() {
        for (profile in listOf(
            ConversationClientProfile.CODEX,
            ConversationClientProfile.CLAUDE_CODE,
            ConversationClientProfile.PI,
        )) {
            val text = guidanceFor(profile)
            assertTrue(text.startsWith("Tool-use style for this turn"))
            assertTrue(text.none { it == '你' })
            assertTrue("You are" !in text && "ignore previous" !in text.lowercase())
            assertTrue(text.length < 700)
        }
        assertEquals("", guidanceFor(ConversationClientProfile.NATIVE))
        assertEquals("", guidanceFor(ConversationClientProfile.AUTO))
    }
}
