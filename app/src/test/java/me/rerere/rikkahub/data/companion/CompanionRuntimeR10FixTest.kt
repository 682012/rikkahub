package me.rerere.rikkahub.data.companion

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
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
 * P1 修复的回归钉子（纯 JVM，假 [CompanionReplySource] + `runTest` 虚拟时钟）。
 *
 * 1. **不合作的取消**：source 吞掉 CancellationException、照样返回一句"成品"（等价于阻塞式
 *    OkHttp 回调收尾、包装层 `catch (Throwable)`、flow 断流时的 finally），这一轮也必须是 CANCELLED，
 *    不能写成 COMPLETED，不能进记忆；
 * 2. **旧 run 的迟到结果**不能覆盖新一轮（run token）；
 * 3. **refresh 在 busy 时不覆盖**当前请求的模型，这一轮结束后下一次刷新才更新（描述与实际请求同一快照）；
 * 4. **超时覆盖模型解析**：解析卡住也按 TIMEOUT 结束，不发请求；
 * 5. **失败体脱敏限长**：不进覆盖层、不留密钥、保留状态码；
 * 6. **跟随会话没有会话时不许回退全局模型**（决策表 + 控制器行为）。
 */
class CompanionRuntimeR10FixTest {

    private val aiOn = FloatingCompanionSettings(aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION)

    private fun tap() = CompanionTrigger(CompanionTriggerKind.TAP)

