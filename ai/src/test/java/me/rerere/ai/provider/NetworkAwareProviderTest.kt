package me.rerere.ai.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.http.NetworkHandover
import org.junit.Assert.*
import org.junit.Test

class NetworkAwareProviderTest {
    private val model = Model(modelId = "route-model", maxConcurrentRequests = 1)
    private val params = TextGenerationParams(model = model)
    private val remote = ProviderSetting.OpenAI(baseUrl = "https://example.com/v1")

    @Test
    fun `route replacement aborts only the old request and releases its model permit`() = runTest {
        val network = NetworkHandover().apply { updateRoute("wifi", true) }
        val limiter = ModelRequestLimiter()
        var cancelled = 0
        val api = FakeProvider(stream = {
            flow {
                try { emit(StreamChunk.TextStart("x")); awaitCancellation() }
                finally { cancelled++ }
            }
        })
        val provider = NetworkAwareProvider(RequestLimitedProvider(api, limiter), network)
        val task = async { runCatching { provider.streamText(remote, emptyList(), params).toList() } }
        runCurrent()
        assertEquals(1, limiter.inFlightCount(model.id))
        network.updateRoute("vpn", true)
        runCurrent()
        assertTrue(task.await().exceptionOrNull() is NetworkRouteChangedException)
        assertEquals(1, cancelled)
        assertEquals(0, limiter.inFlightCount(model.id))
        assertEquals(0, limiter.trackedModelCount())
    }

    @Test
    fun `offline fails fast without a permit instead of silently waiting two minutes`() = runTest {
        val network = NetworkHandover().apply { updateRoute("wifi", true); updateRoute(null, false) }
        val limiter = ModelRequestLimiter()
        var calls = 0
        val provider = NetworkAwareProvider(RequestLimitedProvider(FakeProvider(generate = {
            calls++; result()
        }), limiter), network)

        val error = runCatching { provider.generateText(remote, emptyList(), params) }.exceptionOrNull()

        // 无网时立刻报「没发出去」：界面才不会显示成一直在生成
        assertTrue(error is NetworkUnavailableException)
        assertEquals(0, calls)
        assertEquals(0, limiter.inFlightCount(model.id))
        assertEquals(0, limiter.trackedModelCount())
        assertEquals(250L, currentTime)

        // 网络回来之后才真的发一次，且只发一次
        network.updateRoute("vpn", true)
        advanceUntilIdle()
        assertEquals("route-model", provider.generateText(remote, emptyList(), params).model)
        assertEquals(1, calls)
        assertEquals(0, limiter.trackedModelCount())
    }

    @Test
    fun `normal callback duplicates do not interrupt a live stream`() = runTest {
        val network = NetworkHandover().apply { updateRoute("wifi", true) }
        val done = CompletableDeferred<Unit>()
        var cancelled = false
        val provider = NetworkAwareProvider(FakeProvider(stream = {
            flow { try { done.await(); emit(StreamChunk.Finish("stop")) } finally { cancelled = true } }
        }), network)
        val task = async { provider.streamText(remote, emptyList(), params).toList() }
        runCurrent()
        repeat(3) { network.updateRoute("wifi", true) }
        runCurrent()
        assertFalse(cancelled)
        done.complete(Unit)
        assertEquals(1, task.await().size)
    }

    @Test
    fun `user cancellation stops request and future network events do not restart it`() = runTest {
        val network = NetworkHandover().apply { updateRoute("wifi", true) }
        var calls = 0
        var ended = false
        val provider = NetworkAwareProvider(FakeProvider(generate = {
            calls++
            try { awaitCancellation() } finally { ended = true }
        }), network)
        val job = launch { provider.generateText(remote, emptyList(), params) }
        runCurrent()
        job.cancelAndJoin()
        network.updateRoute("vpn", true)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(ended)
    }

    @Test
    fun `loopback remains usable offline and finished requests leave no watchers`() = runTest {
        val network = NetworkHandover().apply { updateRoute(null, false) }
        var calls = 0
        val provider = NetworkAwareProvider(FakeProvider(generate = { calls++; result() }), network)
        val local = ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:8080/v1")
        assertEquals("route-model", provider.generateText(local, emptyList(), params).model)
        network.updateRoute("wifi", true)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    private fun result() = TextGenerationResult("id", model.modelId, UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))

    private class FakeProvider(
        private val generate: suspend () -> TextGenerationResult = { error("unexpected generate") },
        private val stream: suspend () -> Flow<StreamChunk> = { error("unexpected stream") },
    ) : Provider<ProviderSetting> {
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams) = generate()
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams) = stream()
    }
}
