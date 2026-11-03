package me.rerere.rikkahub.data.companion

import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小助手状态机的真实行为：成功、失败、超时、取消、去重、限流、记忆窗口、模型未配。
 *
 * 用假 [CompanionReplySource]（纯 JVM，不碰网络/Android），时间全部走 `runTest` 的虚拟时钟，
 * 所以"超时"和"冷却"是确定性的断言，不是靠 sleep 猜。
 */
class CompanionAiControllerTest {

    private val enabled = FloatingCompanionSettings(
        aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION,
    )

    private class FakeSource(
        var description: CompanionModelDescription = CompanionModelDescription(
            label = "test-model · Test",
            source = CompanionModelMode.FOLLOW_CONVERSATION,
        ),
    ) : CompanionReplySource {
        var describeCalls = 0
        val requests = mutableListOf<CompanionRequest>()
        var behavior: suspend (CompanionRequest, (CompanionDelta) -> Unit) -> String =
            { _, onDelta ->
                onDelta(CompanionDelta(text = "你好呀"))
                "你好呀"
            }

        override suspend fun describeModel(conversationId: Uuid?): CompanionModelDescription {
            describeCalls++
            return description
        }

        override suspend fun generate(
            conversationId: Uuid?,
            request: CompanionRequest,
            onDelta: (CompanionDelta) -> Unit,
        ): String {
            requests += request
            return behavior(request, onDelta)
        }
    }

    private fun tap() = CompanionTrigger(CompanionTriggerKind.TAP)

    private fun auto(key: String) = CompanionTrigger(
        kind = CompanionTriggerKind.EVENT,
        event = CompanionEventKind.GENERATION_FINISHED,
        eventKey = key,
    )