    private class FakeSource(
        var description: CompanionModelDescription = CompanionModelDescription(
            label = "test-model · Test",
            source = CompanionModelMode.FOLLOW_CONVERSATION,
        ),
    ) : CompanionReplySource {
        val requests = mutableListOf<CompanionRequest>()
        var onDescribe: suspend () -> CompanionModelDescription? = { null }
        var onGenerate: suspend (Int) -> String = { _ -> "好呀" }

        override suspend fun describeModel(conversationId: Uuid?): CompanionModelDescription {
            onDescribe()?.let { return it }
            return description
        }

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

    private fun newController(
        scope: kotlinx.coroutines.CoroutineScope,
        source: CompanionReplySource,
        timeoutMillis: Long = CompanionLimits.REQUEST_TIMEOUT_MILLIS,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) = CompanionAiController(
        scope = scope,
        source = source,
        clock = { scheduler.currentTime },
        policy = CompanionTriggerPolicy(CompanionPolicyConfig(manualCooldownMillis = 0)),
        timeoutMillis = timeoutMillis,
    )

    @Test
    fun `a source that swallows cancellation still cannot finish the cancelled run`() = runTest {
        val source = FakeSource()
        val gate = CompletableDeferred<Unit>()
        source.onGenerate = { index ->
            if (index == 0) {
                try {
                    gate.await()
                } catch (_: CancellationException) {
                    // 不合作：取消被吞掉，接口照样返回一句"成品"
                }
                "迟到的成品"
            } else {
                "重新说一句"
            }
        }
        val controller = newController(this, source, scheduler = testScheduler)
        controller.setVisible(true)
        assertTrue(controller.submit(tap(), aiOn))
        runCurrent()
        assertEquals(CompanionAiPhase.THINKING, controller.state.value.phase)

        controller.stop()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals("吞掉取消也不能变成完成", CompanionAiPhase.CANCELLED, state.phase)
        assertEquals(CompanionCancelReason.USER, state.cancelReason)
        assertEquals("", state.text)
        assertFalse(state.busy)
        assertEquals(1, source.requests.size)
        assertEquals(
            "被取消的那一轮只能是一条 CANCELLED 记录",
            listOf(CompanionAiPhase.CANCELLED),
            controller.interactions.value.map { it.phase },
        )

        // 被取消那句没被用户看见，绝不能进记忆带到下一轮提示词里
        controller.setVisible(true)
        assertTrue(controller.submit(tap(), aiOn))
        advanceUntilIdle()
        assertEquals(0, source.requests[1].history.size)
        assertEquals("重新说一句", controller.state.value.text)
    }

    @Test
    fun `a late reply from the cancelled run never overwrites the new run`() = runTest {
        val source = FakeSource()
        val gate = CompletableDeferred<Unit>()
        source.onGenerate = { index ->
            if (index == 0) {
                try {
                    gate.await()
                } catch (_: CancellationException) {
                    // 不合作
                }
                "旧一轮的迟到成品"
            } else {
                "新一轮的回答"
            }
        }
        val controller = newController(this, source, scheduler = testScheduler)
        controller.setVisible(true)
        assertTrue(controller.submit(tap(), aiOn))
        runCurrent()

        controller.stop()
        runCurrent()
        assertTrue(controller.submit(tap(), aiOn))
        advanceUntilIdle()
        assertEquals("新一轮的回答", controller.state.value.text)

        // 旧一轮这时才"正常返回"：不能把新一轮的结果盖掉，也不能多出一条记录
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(CompanionAiPhase.COMPLETED, controller.state.value.phase)
        assertEquals("新一轮的回答", controller.state.value.text)
        assertEquals(2, source.requests.size)
        assertEquals(
            listOf(CompanionAiPhase.COMPLETED, CompanionAiPhase.CANCELLED),
            controller.interactions.value.map { it.phase },
        )
    }


    @Test
    fun `refreshing while busy never rewrites the model of the run in flight`() = runTest {
        val source = FakeSource(
            description = CompanionModelDescription(
                label = "model-a · A",
                source = CompanionModelMode.FOLLOW_CONVERSATION,
                origin = CompanionModelOrigin.CONVERSATION,
            ),
        )
        val gate = CompletableDeferred<Unit>()
        source.onGenerate = { _ ->
            gate.await()
            "用了 A"
        }
        val controller = newController(this, source, scheduler = testScheduler)
        controller.setVisible(true)
        assertTrue(controller.submit(tap(), aiOn))
        runCurrent()
        assertEquals("model-a · A", controller.state.value.model.label)

        // 用户在生成中换了模型：刷新不能把"这次请求用的模型"改掉
        source.description = CompanionModelDescription(
            label = "model-b · B",
            source = CompanionModelMode.FOLLOW_CONVERSATION,
            origin = CompanionModelOrigin.CONVERSATION,
        )
        controller.refreshModel()
        advanceUntilIdle()
        assertEquals("在途请求的模型栏必须还是这一轮真的用的那个", "model-a · A", controller.state.value.model.label)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("model-a · A", controller.interactions.value.first().modelLabel)

        // 这一轮结束之后，刷新就可以更新到新模型
        controller.refreshModel()
        advanceUntilIdle()
        assertEquals("model-b · B", controller.state.value.model.label)
    }

    @Test
    fun `the timeout also covers model resolution`() = runTest {
        val source = FakeSource()
        source.onDescribe = {
            delay(60_000)
            CompanionModelDescription(label = "too-late · Test")
        }
        val controller = newController(this, source, timeoutMillis = 1_000, scheduler = testScheduler)
        controller.setVisible(true)
        assertTrue(controller.submit(tap(), aiOn))
        advanceUntilIdle()

        assertEquals(CompanionAiPhase.CANCELLED, controller.state.value.phase)
        assertEquals("解析卡住也要按超时结束，不能一直挂在思考中", CompanionCancelReason.TIMEOUT, controller.state.value.cancelReason)
        assertTrue("解析没完成就不该发请求", source.requests.isEmpty())
    }

    @Test
    fun `a follow mode trigger whose conversation is gone fails loudly without any request`() = runTest {
        val source = FakeSource(
            description = CompanionModelDescription(
                source = CompanionModelMode.FOLLOW_CONVERSATION,
                failure = CompanionFailure.ModelMissing,
                origin = CompanionModelOrigin.CONVERSATION,
                conversationMissing = true,
            ),
        )
        val controller = newController(this, source, scheduler = testScheduler)
        controller.setVisible(true)
        controller.submit(tap(), aiOn)
        advanceUntilIdle()

        assertEquals(CompanionAiPhase.FAILED, controller.state.value.phase)
        assertEquals(CompanionFailure.ModelMissing, controller.state.value.failure)
        assertTrue(controller.state.value.model.conversationMissing)
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun `provider failure text is redacted, bounded and keeps the status code`() {
        val raw = "Failed to get response: 401 {\"error\":\"invalid key sk-proj-abcdef123456\",\"request_id\":\"req_9f8\"}" +
            " Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig" +
            " https://api.example.com/v1/chat?key=SECRET123&request_id=req_9f8 " + "A".repeat(300) +
            "\n\ntrace 12"

        val sanitized = sanitizeCompanionFailureMessage(raw)
        assertFalse("密钥不能透出：$sanitized", sanitized.contains("SECRET123"))
        assertFalse("sk- 形式的密钥不能透出：$sanitized", sanitized.contains("sk-proj-abcdef123456"))
        assertFalse("Bearer 令牌不能透出：$sanitized", sanitized.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue("不能把上游响应体整段搬过来：$sanitized", sanitized.length <= CompanionLimits.MAX_FAILURE_CHARS)
        assertTrue("状态码要留着排障：$sanitized", sanitized.startsWith("Failed to get response: 401"))
        assertFalse("换行要压掉：$sanitized", sanitized.contains("\n"))
    }

    @Test
    fun `empty failure text never becomes a raw exception dump`() {
        assertEquals("request failed", sanitizeCompanionFailureMessage(null))
        assertEquals("request failed", sanitizeCompanionFailureMessage("   \n  "))
    }

    @Test
    fun `follow mode decision table never sends the global model without saying so`() {
        val id = Uuid.random()
        // 有会话 ID，会话读不到：明确失败（review F1 的核心）
        assertEquals(
            CompanionFollowDecision.CONVERSATION_MISSING,
            CompanionFollowPolicy.decide(id, conversationFound = false, conversationModelFound = false, globalDefaultFound = true),
        )
        // 有会话 ID，会话读得到但它的模型算不出来：还是明确失败，不许换全局默认
        assertEquals(
            CompanionFollowDecision.CONVERSATION_MISSING,
            CompanionFollowPolicy.decide(id, conversationFound = true, conversationModelFound = false, globalDefaultFound = true),
        )
        assertEquals(
            CompanionFollowDecision.CONVERSATION_MODEL,
            CompanionFollowPolicy.decide(id, conversationFound = true, conversationModelFound = true, globalDefaultFound = true),
        )
        // 完全没有会话（预览态）：用全局默认，但这条分支必须在描述里标成 GLOBAL_DEFAULT
        assertEquals(
            CompanionFollowDecision.GLOBAL_DEFAULT_PREVIEW,
            CompanionFollowPolicy.decide(null, conversationFound = false, conversationModelFound = false, globalDefaultFound = true),
        )
        assertEquals(
            CompanionFollowDecision.MODEL_MISSING,
            CompanionFollowPolicy.decide(null, conversationFound = false, conversationModelFound = false, globalDefaultFound = false),
        )
    }

    @Test
    fun `a description with a snapshot hands the same snapshot to the request`() = runTest {
        val snapshot = CompanionModelSnapshot(
            modelId = Uuid.random(),
            modelKey = "model-a",
            providerId = Uuid.random(),
            label = "model-a · A",
        )
        val source = FakeSource(
            description = CompanionModelDescription(
                label = "model-a · A",
                source = CompanionModelMode.FOLLOW_CONVERSATION,
                origin = CompanionModelOrigin.CONVERSATION,
                snapshot = snapshot,
            ),
        )
        val controller = newController(this, source, scheduler = testScheduler)
        controller.setVisible(true)
        controller.submit(tap(), aiOn)
        advanceUntilIdle()

        assertEquals("请求必须带上这次解析出来的同一份模型快照", snapshot, source.requests.single().expectedModel)
    }
}
