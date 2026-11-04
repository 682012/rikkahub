package me.rerere.rikkahub.data.companion

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r12 定向回归：切角色的隔离。
 *
 * 三件事必须成立：
 * 1. 切角色取消在途请求，**不合作**的 source（吞掉 CancellationException 照样返回）也不能把台词写回来；
 * 2. 上一个角色的短期记忆不带到新角色，新角色第一句的历史为空；
 * 3. 切角色不是"免费重置冷却"：刚切完连点仍然被冷却挡住，等够时间才真的发请求。
 */
class CompanionCharacterIsolationR12Test {

    private val aiOn = FloatingCompanionSettings(aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION)

    private fun character(preset: FloatingCompanionPreset, name: String) =
        aiOn.copy(preset = preset, name = name)

    private fun tap() = CompanionTrigger(CompanionTriggerKind.TAP)

    private class FakeSource(
        var description: CompanionModelDescription = CompanionModelDescription(
            label = "test-model · Test",
            source = CompanionModelMode.FOLLOW_CONVERSATION,
        ),
    ) : CompanionReplySource {
        val requests = mutableListOf<CompanionRequest>()
        var onGenerate: suspend (Int) -> String = { _ -> "好呀" }

        override suspend fun describeModel(conversationId: Uuid?): CompanionModelDescription = description

        override suspend fun generate(
            conversationId: Uuid?,
            request: CompanionRequest,
            onDelta: (CompanionDelta) -> Unit,
        ): String {
            val index = requests.size
            requests += request
            return onGenerate(index)
        }
    }

    @Test
    fun `switching cancels a non cooperative stream and never writes it back`() = runTest {
        val source = FakeSource()
        val gate = CompletableDeferred<Unit>()
        source.onGenerate = {
            try {
                gate.await()
            } catch (_: CancellationException) {
                // 不合作：取消被吞掉，接口照样返回一句"成品"
            }
            "旧角色的迟到台词"
        }
        val memory = CompanionMemory(capacity = 4)
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            memory = memory,
        )
        val whale = character(FloatingCompanionPreset.WHALE_MAID, "喵丸")
        val cat = character(FloatingCompanionPreset.GEMINI_CAT, "小橘")

        controller.setVisible(true)
        assertTrue(controller.submit(tap(), whale))
        runCurrent()
        assertEquals(CompanionAiPhase.THINKING, controller.state.value.phase)

        controller.switchCharacter(cat)
        runCurrent()
        // 切角色后界面是干净的新角色状态：不带着上一只的阶段/台词/忙碌标记
        assertEquals(CompanionAiPhase.IDLE, controller.state.value.phase)
        assertEquals("", controller.state.value.text)
        assertFalse(controller.state.value.busy)

        // 旧流收尾：不许变成 COMPLETED，不许覆盖状态，不许进记忆
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("迟到的台词不能回填", CompanionAiPhase.IDLE, controller.state.value.phase)
        assertEquals("", controller.state.value.text)
        assertEquals(0, memory.size)
        assertTrue(
            "迟到的台词不能落成 COMPLETED",
            controller.interactions.value.none { it.phase == CompanionAiPhase.COMPLETED },
        )
        assertEquals("切角色不新建请求", 1, source.requests.size)
    }

    @Test
    fun `a late reply from the previous character cannot overwrite the new one`() = runTest {
        val source = FakeSource()
        val gate = CompletableDeferred<Unit>()
        source.onGenerate = { index ->
            if (index == 0) {
                try {
                    gate.await()
                } catch (_: CancellationException) {
                    // 不合作
                }
                "旧角色的迟到台词"
            } else {
                "新角色第一句"
            }
        }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 0)),
        )
        val whale = character(FloatingCompanionPreset.WHALE_MAID, "喵丸")
        val cat = character(FloatingCompanionPreset.GEMINI_CAT, "小橘")

        controller.setVisible(true)
        assertTrue(controller.submit(tap(), whale))
        runCurrent()
        controller.switchCharacter(cat)
        runCurrent()

        assertTrue(controller.submit(tap(), cat))
        advanceUntilIdle()
        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
        assertEquals("新角色第一句", controller.state.value.text)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("迟到结果不许盖掉新角色", "新角色第一句", controller.state.value.text)
        assertTrue(
            "旧角色的迟到台词不能进互动记录",
            controller.interactions.value.none { it.text == "旧角色的迟到台词" },
        )
    }

    @Test
    fun `the previous character's memory is not carried into the new one`() = runTest {
        val source = FakeSource()
        var index = 0
        source.onGenerate = { "第${++index}句" }
        val memory = CompanionMemory(capacity = 4)
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 0)),
            memory = memory,
        )
        val whale = character(FloatingCompanionPreset.WHALE_MAID, "喵丸")
        val cat = character(FloatingCompanionPreset.GEMINI_CAT, "小橘")

        controller.setVisible(true)
        assertTrue(controller.submit(tap(), whale))
        advanceUntilIdle()
        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
        assertEquals(1, memory.size)

        controller.switchCharacter(cat)
        assertEquals("切角色要清掉上一个角色的短期记忆", 0, memory.size)

        assertTrue(controller.submit(tap(), cat))
        advanceUntilIdle()
        assertEquals("新角色第一句不带上一个角色的历史", 0, source.requests.last().history.size)
        assertTrue(source.requests.last().systemPrompt.contains("小橘"))
        assertFalse(source.requests.last().systemPrompt.contains("喵丸"))
    }

    @Test
    fun `switching does not reset the tap cooldown`() = runTest {
        val source = FakeSource()
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
        )
        val whale = character(FloatingCompanionPreset.WHALE_MAID, "喵丸")
        val cat = character(FloatingCompanionPreset.GEMINI_CAT, "小橘")

        controller.setVisible(true)
        assertTrue(controller.submit(tap(), whale))
        advanceUntilIdle()
        assertEquals(1, source.requests.size)

        // 切角色后立刻再点：仍然是同一次手动冷却，不能借切角色多发一次请求
        controller.switchCharacter(cat)
        assertFalse(controller.submit(tap(), cat))
        assertEquals(CompanionSkipReason.COOLDOWN, controller.state.value.skipped?.reason)
        advanceUntilIdle()
        assertEquals(1, source.requests.size)

        // 等够冷却才真的发
        advanceTimeBy(CompanionPolicyConfig().manualCooldownMillis + 1)
        assertTrue(controller.submit(tap(), cat))
        advanceUntilIdle()
        assertEquals(2, source.requests.size)
        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
    }
}
