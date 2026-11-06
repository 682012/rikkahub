package me.rerere.rikkahub.data.ai.channel

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.stream.StreamTruncatedException
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.rikkahub.data.datastore.ChannelFailoverSettings
import me.rerere.rikkahub.data.datastore.ModelChannelRoute
import me.rerere.rikkahub.data.datastore.ModelChannelSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同模型多渠道优先级：严格白名单排序、同模型校验、同协议族、空响应切换、有界切换、取消传播、
 * 半截输出不重来。
 *
 * 全部是纯逻辑 + 假 provider，不发任何真实请求。
 */
class ChannelFailoverTest {
    private val providerA = ProviderSetting.OpenAI(name = "A")
    private val providerB = ProviderSetting.OpenAI(name = "B")
    private val providerC = ProviderSetting.Claude(name = "C")
    private val modelA = Model(modelId = "gpt-4o", displayName = "A-4o")
    private val modelB = Model(modelId = "gpt-4o", displayName = "B-4o")
    private val modelC = Model(modelId = "gpt-4o", displayName = "C-4o")

    private val providers = listOf(
        providerA.copy(models = listOf(modelA)),
        providerB.copy(models = listOf(modelB)),
        providerC.copy(models = listOf(modelC)),
    )

    private fun routeOf(vararg providerIds: String) = ChannelFailoverSettings(
        enabled = true,
        routes = listOf(
            ModelChannelRoute(modelId = "gpt-4o", channels = providerIds.map { ModelChannelSlot(it, "gpt-4o") }),
        ),
    )

    // ---- 渠道解析 ----

