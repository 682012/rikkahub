package me.rerere.ai.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class RequestLimitedProviderTest {
    private val setting = ProviderSetting.OpenAI()

    private fun model(limit: Int?, id: Uuid = Uuid.random()) = Model(
        modelId = "chat",
        displayName = "Chat",
        id = id,
        maxConcurrentRequests = limit,
    )

    private fun textParams(model: Model) = TextGenerationParams(model = model)

    @Test
    fun `all five request types go through the model cap`() = runTest {
        val limiter = ModelRequestLimiter()
        val delegate = RecordingProvider()
        val provider = RequestLimitedProvider(delegate, limiter)
        val target = model(1)

        provider.generateText(setting, emptyList(), textParams(target))
        provider.streamText(setting, emptyList(), textParams(target)).collect { }
        provider.generateEmbedding(setting, EmbeddingGenerationParams(model = target, input = listOf("a")))
        provider.generateImage(setting, ImageGenerationParams(model = target, prompt = "p")).collect { }
        provider.editImage(
            setting,
            ImageEditParams(model = target, prompt = "p", images = listOf("i")),
        ).collect { }

        assertEquals(listOf("text", "stream", "embedding", "image", "editImage"), delegate.modes)
        assertEquals(5, delegate.calls)
        assertEquals(1, delegate.peak)
        assertEquals(0, limiter.inFlightCount(target.id))
        assertEquals(0, limiter.trackedModelCount())
    }

    @Test
    fun `stream flow is cold and acquires a slot per collection`() = runTest {
        val limiter = ModelRequestLimiter()
        val delegate = RecordingProvider()
        val provider = RequestLimitedProvider(delegate, limiter)
        val target = model(1)

        val stream = provider.streamText(setting, emptyList(), textParams(target))
        // 不收集就不占名额，也不会打到上游
        assertEquals(0, delegate.calls)
        assertEquals(0, limiter.inFlightCount(target.id))

        stream.collect { }
        assertEquals(1, delegate.calls)
        assertEquals(0, limiter.inFlightCount(target.id))

        // 重复收集各占一次名额，不会复用上一次的
        stream.collect { }
        assertEquals(2, delegate.calls)
        assertEquals(1, delegate.peak)
        assertEquals(0, limiter.inFlightCount(target.id))
        assertEquals(0, delegate.inFlight)
    }

    @Test
    fun `two collections of the same stream share the model cap`() = runTest {
        val limiter = ModelRequestLimiter()
        val gate = CompletableDeferred<Unit>()
        val delegate = RecordingProvider { mode -> if (mode == "stream") gate.await() }
        val provider = RequestLimitedProvider(delegate, limiter)
        val target = model(1)
        val stream = provider.streamText(setting, emptyList(), textParams(target))

        val first = launch { stream.collect { } }
        val second = launch { stream.collect { } }
        advanceUntilIdle()

        assertEquals(1, delegate.inFlight)
        assertEquals(1, limiter.inFlightCount(target.id))
        assertEquals(1, limiter.waitingCount(target.id))

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, delegate.calls)
        assertEquals(1, delegate.peak)
        assertEquals(0, limiter.inFlightCount(target.id))
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
    }

    @Test
    fun `cancelling the collector releases the slot`() = runTest {
        val limiter = ModelRequestLimiter()
        val gate = CompletableDeferred<Unit>()
        val delegate = RecordingProvider { mode -> if (mode == "stream") gate.await() }
        val provider = RequestLimitedProvider(delegate, limiter)
        val target = model(1)

        val collector = launch {
            provider.streamText(setting, emptyList(), textParams(target)).collect { }
        }
        advanceUntilIdle()
        assertEquals(1, limiter.inFlightCount(target.id))

        collector.cancel()
        advanceUntilIdle()
        assertEquals(0, limiter.inFlightCount(target.id))
        assertEquals(0, limiter.trackedModelCount())
        assertEquals(0, delegate.inFlight)
    }

    @Test
    fun `stream setup exception releases the slot and a retry can run`() = runTest {
        val limiter = ModelRequestLimiter()
        val delegate = RecordingProvider()
        delegate.streamSetupError = IllegalStateException("setup failed")
        val provider = RequestLimitedProvider(delegate, limiter)
        val target = model(1)

        val failure = runCatching {
            provider.streamText(setting, emptyList(), textParams(target)).collect { }
        }
        assertTrue(failure.isFailure)
        assertEquals(0, limiter.inFlightCount(target.id))
        assertEquals(0, limiter.trackedModelCount())

        delegate.streamSetupError = null
        provider.streamText(setting, emptyList(), textParams(target)).collect { }
        assertEquals(1, delegate.modes.count { it == "stream" })
        assertEquals(0, limiter.inFlightCount(target.id))
    }

    @Test
    fun `stream failure during collection releases the slot`() = runTest {
        val limiter = ModelRequestLimiter()
        val delegate = RecordingProvider()
        delegate.streamBodyError = IllegalStateException("stream failed")
        val provider = RequestLimitedProvider(delegate, limiter)
        val target = model(1)

        val failure = runCatching {
            provider.streamText(setting, emptyList(), textParams(target)).collect { }
        }
        assertTrue(failure.isFailure)
        assertEquals(0, limiter.inFlightCount(target.id))
        assertEquals(0, delegate.inFlight)
    }

    @Test
    fun `failed text request releases the slot so a retry is not blocked`() = runTest {
        val limiter = ModelRequestLimiter()
        val delegate = RecordingProvider()
        delegate.textError = IllegalStateException("boom")
        val provider = RequestLimitedProvider(delegate, limiter)
        val target = model(1)

        assertTrue(runCatching { provider.generateText(setting, emptyList(), textParams(target)) }.isFailure)
        assertEquals(0, limiter.inFlightCount(target.id))

        delegate.textError = null
        val result = provider.generateText(setting, emptyList(), textParams(target))
        assertEquals(target.modelId, result.model)
        assertEquals(2, delegate.calls)
        assertEquals(0, limiter.inFlightCount(target.id))
    }

    @Test
    fun `tool execution between two requests never holds a slot`() = runTest {
        val limiter = ModelRequestLimiter()
        val delegate = RecordingProvider()
        val provider = RequestLimitedProvider(delegate, limiter)
        val target = model(1)
        val toolGate = CompletableDeferred<Unit>()
        var toolStartInFlight = 0
        var otherAgentDone = false

        // 同一个模型、额度 1：agent A 拿到回复后执行工具，工具执行期间不占名额
        val agentA = launch {
            provider.generateText(setting, emptyList(), textParams(target))
            toolStartInFlight = limiter.inFlightCount(target.id)
            toolGate.await()
        }
        advanceUntilIdle()
        assertEquals(0, toolStartInFlight)

        val agentB = launch {
            provider.generateText(setting, emptyList(), textParams(target))
            otherAgentDone = true
        }
        advanceUntilIdle()

        // 工具还挂起着，另一个请求已经跑完了，没有死锁
        assertTrue(otherAgentDone)
        assertTrue(agentA.isActive)
        assertTrue(!toolGate.isCompleted)

        toolGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, limiter.inFlightCount(target.id))
        assertTrue(agentA.isCompleted)
        assertTrue(agentB.isCompleted)
    }

    @Test
    fun `providers sharing one limiter share the per model cap`() = runTest {
        // ProviderManager 只造一个 limiter，多个 provider 实例共用同一份名额
        val limiter = ModelRequestLimiter()
        val gate = CompletableDeferred<Unit>()
        val first = RequestLimitedProvider(RecordingProvider { gate.await() }, limiter)
        val second = RequestLimitedProvider(RecordingProvider { gate.await() }, limiter)
        val target = model(1)

        val running = launch { first.generateText(setting, emptyList(), textParams(target)) }
        advanceUntilIdle()
        val queued = launch { second.generateText(setting, emptyList(), textParams(target)) }
        advanceUntilIdle()

        assertEquals(1, limiter.inFlightCount(target.id))
        assertEquals(1, limiter.waitingCount(target.id))

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, limiter.inFlightCount(target.id))
        assertTrue(running.isCompleted)
        assertTrue(queued.isCompleted)
    }

    @Test
    fun `limits resolve against the latest snapshot fed by settings`() = runTest {
        val id = Uuid.random()
        val snapshot = model(1, id)
        val limits = LiveModelLimits()
        val limiter = ModelRequestLimiter(limits::limitFor)
        val gate = CompletableDeferred<Unit>()
        val provider = RequestLimitedProvider(RecordingProvider { gate.await() }, limiter)

        limits.update(listOf(model(3, id)))
        val jobs = (1..3).map {
            launch { provider.generateText(setting, emptyList(), textParams(snapshot)) }
        }
        advanceUntilIdle()
        assertEquals(3, limiter.inFlightCount(id))

        // 运行中把上限收到 1，新请求进不来，已在跑的继续
        limits.update(listOf(model(1, id)))
        val late = launch { provider.generateText(setting, emptyList(), textParams(snapshot)) }
        advanceUntilIdle()
        assertEquals(3, limiter.inFlightCount(id))
        assertTrue(late.isActive)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, limiter.inFlightCount(id))
        jobs.forEach { assertTrue(it.isCompleted) }
        assertTrue(late.isCompleted)
    }

    private class RecordingProvider(
        private val hold: suspend (String) -> Unit = {},
    ) : Provider<ProviderSetting> {
        var calls = 0
        var inFlight = 0
        var peak = 0
        val modes = mutableListOf<String>()
        var textError: Throwable? = null
        var streamSetupError: Throwable? = null
        var streamBodyError: Throwable? = null

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

        private suspend fun <R> tracked(mode: String, block: suspend () -> R): R {
            calls++
            modes += mode
            inFlight++
            peak = maxOf(peak, inFlight)
            try {
                hold(mode)
                return block()
            } finally {
                inFlight--
            }
        }

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = tracked("text") {
            textError?.let { throw it }
            TextGenerationResult(
                id = "result",
                model = params.model.modelId,
                message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
            )
        }

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            streamSetupError?.let { throw it }
            return flow {
                tracked("stream") {
                    emit(StreamChunk.TextStart(id = "1"))
                    streamBodyError?.let { throw it }
                    emit(StreamChunk.TextEnd(id = "1"))
                }
            }
        }

        override suspend fun generateEmbedding(
            providerSetting: ProviderSetting,
            params: EmbeddingGenerationParams,
        ): EmbeddingGenerationResult = tracked("embedding") {
            EmbeddingGenerationResult(model = params.model.modelId, embeddings = params.input.map { listOf(1f) })
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting,
            params: ImageGenerationParams,
        ): Flow<ImageGenerationItem> = flow {
            tracked("image") {
                emit(ImageGenerationItem(data = "a", mimeType = "image/png"))
            }
        }

        override suspend fun editImage(
            providerSetting: ProviderSetting,
            params: ImageEditParams,
        ): Flow<ImageGenerationItem> = flow {
            tracked("editImage") {
                emit(ImageGenerationItem(data = "a", mimeType = "image/png"))
            }
        }
    }
}
