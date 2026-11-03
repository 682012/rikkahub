package me.rerere.rikkahub.data.companion

import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.datastore.CompanionModelMode
import me.rerere.rikkahub.data.datastore.FloatingCompanionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只读审查 r10 小助手运行链路时补的对抗性用例（独立文件，不改任何已有测试）。
 *
 * 这几类风险单靠 happy path 测不出来，这里逐个钉在控制器/策略的公开接口上：
 * 1. 关窗口 / 按停止之后，被取消的那一轮不能留下"成品"（不进记忆、不进状态、不留思考中）；
 * 2. 停止后马上重来，新请求的结果不能被旧的取消覆盖；
 * 3. 自动事件是"来一个提交一个"，必须被每小时预算硬挡住；窗口反复开关不能把去重/冷却清零；
 * 4. 用户在设置里删掉固定模型后，下一次触发不能再拿旧解析结果发请求。
 *
 * 全部纯 JVM：假 [CompanionReplySource] + `runTest` 虚拟时钟，不碰网络、Android 和真实密钥。
 */
class CompanionRuntimeAdversarialR10Test {

    private val aiOn = FloatingCompanionSettings(aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION)
    private val autoOn = aiOn.copy(autoEventsEnabled = true)

    private fun tap() = CompanionTrigger(CompanionTriggerKind.TAP)

    private fun event(key: String) = CompanionTrigger(
        kind = CompanionTriggerKind.EVENT,
        event = CompanionEventKind.GENERATION_FINISHED,
        eventKey = key,
    )

    /** 按调用序号编排行为的假实现：第 0 次、第 1 次……各说各的，方便验证顺序和归属。 */
    private class ScriptedSource(
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
    fun `closing the window mid request leaves no reply behind for the next request`() = runTest {
        val source = ScriptedSource()
        val gate = CompletableDeferred<Unit>()
        source.onGenerate = { index ->
            if (index == 0) {
                gate.await()
                "取消前流出来的半句"
            } else {
                "重新来一句"
            }
        }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 0)),
        )
        controller.setVisible(true)
        assertTrue(controller.submit(tap(), aiOn))
        runCurrent()
        assertEquals(CompanionAiPhase.THINKING, controller.state.value.phase)

        controller.setVisible(false)
        runCurrent()

        val afterClose = controller.state.value
        assertEquals(CompanionAiPhase.CANCELLED, afterClose.phase)
        assertEquals(CompanionCancelReason.LEFT_WINDOW, afterClose.cancelReason)
        assertEquals("窗口关掉后不能留下半句当成说完", "", afterClose.text)
        assertFalse("窗口关掉后不能还挂在思考中", afterClose.busy)
        assertEquals(1, source.requests.size)

        // 窗口回来再说一句：被取消的那一轮既不能进记忆，也不能算一条完成记录
        controller.setVisible(true)
        assertTrue(controller.submit(tap(), aiOn))
        advanceUntilIdle()

        assertEquals("取消掉的回复不能进入下一轮提示词", 0, source.requests[1].history.size)
        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
        assertEquals("重新来一句", controller.state.value.text)
        assertEquals(
            listOf(CompanionAiPhase.COMPLETED, CompanionAiPhase.CANCELLED),
            controller.interactions.value.map { it.phase },
        )
    }

    @Test
    fun `a stop followed by a new tap ends on the new reply not on the cancelled one`() = runTest {
        val source = ScriptedSource()
        val gate = CompletableDeferred<Unit>()
        source.onGenerate = { index ->
            if (index == 0) {
                gate.await()
                "不该出现"
            } else {
                "第二句"
            }
        }
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 0)),
        )
        controller.setVisible(true)
        assertTrue(controller.submit(tap(), aiOn))
        runCurrent()

        controller.stop()
        runCurrent()
        assertEquals(CompanionAiPhase.CANCELLED, controller.state.value.phase)
        assertEquals(CompanionCancelReason.USER, controller.state.value.cancelReason)

        // 停完立刻重来：旧一轮的取消不能覆盖新一轮的结果
        assertTrue(controller.submit(tap(), aiOn))
        advanceUntilIdle()

        assertEquals(2, source.requests.size)
        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
        assertEquals("第二句", controller.state.value.text)
        assertNull(controller.state.value.cancelReason)
        assertEquals(
            "最新一条记录必须属于刚完成的那一轮",
            CompanionAiPhase.COMPLETED,
            controller.interactions.value.first().phase,
        )
        assertEquals(
            listOf(CompanionAiPhase.COMPLETED, CompanionAiPhase.CANCELLED),
            controller.interactions.value.map { it.phase },
        )
    }

    @Test
    fun `a burst of different app events is hard capped by the hourly budget`() = runTest {
        val source = ScriptedSource()
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(
                CompanionPolicyConfig(
                    autoCooldownMillis = 0,
                    sameEventCooldownMillis = 0,
                    maxAutoPerHour = 3,
                ),
            ),
        )
        controller.setVisible(true)

        // 悬浮窗把"每一个真实事件"都交给控制器，所以预算必须在控制器/策略这一层硬挡
        repeat(6) { index ->
            controller.submit(event("finish:conv:$index"), autoOn)
            advanceUntilIdle()
        }

        assertEquals(3, source.requests.size)
        assertEquals(CompanionSkipReason.BUDGET, controller.state.value.skipped?.reason)
    }

    @Test
    fun `reopening the window never re-answers the same event`() = runTest {
        val source = ScriptedSource()
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(CompanionPolicyConfig(autoCooldownMillis = 0)),
        )
        controller.setVisible(true)
        val finished = event("finish:conv:9")
        assertTrue(controller.submit(finished, autoOn))
        advanceUntilIdle()
        assertEquals(1, source.requests.size)

        // 窗口关掉再开：不能把去重/冷却清零，否则反复开窗口就是反复花钱
        controller.setVisible(false)
        runCurrent()
        controller.setVisible(true)

        assertFalse(controller.submit(finished, autoOn))
        assertEquals(CompanionSkipReason.DUPLICATE_EVENT, controller.state.value.skipped?.reason)
        assertEquals(1, source.requests.size)
    }

    @Test
    fun `after the fixed model is deleted the next trigger never calls the model`() = runTest {
        val source = ScriptedSource()
        val controller = CompanionAiController(
            scope = this,
            source = source,
            clock = { testScheduler.currentTime },
            policy = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 0)),
        )
        controller.setVisible(true)
        val fixed = aiOn.copy(aiModelMode = CompanionModelMode.FIXED, aiModelId = Uuid.random())
        assertTrue(controller.submit(tap(), fixed))
        advanceUntilIdle()
        assertEquals(1, source.requests.size)

        // 用户在设置里删掉这个模型：下一次触发要明说"没有模型"，不能沿用旧的解析结果
        source.description = CompanionModelDescription(failure = CompanionFailure.ModelMissing)
        controller.refreshModel()
        advanceUntilIdle()
        assertNull("模型没了就不能再显示旧模型名", controller.state.value.model.label)
        assertEquals(CompanionFailure.ModelMissing, controller.state.value.model.failure)

        controller.submit(tap(), fixed)
        advanceUntilIdle()

        assertEquals("删掉模型后不能再发一次请求", 1, source.requests.size)
        assertEquals(CompanionAiPhase.FAILED, controller.state.value.phase)
        assertEquals(CompanionFailure.ModelMissing, controller.state.value.failure)
        assertEquals(
            listOf(CompanionAiPhase.FAILED, CompanionAiPhase.COMPLETED),
            controller.interactions.value.map { it.phase },
        )
    }
}