    @Test
    fun `disabled or unconfigured models keep the single channel path`() {
        assertTrue(resolveChannelPlan(providers, modelA, ChannelFailoverSettings()).isEmpty())
        assertTrue(
            resolveChannelPlan(
                providers = providers,
                model = modelA,
                failover = routeOf(providerA.id.toString(), providerB.id.toString()).copy(enabled = false),
            ).isEmpty(),
        )
        // 只有一个渠道仍严格使用显式白名单，不借用其它渠道。
        assertEquals(
            listOf(modelA.id),
            resolveChannelPlan(providers, modelA, routeOf(providerA.id.toString())).map { it.model.id },
        )
        // 启用但当前模型没有配置组 -> 不去借别的模型的组
        assertTrue(
            resolveChannelPlan(
                providers = providers,
                model = modelA,
                failover = ChannelFailoverSettings(
                    enabled = true,
                    routes = listOf(
                        ModelChannelRoute("claude-3", listOf(ModelChannelSlot(providerC.id.toString(), "gpt-4o"))),
                    ),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `whitelist order wins and the conversation's current channel never jumps the queue`() {
        // 会话选的是 B，用户排的顺序是 A -> B：必须按用户顺序，A 先试
        val plan = resolveChannelPlan(providers, modelB, routeOf(providerA.id.toString(), providerB.id.toString()))
        assertEquals(listOf(providerA.id.toString(), providerB.id.toString()), plan.map { it.providerId })
        assertEquals(listOf(modelA.id, modelB.id), plan.map { it.model.id })
    }

    @Test
    fun `a disabled current channel does not throw away the whitelisted fallback`() {
        val aDisabled = listOf(
            providerA.copy(enabled = false, models = listOf(modelA)),
            providerB.copy(models = listOf(modelB)),
        )
        val plan = resolveChannelPlan(
            aDisabled,
            modelA,
            routeOf(providerA.id.toString(), providerB.id.toString()),
        )
        // A 被禁用，但 B 仍然可用：不能因为"当前渠道不可用"就整条计划作废
        assertEquals(listOf(providerB.id.toString()), plan.map { it.providerId })
    }

    @Test
    fun `the last usable channel is still used when the others are gone`() {
        val withoutA = listOf(providerB.copy(models = listOf(modelB)))
        val plan = resolveChannelPlan(
            withoutA,
            modelA,
            routeOf(providerA.id.toString(), providerB.id.toString()),
        )
        assertEquals(listOf(providerB.id.toString()), plan.map { it.providerId })
        assertEquals(modelB.id, plan.single().model.id)
    }

    @Test
    fun `stale slots are skipped instead of calling unknown addresses`() {
        val plan = resolveChannelPlan(
            providers,
            modelA,
            routeOf(providerA.id.toString(), "00000000-0000-0000-0000-000000000000", providerB.id.toString()),
        )
        assertEquals(listOf(providerA.id.toString(), providerB.id.toString()), plan.map { it.providerId })
    }

    @Test
    fun `a slot pointing at another model of the same provider is ignored`() {
        // B 里有 gpt-4o，也有 gpt-4o-mini；槽位写 mini 时必须被丢弃，不能拿它顶替 gpt-4o
        val mini = Model(modelId = "gpt-4o-mini", displayName = "B-mini")
        val list = listOf(
            providerA.copy(models = listOf(modelA)),
            providerB.copy(models = listOf(modelB, mini)),
        )
        val failover = ChannelFailoverSettings(
            enabled = true,
            routes = listOf(
                ModelChannelRoute(
                    modelId = "gpt-4o",
                    channels = listOf(
                        ModelChannelSlot(providerA.id.toString(), "gpt-4o"),
                        ModelChannelSlot(providerB.id.toString(), "gpt-4o-mini"),
                    ),
                ),
            ),
        )
        val plan = resolveChannelPlan(list, modelB, failover)
        assertEquals(listOf(providerA.id.toString()), plan.map { it.providerId })
        assertEquals(listOf("gpt-4o"), plan.map { it.model.modelId })
    }

    @Test
    fun `channels from another protocol family are skipped`() {
        // 用户把 Claude 的 gpt-4o 也排了进去：跨协议无法保证请求正确，必须跳过
        val plan = resolveChannelPlan(
            providers,
            modelA,
            routeOf(providerA.id.toString(), providerC.id.toString(), providerB.id.toString()),
        )
        assertEquals(listOf(providerA.id.toString(), providerB.id.toString()), plan.map { it.providerId })
    }

    @Test
    fun `candidate groups are split by protocol family`() {
        val groups = channelCandidateGroups(providers)
        assertEquals(2, groups.size)
        val openai = groups.single { it.protocolFamily == FAMILY_OPENAI }
        val claude = groups.single { it.protocolFamily == FAMILY_CLAUDE }
        assertEquals("gpt-4o", openai.modelId)
        assertEquals(2, openai.channels.size)
        assertEquals(1, claude.channels.size)
        // 加渠道时候选只列同族
        assertEquals(2, channelCandidatesFor(providers, "gpt-4o", FAMILY_OPENAI).size)
        assertEquals(1, channelCandidatesFor(providers, "gpt-4o", FAMILY_CLAUDE).size)
    }

    @Test
    fun `disabled providers and non chat models are not channels`() {
        val disabledList = listOf(
            providerA.copy(models = listOf(modelA)),
            providerB.copy(enabled = false, models = listOf(modelB)),
        )
        assertEquals(
            listOf(modelA.id),
            resolveChannelPlan(
                disabledList,
                modelA,
                routeOf(providerA.id.toString(), providerB.id.toString()),
            ).map { it.model.id },
        )

        val imageModel = Model(modelId = "gpt-4o", type = ModelType.IMAGE)
        val withImage = listOf(
            providerA.copy(models = listOf(modelA)),
            providerB.copy(models = listOf(imageModel)),
        )
        assertEquals(
            listOf(modelA.id),
            resolveChannelPlan(withImage, modelA, routeOf(providerA.id.toString(), providerB.id.toString())).map { it.model.id },
        )
    }

    @Test
    fun `an unavailable whitelist never silently uses the current unlisted channel`() {
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            resolveChannelPlan(
                listOf(providerA, providerB.copy(enabled = false)), modelA,
                routeOf(providerB.id.toString()),
            )
        }
    }

    @Test
    fun `web account sessions are not interchangeable channels`() {
        val web = ProviderSetting.GeminiWeb(models = listOf(Model(modelId = "gpt-4o")))
        val list = listOf(web, providerB.copy(models = listOf(modelB)))
        val plan = resolveChannelPlan(
            list,
            web.models.first(),
            routeOf(web.id.toString(), providerB.id.toString()),
        )
        assertTrue(plan.isEmpty())
    }

    // ---- 错误判定 ----

    @Test
    fun `only channel-side failures are switchable`() {
        assertTrue(channelFailoverSwitchable(HttpException("unauthorized", 401)))
        assertTrue(channelFailoverSwitchable(HttpException("forbidden", 403)))
        assertTrue(channelFailoverSwitchable(HttpException("payment required", 402)))
        assertTrue(channelFailoverSwitchable(HttpException("server error", 500)))
        assertTrue(channelFailoverSwitchable(HttpException("too many requests", 429)))
        assertTrue(channelFailoverSwitchable(IOException("connection reset")))
        assertTrue(channelFailoverSwitchable(StreamTruncatedException("empty stream")))

        // 请求本身的问题：换渠道只会多打几次
        assertFalse(channelFailoverSwitchable(HttpException("bad request", 400)))
        assertFalse(channelFailoverSwitchable(HttpException("unprocessable", 422)))
        assertFalse(channelFailoverSwitchable(WebProtocolException("not json")))
        assertFalse(channelFailoverSwitchable(IllegalStateException("boom")))
        // 用户取消
        assertFalse(channelFailoverSwitchable(CancellationException("user stopped")))
    }

    // ---- 执行层 ----

    private class FakeProvider(
        val stream: (suspend () -> Flow<StreamChunk>)? = null,
        val generate: (suspend () -> TextGenerationResult)? = null,
    ) : Provider<ProviderSetting> {
        val streamCalls = mutableListOf<Pair<ProviderSetting, TextGenerationParams>>()
        val generateCalls = mutableListOf<Pair<ProviderSetting, TextGenerationParams>>()

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

        override suspend fun streamText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> {
            streamCalls += providerSetting to params
            return (stream ?: { flow { throw IOException("no stream configured") } }).invoke()
        }

        override suspend fun generateText(
            providerSetting: ProviderSetting,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            generateCalls += providerSetting to params
            return (generate ?: { error("no generate configured") }).invoke()
        }
    }

    private fun target(provider: ProviderSetting, model: Model) =
        ChannelTarget(provider.id.toString(), provider, model)

    private fun success(text: String = "ok") = TextGenerationResult(
        id = "r",
        model = "gpt-4o",
        message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
    )

    private fun emptyResult() = TextGenerationResult(
        id = "r",
        model = "gpt-4o",
        message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
    )

    @Test
    fun `stream switches to the next channel and delivers its chunks once`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(stream = { flow { throw IOException("down") } })
        val fakeB = FakeProvider(stream = { flow { emit(StreamChunk.TextDelta("t", "hello")) } })
        val switches = mutableListOf<String>()

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
            onSwitch = { from, to, _ -> switches += "${from.providerId}->${to.providerId}" },
        )

        val chunks = provider.streamText(providerA, emptyList(), TextGenerationParams(model = modelA)).toList()

        assertEquals(1, chunks.size)
        assertEquals(listOf("${a.providerId}->${b.providerId}"), switches)
        // 每次请求都带目标渠道自己的 setting/model（用量统计记在真实渠道上）
        assertEquals(providerA, fakeA.streamCalls.single().first)
        assertEquals(providerB, fakeB.streamCalls.single().first)
        assertEquals(modelB.id, fakeB.streamCalls.single().second.model.id)
        // 主渠道原样发，参数一个字节都不改
        assertEquals(modelA.id, fakeA.streamCalls.single().second.model.id)
    }

    @Test
    fun `an empty stream is treated as an empty response and switches`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(stream = { flow { } })
        val fakeB = FakeProvider(stream = { flow { emit(StreamChunk.TextDelta("t", "hello")) } })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        val chunks = provider.streamText(providerA, emptyList(), TextGenerationParams(model = modelA)).toList()

        assertEquals(1, chunks.size)
        assertEquals(1, fakeB.streamCalls.size)
        assertEquals(b.providerId, provider.currentChannel().providerId)
    }