    @Test
    fun `tap with a configured model completes and records the line`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        assertTrue(controller.submit(tap(), enabled))
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(CompanionAiPhase.COMPLETED, state.phase)
        assertEquals("你好呀", state.text)
        assertEquals("test-model · Test", state.model.label)
        assertEquals(CompanionTriggerKind.TAP, state.trigger?.kind)
        assertEquals(listOf(CompanionAiPhase.COMPLETED), controller.interactions.value.map { it.phase })
        assertEquals(1, source.requests.size)
        assertFalse(state.busy)
    }

    @Test
    fun `nothing is sent when the bubble is not shown`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        assertFalse(controller.submit(tap(), enabled))
        advanceUntilIdle()
        assertEquals(CompanionSkipReason.NOT_VISIBLE, controller.state.value.skipped?.reason)
        assertTrue(source.requests.isEmpty())
        assertEquals(CompanionAiPhase.IDLE, controller.state.value.phase)
    }

    @Test
    fun `nothing is sent while ai responses are off`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        assertFalse(controller.submit(tap(), FloatingCompanionSettings()))
        advanceUntilIdle()
        assertEquals(CompanionSkipReason.AI_OFF, controller.state.value.skipped?.reason)
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun `auto events stay quiet until the switch is turned on`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        assertFalse(controller.submit(auto("finish:1"), enabled))
        advanceUntilIdle()
        assertEquals(CompanionSkipReason.AUTO_EVENTS_OFF, controller.state.value.skipped?.reason)
        assertTrue(source.requests.isEmpty())

        assertTrue(controller.submit(auto("finish:1"), enabled.copy(autoEventsEnabled = true)))
        advanceUntilIdle()
        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
    }

    @Test
    fun `only one request is in flight and a second trigger is skipped`() = runTest {
        val source = FakeSource()
        val gate = CompletableDeferred<Unit>()
        source.behavior = { _, _ ->
            gate.await()
            "慢一点"
        }
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        assertTrue(controller.submit(tap(), enabled))
        runCurrent()
        assertEquals(CompanionAiPhase.THINKING, controller.state.value.phase)

        assertFalse(controller.submit(tap(), enabled))
        assertEquals(CompanionSkipReason.BUSY, controller.state.value.skipped?.reason)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
        assertEquals(1, source.requests.size)
    }

    @Test
    fun `streaming deltas move the phase to generating`() = runTest {
        val source = FakeSource()
        source.behavior = { _, onDelta ->
            onDelta(CompanionDelta(reasoning = "想一想"))
            onDelta(CompanionDelta(text = "在"))
            onDelta(CompanionDelta(text = "在呢"))
            "在呢"
        }
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        controller.submit(tap(), enabled)
        runCurrent()
        advanceUntilIdle()
        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
        assertEquals("在呢", controller.state.value.text)
        assertEquals("想一想", controller.state.value.thinking)
    }

    @Test
    fun `request failure is reported as failed and recorded`() = runTest {
        val source = FakeSource()
        source.behavior = { _, _ -> throw IllegalStateException("boom") }
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        controller.submit(tap(), enabled)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(CompanionAiPhase.FAILED, state.phase)
        assertEquals(CompanionFailure.Request("boom"), state.failure)
        assertEquals(CompanionAiPhase.FAILED, controller.interactions.value.single().phase)
        assertEquals(1, source.requests.size)
    }

    @Test
    fun `a missing model fails loudly and never calls the model`() = runTest {
        val source = FakeSource(description = CompanionModelDescription(failure = CompanionFailure.ModelMissing))
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        controller.submit(tap(), enabled.copy(aiModelMode = CompanionModelMode.FIXED, aiModelId = Uuid.random()))
        advanceUntilIdle()

        assertEquals(CompanionAiPhase.FAILED, controller.state.value.phase)
        assertEquals(CompanionFailure.ModelMissing, controller.state.value.failure)
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun `a disabled ai response is reported instead of silently falling back`() = runTest {
        val source = FakeSource(description = CompanionModelDescription(failure = CompanionFailure.AiDisabled))
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        // 设置说开着、实际解析却关掉了的极端情况：状态必须说清楚，而不是换一个模型偷偷发
        controller.submit(tap(), enabled)
        advanceUntilIdle()
        assertEquals(CompanionFailure.AiDisabled, controller.state.value.failure)
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun `a request that never answers is cancelled by the timeout`() = runTest {
        val source = FakeSource()
        source.behavior = { _, _ ->
            delay(60_000)
            "永远不来"
        }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            timeoutMillis = 1_000,
        )
        controller.setVisible(true)
        controller.submit(tap(), enabled)
        advanceUntilIdle()

        assertEquals(CompanionAiPhase.CANCELLED, controller.state.value.phase)
        assertEquals(CompanionCancelReason.TIMEOUT, controller.state.value.cancelReason)
        assertEquals(CompanionAiPhase.CANCELLED, controller.interactions.value.single().phase)
    }

    @Test
    fun `stop cancels the in-flight request`() = runTest {
        val source = FakeSource()
        val gate = CompletableDeferred<Unit>()
        source.behavior = { _, _ ->
            gate.await()
            "不该出现"
        }
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        controller.submit(tap(), enabled)
        runCurrent()
        controller.stop()
        runCurrent()

        assertEquals(CompanionAiPhase.CANCELLED, controller.state.value.phase)
        assertEquals(CompanionCancelReason.USER, controller.state.value.cancelReason)
        assertEquals("", controller.state.value.text)
    }

    @Test
    fun `closing the window cancels the in-flight request`() = runTest {
        val source = FakeSource()
        val gate = CompletableDeferred<Unit>()
        source.behavior = { _, _ ->
            gate.await()
            "不该出现"
        }
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        controller.submit(tap(), enabled)
        runCurrent()
        controller.setVisible(false)
        runCurrent()

        assertEquals(CompanionAiPhase.CANCELLED, controller.state.value.phase)
        assertEquals(CompanionCancelReason.LEFT_WINDOW, controller.state.value.cancelReason)
    }

    @Test
    fun `memory keeps only the last few exchanges and feeds the next prompt`() = runTest {
        val source = FakeSource()
        var index = 0
        source.behavior = { _, _ -> "第${++index}句" }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 0)),
            memory = CompanionMemory(capacity = 2),
        )
        controller.setVisible(true)
        repeat(4) {
            controller.submit(tap(), enabled)
            advanceUntilIdle()
        }
        assertEquals(4, source.requests.size)
        // 第 3 次请求时记忆里已经有两条（一问一答 = 4 条 message）
        assertEquals(4, source.requests[2].history.size)
        // 第 4 次请求的记忆窗口只剩最近两条：第 2、3 句（第 4 句还没生成）
        val lastHistory = source.requests.last().history
        assertEquals(4, lastHistory.size)
        assertEquals("第2句", lastHistory[1].text)
        assertEquals("第3句", lastHistory[3].text)
    }

    @Test
    fun `interaction list is bounded`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 0)),
        )
        controller.setVisible(true)
        repeat(CompanionLimits.MAX_INTERACTIONS + 5) {
            controller.submit(tap(), enabled)
            advanceUntilIdle()
        }
        assertEquals(CompanionLimits.MAX_INTERACTIONS, controller.interactions.value.size)
    }

    @Test
    fun `refresh only re-reads the model and never sends a request`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.refreshModel()
        advanceUntilIdle()
        assertEquals("test-model · Test", controller.state.value.model.label)
        assertTrue(source.requests.isEmpty())
        assertEquals(CompanionAiPhase.IDLE, controller.state.value.phase)
    }

    @Test
    fun `repeat replays the last trigger`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 0)),
        )
        controller.setVisible(true)
        controller.submit(CompanionTrigger(CompanionTriggerKind.INPUT, detail = "在吗"), enabled)
        advanceUntilIdle()
        controller.repeatLast()
        advanceUntilIdle()
        assertEquals(2, source.requests.size)
        assertTrue(source.requests.first().userPrompt.contains("在吗"))
    }

    @Test
    fun `auto event requests carry the event only, never chat content`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(scope = this, source = source, clock = { testScheduler.currentTime })
        controller.setVisible(true)
        controller.submit(auto("finish:secret"), enabled.copy(autoEventsEnabled = true))
        advanceUntilIdle()
        val request = source.requests.single()
        assertTrue(request.history.isEmpty())
        assertTrue(request.userPrompt.contains("finished"))
        assertFalse(request.userPrompt.contains("闲聊正文"))
        assertFalse(request.systemPrompt.contains("闲聊正文"))
    }
}
