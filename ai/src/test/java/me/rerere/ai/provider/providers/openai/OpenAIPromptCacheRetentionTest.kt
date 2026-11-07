package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.PromptCacheRetention
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 缓存保留档位的 wire 契约：
 * 默认一个字节都不发；24h 走旧字段；30m/12h 走 prompt_cache_options.ttl；
 * Chat Completions 强配 12h 必须明确报错，不能静默缩短。
 */
class OpenAIPromptCacheRetentionTest {
    private val api = ChatCompletionsAPI(
        okhttp3.OkHttpClient(),
        me.rerere.ai.util.KeyRoulette.default(),
    )

    private fun build(params: TextGenerationParams): JsonObject {
        val method = api::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return method.invoke(
            api,
            listOf(me.rerere.ai.ui.UIMessage.user("hi")),
            params,
            ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            false,
        ) as JsonObject
    }

    private fun model(retention: PromptCacheRetention) = Model(
        modelId = "gpt-test",
        promptCacheRetention = retention,
    )

    @Test
    fun `default sends no cache retention field`() {
        val body = build(TextGenerationParams(model = model(PromptCacheRetention.DEFAULT)))
        assertNull(body["prompt_cache_retention"])
        assertNull(body["prompt_cache_options"])
    }

    @Test
    fun `legacy opt-in sends 24h`() {
        val body = build(TextGenerationParams(model = model(PromptCacheRetention.ONE_DAY)))
        assertEquals("24h", body["prompt_cache_retention"]!!.jsonPrimitive.content)
        assertNull(body["prompt_cache_options"])
    }

    @Test
    fun `chat completions sends 30m via prompt_cache_options`() {
        val body = build(TextGenerationParams(model = model(PromptCacheRetention.THIRTY_MINUTES)))
        assertNull(body["prompt_cache_retention"])
        assertEquals(
            """{"ttl":"30m"}""",
            body["prompt_cache_options"]!!.jsonObject.toString(),
        )
    }

    @Test
    fun `chat completions rejects 12h explicitly`() {
        try {
            build(TextGenerationParams(model = model(PromptCacheRetention.TWELVE_HOURS)))
            fail("Chat Completions 不应接受 12h")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("12h"))
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException
            assertTrue(cause is IllegalArgumentException)
            assertTrue(cause.message!!.contains("12h"))
        }
    }
}

/**
 * 纯函数层：两个 API 共用的路由与能力校验，不依赖网络与反射。
 */
class PromptCacheRetentionWireTest {
    private fun fields(
        retention: PromptCacheRetention,
        capabilities: ModelCacheRetentionCapabilities,
    ): JsonObject = promptCacheRetentionFields(retention, capabilities)

    @Test
    fun `default produces an empty object for every capability set`() {
        listOf(
            ModelCacheRetentionCapabilities.CHAT_COMPLETIONS,
            ModelCacheRetentionCapabilities.RESPONSES,
            ModelCacheRetentionCapabilities(),
        ).forEach { caps ->
            assertEquals("{}", fields(PromptCacheRetention.DEFAULT, caps).toString())
        }
    }

    @Test
    fun `responses 30m uses options ttl`() {
        assertEquals(
            """{"prompt_cache_options":{"ttl":"30m"}}""",
            fields(PromptCacheRetention.THIRTY_MINUTES, ModelCacheRetentionCapabilities.RESPONSES)
                .toString(),
        )
    }

    @Test
    fun `responses 12h uses options ttl`() {
        assertEquals(
            """{"prompt_cache_options":{"ttl":"12h"}}""",
            fields(PromptCacheRetention.TWELVE_HOURS, ModelCacheRetentionCapabilities.RESPONSES)
                .toString(),
        )
    }

    @Test
    fun `legacy 24h still uses prompt_cache_retention`() {
        assertEquals(
            """{"prompt_cache_retention":"24h"}""",
            fields(PromptCacheRetention.ONE_DAY, ModelCacheRetentionCapabilities.RESPONSES)
                .toString(),
        )
    }

    @Test
    fun `chat completions rejects 12h instead of silently shortening`() {
        assertEquals(
            """{"prompt_cache_options":{"ttl":"30m"}}""",
            fields(PromptCacheRetention.THIRTY_MINUTES, ModelCacheRetentionCapabilities.CHAT_COMPLETIONS)
                .toString(),
        )
        try {
            fields(PromptCacheRetention.TWELVE_HOURS, ModelCacheRetentionCapabilities.CHAT_COMPLETIONS)
            fail("12h 必须被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("12h"))
        }
    }

    @Test
    fun `selectable only offers what the api accepts`() {
        assertEquals(
            listOf(
                PromptCacheRetention.DEFAULT,
                PromptCacheRetention.THIRTY_MINUTES,
                PromptCacheRetention.ONE_DAY,
            ),
            ModelCacheRetentionCapabilities.CHAT_COMPLETIONS.selectable(),
        )
        assertEquals(
            PromptCacheRetention.entries,
            ModelCacheRetentionCapabilities.RESPONSES.selectable(),
        )
        assertEquals(
            listOf(PromptCacheRetention.DEFAULT),
            ModelCacheRetentionCapabilities().selectable(),
        )
        assertTrue(!ModelCacheRetentionCapabilities().supportsAny)
    }

    @Test
    fun `model default keeps old configs compatible`() {
        val model = Json.decodeFromString(
            Model.serializer(),
            """{"id":"x","modelId":"gpt-test","displayName":"t"}""",
        )
        assertEquals(PromptCacheRetention.DEFAULT, model.promptCacheRetention)
        assertEquals(
            PromptCacheRetention.TWELVE_HOURS,
            Json.decodeFromString(
                Model.serializer(),
                """{"id":"x","modelId":"gpt-test","displayName":"t","promptCacheRetention":"12h"}""",
            ).promptCacheRetention,
        )
    }
}