    @Test
    fun `a finish-only stream switches without leaking the stale finish chunk`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(stream = { flow { emit(StreamChunk.Finish(finishReason = "stop")) } })
        val fakeB = FakeProvider(stream = { flow { emit(StreamChunk.TextDelta("t", "hello")) } })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        val chunks = provider.streamText(providerA, emptyList(), TextGenerationParams(model = modelA)).toList()

        assertEquals(listOf<StreamChunk>(StreamChunk.TextDelta("t", "hello")), chunks)
        assertEquals(1, fakeB.streamCalls.size)
    }

    @Test
    fun `metadata-only usage is buffered and dropped when the channel produces nothing`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(stream = {
            flow {
                emit(StreamChunk.Usage(TokenUsage(promptTokens = 10, completionTokens = 0, totalTokens = 10)))
                emit(StreamChunk.Finish(finishReason = "stop"))
            }
        })
        val fakeB = FakeProvider(stream = {
            flow {
                emit(StreamChunk.TextDelta("t", "hello"))
                emit(StreamChunk.Usage(TokenUsage(promptTokens = 10, completionTokens = 3, totalTokens = 13)))
            }
        })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        val chunks = provider.streamText(providerA, emptyList(), TextGenerationParams(model = modelA)).toList()

        // 失败的渠道一条分块都不该漏下去（否则两个渠道的用量会混在一起）
        assertEquals(2, chunks.size)
        assertTrue(chunks.none { it is StreamChunk.Finish })
        assertEquals(1, chunks.count { it is StreamChunk.Usage })
        assertEquals(1, fakeB.streamCalls.size)
    }

    @Test
    fun `an empty nonstream result switches to the next channel`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(generate = { emptyResult() })
        val fakeB = FakeProvider(generate = { success("from B") })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        val result = provider.generateText(providerA, emptyList(), TextGenerationParams(model = modelA))

        assertEquals("from B", result.message.toText())
        assertEquals(1, fakeB.generateCalls.size)
    }

    @Test
    fun `a stream that already produced output is never replayed on another channel`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(stream = {
            flow {
                emit(StreamChunk.TextDelta("t", "half"))
                throw IOException("cut")
            }
        })
        val fakeB = FakeProvider(stream = { flow { emit(StreamChunk.TextDelta("t", "other")) } })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        val received = mutableListOf<StreamChunk>()
        var failure: Throwable? = null
        try {
            provider.streamText(providerA, emptyList(), TextGenerationParams(model = modelA))
                .collect { received += it }
        } catch (e: Throwable) {
            failure = e
        }

        assertTrue(failure is IOException)
        assertEquals(1, received.size)
        assertTrue(fakeB.streamCalls.isEmpty())
    }

    @Test
    fun `a server tool start already emitted also blocks a replay`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(stream = {
            flow {
                emit(StreamChunk.ServerToolStart("s1", "web_search"))
                throw IOException("cut")
            }
        })
        val fakeB = FakeProvider(stream = { flow { emit(StreamChunk.TextDelta("t", "other")) } })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        var failure: Throwable? = null
        try {
            provider.streamText(providerA, emptyList(), TextGenerationParams(model = modelA)).collect { }
        } catch (e: Throwable) {
            failure = e
        }
        assertTrue(failure is IOException)
        assertTrue(fakeB.streamCalls.isEmpty())
    }

    @Test
    fun `request format errors do not burn another channel`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(stream = { flow { throw HttpException("bad request", 400) } })
        val fakeB = FakeProvider(stream = { flow { emit(StreamChunk.TextDelta("t", "other")) } })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        var failure: Throwable? = null
        try {
            provider.streamText(providerA, emptyList(), TextGenerationParams(model = modelA)).collect { }
        } catch (e: Throwable) {
            failure = e
        }
        assertTrue(failure is HttpException)
        assertTrue(fakeB.streamCalls.isEmpty())
    }

    @Test
    fun `cancellation propagates without touching the next channel`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(generate = { throw CancellationException("stopped") })
        val fakeB = FakeProvider(generate = { success() })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        var failure: Throwable? = null
        try {
            provider.generateText(providerA, emptyList(), TextGenerationParams(model = modelA))
        } catch (e: Throwable) {
            failure = e
        }
        assertTrue(failure is CancellationException)
        assertTrue(fakeB.generateCalls.isEmpty())
    }

    @Test
    fun `a throwing switch callback neither fails nor duplicates the request`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(generate = { throw HttpException("unauthorized", 401) })
        val fakeB = FakeProvider(generate = { success() })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
            onSwitch = { _, _, _ -> error("UI callback crashed") },
        )

        val result = provider.generateText(providerA, emptyList(), TextGenerationParams(model = modelA))

        assertEquals("ok", result.message.toText())
        assertEquals(1, fakeA.generateCalls.size)
        assertEquals(1, fakeB.generateCalls.size)
    }

    @Test
    fun `step params are preserved when the request moves to another channel`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(generate = { throw HttpException("server error", 500) })
        val fakeB = FakeProvider(generate = { success() })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            // 只有渠道相关字段被重写，其余必须沿用 prepareStep 那一步算好的值
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        val stepParams = TextGenerationParams(
            model = modelA,
            temperature = 0.3f,
            maxTokens = 1234,
            reasoningLevel = ReasoningLevel.HIGH,
            sessionId = "session-1",
            disableToolCalls = true,
        )
        provider.generateText(providerA, emptyList(), stepParams)

        val sent = fakeB.generateCalls.single().second
        assertEquals(modelB.id, sent.model.id)
        assertEquals(1234L, sent.maxTokens?.toLong())
        assertEquals(0.3f, sent.temperature ?: 0f, 0.0001f)
        assertEquals(ReasoningLevel.HIGH, sent.reasoningLevel)
        assertEquals("session-1", sent.sessionId)
        assertTrue(sent.disableToolCalls)
    }

    @Test
    fun `the channel that worked is reused by later steps`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(generate = { throw HttpException("unauthorized", 401) })
        val fakeB = FakeProvider(generate = { success() })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        val params = TextGenerationParams(model = modelA)
        provider.generateText(providerA, emptyList(), params)
        provider.generateText(providerA, emptyList(), params)
        provider.generateText(providerA, emptyList(), params)

        assertEquals(1, fakeA.generateCalls.size)
        assertEquals(3, fakeB.generateCalls.size)
        assertEquals(b.providerId, provider.currentChannel().providerId)
    }

    @Test
    fun `switching is bounded per turn and never loops back`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val c = target(providerC, modelC)
        val fakeA = FakeProvider(generate = { throw IOException("down") })
        val fakeB = FakeProvider(generate = { throw IOException("down") })
        val fakeC = FakeProvider(generate = { throw IOException("down") })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b, c),
            providerFor = {
                when (it.providerId) {
                    a.providerId -> fakeA
                    b.providerId -> fakeB
                    else -> fakeC
                }
            },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
            maxSwitchesPerTurn = 1,
        )

        var failure: Throwable? = null
        try {
            provider.generateText(providerA, emptyList(), TextGenerationParams(model = modelA))
        } catch (e: Throwable) {
            failure = e
        }

        assertTrue(failure is IOException)
        assertEquals(1, fakeA.generateCalls.size)
        assertEquals(1, fakeB.generateCalls.size)
        assertTrue(fakeC.generateCalls.isEmpty())
    }

    @Test
    fun `every channel is tried at most once in one call`() = runTest {
        val a = target(providerA, modelA)
        val b = target(providerB, modelB)
        val fakeA = FakeProvider(generate = { throw HttpException("server error", 503) })
        val fakeB = FakeProvider(generate = { throw HttpException("server error", 503) })

        val provider = ChannelFailoverProvider(
            channels = listOf(a, b),
            providerFor = { if (it.providerId == a.providerId) fakeA else fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
            maxSwitchesPerTurn = 100,
        )

        repeat(2) {
            var failure: Throwable? = null
            try {
                provider.generateText(providerA, emptyList(), TextGenerationParams(model = modelA))
            } catch (e: Throwable) {
                failure = e
            }
            // 全部渠道都失败时抛的是最后一次的真实错误，上层才能照常分类/自动恢复
            assertTrue(failure is HttpException)
        }
        // 只向后走：第一次 A、B 各试一次，第二次从 B 继续（不绕回 A）
        assertEquals(1, fakeA.generateCalls.size)
        assertEquals(2, fakeB.generateCalls.size)
    }

    @Test
    fun `a single channel plan still routes to that channel`() = runTest {
        val b = target(providerB, modelB)
        val fakeB = FakeProvider(generate = { success("from B") })

        val provider = ChannelFailoverProvider(
            channels = listOf(b),
            providerFor = { fakeB },
            rebuildParams = { params, channel -> params.copy(model = channel.model) },
        )

        val result = provider.generateText(providerA, emptyList(), TextGenerationParams(model = modelA))

        assertEquals("from B", result.message.toText())
        assertEquals(modelB.id, fakeB.generateCalls.single().second.model.id)
    }

    // ---- 设置里的排序 ----

    @Test
    fun `route editing keeps order and removes duplicates`() {
        var failover = ChannelFailoverSettings(enabled = true)
        val slotA = ModelChannelSlot(providerA.id.toString(), "gpt-4o")
        val slotB = ModelChannelSlot(providerB.id.toString(), "gpt-4o")
        failover = failover.withChannelAdded("gpt-4o", slotA)
        failover = failover.withChannelAdded("gpt-4o", slotB)
        failover = failover.withChannelAdded("gpt-4o", slotA).normalized()
        assertEquals(listOf(slotA, slotB), failover.routeFor("gpt-4o")?.channels)

        failover = failover.withChannelMoved("gpt-4o", 1, 0)
        assertEquals(listOf(slotB, slotA), failover.routeFor("gpt-4o")?.channels)

        failover = failover.withChannelRemoved("gpt-4o", slotB)
        assertEquals(listOf(slotA), failover.routeFor("gpt-4o")?.channels)

        failover = failover.withoutRoute("gpt-4o")
        assertTrue(failover.routes.isEmpty())
    }

    @Test
    fun `routes are matched ignoring case and trimmed`() {
        val failover = ChannelFailoverSettings(
            enabled = true,
            routes = listOf(ModelChannelRoute("  GPT-4o ", listOf(ModelChannelSlot(" p ", " m ")))),
        ).normalized()
        assertEquals("GPT-4o", failover.routes.single().modelId)
        assertEquals("p", failover.routes.single().channels.single().providerId)
        assertEquals("m", failover.routes.single().channels.single().modelId)
        assertTrue(failover.routeFor("gpt-4o") != null)
    }
}
