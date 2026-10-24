package me.rerere.rikkahub.data.ai.subagent

import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.withRetry
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** buildSubagentRequest 只组装请求，永远不真的发请求。 */
private class UnusedProvider : Provider<ProviderSetting> {
    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = error("unused: buildSubagentRequest does not generate")

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = error("unused: buildSubagentRequest does not stream")
}

/**
 * 子代理请求的重试/步数接线（对应 fork.34 的 P1：retry 硬编码 RetryPolicy.NETWORK，
 * 无视"网络 → 自动重试"设置）。
 */
class SubagentRequestWiringTest {
    private fun request(settings: Settings, maxSteps: Int = 40): AgentRequest = buildSubagentRequest(
        provider = UnusedProvider(),
        providerSetting = ProviderSetting.OpenAI(name = "test"),
        prompt = "do the thing",
        tools = listOf(Tool(name = "search_web", description = "") { emptyList() }),
        maxSteps = maxSteps,
        settings = settings,
    )

    private fun settings(enableAutoRetry: Boolean, maxRetries: Int) = Settings(
        networkSetting = NetworkSetting(enableAutoRetry = enableAutoRetry, maxRetries = maxRetries),
    )

    @Test
    fun `subagent uses the retry count from settings instead of a hardcoded 3`() {
        assertEquals(5, request(settings(enableAutoRetry = true, maxRetries = 5)).retry.maxRetries)
    }

    @Test
    fun `disabling auto retry disables subagent retry as well`() {
        assertEquals(0, request(settings(enableAutoRetry = false, maxRetries = 9)).retry.maxRetries)
    }

    @Test
    fun `max retries zero disables subagent retry`() {
        assertEquals(0, request(settings(enableAutoRetry = true, maxRetries = 0)).retry.maxRetries)
    }

    @Test
    fun `retry count is clamped to the same 100 ceiling as the main chain`() {
        assertEquals(100, request(settings(enableAutoRetry = true, maxRetries = 500)).retry.maxRetries)
    }

    @Test
    fun `request keeps a single user message and no streaming`() {
        val request = request(settings(enableAutoRetry = true, maxRetries = 3))
        assertFalse(request.stream)
        assertEquals(1, request.messages.size)
        assertEquals("do the thing", request.messages.single().toText())
        assertEquals(listOf("search_web"), request.tools.map { it.name })
    }

    @Test
    fun `max steps stays inside the hard ceiling`() {
        val s = settings(enableAutoRetry = true, maxRetries = 3)
        assertEquals(1, request(s, maxSteps = 0).maxSteps)
        assertEquals(1, request(s, maxSteps = -5).maxSteps)
        assertEquals(40, request(s, maxSteps = 40).maxSteps)
        assertEquals(120, request(s, maxSteps = 999).maxSteps)
    }

    @Test
    fun `network failure is not retried when the user turned auto retry off`() {
        val policy = request(settings(enableAutoRetry = false, maxRetries = 3)).retry
        var attempts = 0
        runBlocking {
            runCatching { withRetry(policy) { attempts++; throw IOException("connection reset") } }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `network failure is retried the configured number of times`() {
        val policy = request(settings(enableAutoRetry = true, maxRetries = 2)).retry
            .copy(initialDelay = Duration.ZERO, maxDelay = Duration.ZERO)
        var attempts = 0
        runBlocking {
            runCatching { withRetry(policy) { attempts++; throw IOException("connection reset") } }
        }
        assertEquals(3, attempts)
    }

    @Test
    fun `cancellation is never retried even with retries enabled`() {
        val policy = request(settings(enableAutoRetry = true, maxRetries = 3)).retry
        var attempts = 0
        var retryNotified = 0
        runBlocking {
            val error = runCatching {
                withRetry(policy, onRetry = { retryNotified++ }) {
                    attempts++
                    throw CancellationException("user cancelled the run")
                }
            }.exceptionOrNull()
            assertTrue("取消必须原样抛出，交回给 SubagentRunner 落 CANCELLED", error is CancellationException)
        }
        assertEquals(1, attempts)
        assertEquals(0, retryNotified)
    }
}
