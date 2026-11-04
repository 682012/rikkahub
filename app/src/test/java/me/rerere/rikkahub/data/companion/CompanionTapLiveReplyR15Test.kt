package me.rerere.rikkahub.data.companion

import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r15：每一次被接受的点击都必须真的问一次模型，并按这一下自己的“即兴方向”生成。
 *
 * 这里钉住的是**行为**，不是文案：
 * - 两次点击 = 两次真实 `source.generate`，两次的 prompt 不同；
 * - 8 句固定台词既不进请求，也不冒充结果；
 * - 用户自己写的人设优先于内置角色（叠加段不串进来）；
 * - 忙、冷却、离线都不发请求，也不占“方向序号”；
 * - 模型重复同一句时不会被自动重买一次；
 * - 换角色后旧 run 的迟到结果既写不进状态，也进不了新角色的上下文；
 * - 保留原模型请求参数，不注入采样参数或新增收费重试。
 */
class CompanionTapLiveReplyR15Test {

    private val dragon = FloatingCompanionSettings(
        preset = FloatingCompanionPreset.DRAGON_GIRL,
        aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION,
    )

    private val sun = dragon.copy(preset = FloatingCompanionPreset.SUN_READER)

    private fun noCooldown() = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 0))

    private fun tap() = CompanionTrigger(CompanionTriggerKind.TAP)

    private fun autoFinished(key: String) = CompanionTrigger(
        kind = CompanionTriggerKind.EVENT,
        event = CompanionEventKind.GENERATION_FINISHED,
        eventKey = key,
    )

    /** 假 source：只记录请求、按 behavior 返回；不碰网络、不碰 Android。 */
    private class FakeSource : CompanionReplySource {
        val requests = mutableListOf<CompanionRequest>()
        var behavior: suspend (CompanionRequest, (CompanionDelta) -> Unit) -> String =
            { _, onDelta ->
                onDelta(CompanionDelta(text = "嗯？"))
                "嗯？"
            }

        override suspend fun describeModel(conversationId: Uuid?): CompanionModelDescription =
            CompanionModelDescription(
                label = "test-model · Test",
                source = CompanionModelMode.FOLLOW_CONVERSATION,
            )

        override suspend fun generate(
            conversationId: Uuid?,
            request: CompanionRequest,
            onDelta: (CompanionDelta) -> Unit,
        ): String {
            requests += request
            return behavior(request, onDelta)
        }
    }

    @Test
    fun `each accepted tap asks the model once along its own direction`() = runTest {
        val source = FakeSource()
        var index = 0
        source.behavior = { _, _ -> "现场第${++index}句" }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = noCooldown(),
        )
        controller.setVisible(true)

        assertTrue(controller.submit(tap(), dragon))
        advanceUntilIdle()
        assertTrue(controller.submit(tap(), dragon))
        advanceUntilIdle()

        assertEquals(2, source.requests.size)
        assertEquals(2, controller.acceptedTapTurns)
        val (first, second) = source.requests
        assertNotEquals(first.userPrompt, second.userPrompt)
        assertTrue(second.userPrompt.contains("improvise along this direction"))
        assertEquals("现场第2句", controller.state.value.text)
        assertEquals(
            listOf(CompanionAiPhase.COMPLETED, CompanionAiPhase.COMPLETED),
            controller.interactions.value.map { it.phase },
        )
    }

    @Test
    fun `the eight offline lines never enter the request or the reply`() = runTest {
        val source = FakeSource()
        val generated = "刚醒，正接住你这下"
        source.behavior = { _, _ -> generated }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = noCooldown(),
        )
        controller.setVisible(true)
        controller.submit(tap(), dragon)
        advanceUntilIdle()

        val fixed = CompanionCatalog.portraitTapLines(FloatingCompanionPreset.DRAGON_GIRL)
        assertEquals(8, fixed.size)
        val request = source.requests.single()
        val everything = buildString {
            append(request.systemPrompt).append('\n')
            append(request.userPrompt).append('\n')
            request.history.forEach { append(it.text).append('\n') }
        }
        fixed.forEach { line -> assertFalse("offline line leaked: $line", everything.contains(line)) }
        assertEquals(generated, controller.state.value.text)
        assertFalse(fixed.contains(controller.state.value.text))
    }

    @Test
    fun `a hand written persona wins over the built in one`() = runTest {
        val source = FakeSource()
        val custom = dragon.copy(aiPersonaPrompt = "你是测试角色阿青，说话像在记账，句子短。")
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = noCooldown(),
        )
        controller.setVisible(true)
        controller.submit(tap(), custom)
        advanceUntilIdle()

        val system = source.requests.single().systemPrompt
        assertTrue(system.contains("测试角色阿青"))
        // 内置人设和 r15 的角色叠加段都不应该跟用户写的人设一起发出去
        val builtInPersona = CompanionCatalog.profile(FloatingCompanionPreset.DRAGON_GIRL)!!.persona
        assertFalse(system.contains(builtInPersona.take(20)))
        val overlay = CompanionPersonas.builtInOverlay(FloatingCompanionPreset.DRAGON_GIRL)
        if (overlay.isNotBlank()) assertFalse(system.contains(overlay.take(20)))
        assertFalse(system.contains("no bracketed stage directions"))
        assertFalse(system.contains("No assistant or customer-service opening"))
        assertTrue(system.contains("The custom persona above defines your identity"))
        val genericHint = CompanionTapImagination.hint(null, 1, false)
        assertTrue(source.requests.single().userPrompt.contains(genericHint))
        val dragonHint = CompanionTapImagination.hint("gpt", 1, false)
        assertFalse(source.requests.single().userPrompt.contains(dragonHint))
    }

    @Test
    fun `a second tap while one is in flight never stacks a paid request`() = runTest {
        val source = FakeSource()
        val gate = CompletableDeferred<Unit>()
        source.behavior = { _, _ ->
            gate.await()
            "慢慢来"
        }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = noCooldown(),
        )
        controller.setVisible(true)

        assertTrue(controller.submit(tap(), dragon))
        runCurrent()
        assertFalse(controller.submit(tap(), dragon))
        assertEquals(CompanionSkipReason.BUSY, controller.state.value.skipped?.reason)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, source.requests.size)
        // 被拒绝的那一下不占方向序号，也不算说过的内容
        assertEquals(1, controller.acceptedTapTurns)
        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
    }

    @Test
    fun `with ai responses off a tap never reaches the model`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = noCooldown(),
        )
        controller.setVisible(true)

        assertFalse(controller.submit(tap(), FloatingCompanionSettings(preset = FloatingCompanionPreset.DRAGON_GIRL)))
        advanceUntilIdle()

        assertEquals(CompanionSkipReason.AI_OFF, controller.state.value.skipped?.reason)
        assertTrue(source.requests.isEmpty())
        assertEquals(0, controller.acceptedTapTurns)
        assertEquals(CompanionAiPhase.IDLE, controller.state.value.phase)
    }

    @Test
    fun `an identical reply is shown once and never re bought automatically`() = runTest {
        val source = FakeSource()
        source.behavior = { _, _ -> "在呢" }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = noCooldown(),
        )
        controller.setVisible(true)

        repeat(2) {
            assertTrue(controller.submit(tap(), dragon))
            advanceUntilIdle()
        }
        // 两次点击 = 两次请求；模型说了同一句也不会自动重试或再生成一个候选
        assertEquals(2, source.requests.size)
        assertEquals("在呢", controller.state.value.text)
        assertEquals(2, controller.interactions.value.size)
    }

    @Test
    fun `a late reply from the previous character never lands on the new one`() = runTest {
        val source = FakeSource()
        val gate = CompletableDeferred<Unit>()
        // 故意不配合取消的 source：吞掉取消后照样返回，模拟真实实现里最坏的情况
        source.behavior = { _, _ ->
            runCatching { gate.await() }
            "旧角色的话"
        }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = noCooldown(),
        )
        controller.setVisible(true)

        assertTrue(controller.submit(tap(), dragon))
        runCurrent()
        controller.switchCharacter(sun)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("", controller.state.value.text)
        assertNotEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
        assertTrue(controller.interactions.value.isEmpty())
        assertEquals(0, controller.acceptedTapTurns)

        // 新角色的下一句完全看不到旧角色的任何痕迹（小记忆已经清掉）
        assertTrue(controller.submit(tap(), sun))
        advanceUntilIdle()
        assertTrue(source.requests.last().history.isEmpty())
        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
    }

    @Test
    fun `switching character resets the accepted tap ordinal`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = noCooldown(),
        )
        controller.setVisible(true)

        repeat(2) {
            assertTrue(controller.submit(tap(), dragon))
            advanceUntilIdle()
        }
        assertEquals(2, controller.acceptedTapTurns)

        controller.switchCharacter(sun)
        assertEquals(0, controller.acceptedTapTurns)

        assertTrue(controller.submit(tap(), sun))
        advanceUntilIdle()
        assertEquals(1, controller.acceptedTapTurns)
    }

    @Test
    fun `a tap rejected by the cooldown neither bills nor consumes a direction`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(),
        )
        controller.setVisible(true)

        assertTrue(controller.submit(tap(), dragon))
        advanceUntilIdle()
        assertEquals(1, source.requests.size)

        // 约 1 秒防抖：手抖的第二下被挡下，不花钱也不占序号
        assertFalse(controller.submit(tap(), dragon))
        assertEquals(CompanionSkipReason.COOLDOWN, controller.state.value.skipped?.reason)
        assertEquals(1, source.requests.size)
        assertEquals(1, controller.acceptedTapTurns)

        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(controller.submit(tap(), dragon))
        advanceUntilIdle()
        assertEquals(2, source.requests.size)
        assertEquals(2, controller.acceptedTapTurns)
    }

    @Test
    fun `a timed out tap is cancelled and its late answer never enters memory`() = runTest {
        val source = FakeSource()
        source.behavior = { _, _ ->
            delay(60_000)
            "迟到的句子"
        }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = noCooldown(),
            timeoutMillis = 1_000,
        )
        controller.setVisible(true)

        controller.submit(tap(), dragon)
        advanceUntilIdle()
        assertEquals(CompanionAiPhase.CANCELLED, controller.state.value.phase)
        assertEquals(CompanionCancelReason.TIMEOUT, controller.state.value.cancelReason)

        controller.submit(tap(), dragon)
        advanceUntilIdle()
        assertEquals(2, source.requests.size)
        // 超时那一轮没有进记忆，下一句的上下文是干净的
        assertTrue(source.requests.last().history.isEmpty())
    }

    @Test
    fun `only taps carry a direction while app events stay plain`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = noCooldown(),
        )
        controller.setVisible(true)

        controller.submit(autoFinished("finish:1"), dragon.copy(autoEventsEnabled = true))
        advanceUntilIdle()

        val request = source.requests.single()
        assertTrue(request.userPrompt.contains("finished"))
        assertFalse(request.userPrompt.contains("improvise along this direction"))
        assertEquals(0, controller.acceptedTapTurns)
    }

}
