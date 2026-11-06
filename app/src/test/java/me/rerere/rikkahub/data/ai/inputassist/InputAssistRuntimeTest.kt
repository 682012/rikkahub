package me.rerere.rikkahub.data.ai.inputassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import kotlin.uuid.Uuid

/**
 * 这一轮用哪条文本登记处 + 请求改写的判定（纯逻辑部分）。
 *
 * 只测不依赖 ai 模块的语义：登记/清理、开关全关时什么都不改、缓存命中与失败都不会被当成可替换文本，
 * 以及**代次**：一轮结束后（会话删除、换助手、或者干脆被下一轮顶掉）迟到回来的优化结果不能再登记。
 */
class InputAssistRuntimeTest {
    @Before
    fun resetBefore() = InputAssistRuntime.clearAll()

    @After
    fun resetAfter() = InputAssistRuntime.clearAll()

    private fun state(
        conversationId: Uuid = Uuid.random(),
        messageId: Uuid = Uuid.random(),
        status: InputOptimizationStatus = InputOptimizationStatus.OPTIMIZED,
        text: String? = "优化后的要求",
        selfCheck: Boolean = false,
    ) = InputAssistTurnState(
        conversationId = conversationId,
        messageId = messageId,
        assistantId = Uuid.random(),
        optimization = InputOptimizationOutcome(status, text, "fast-model"),
        selfCheck = selfCheck,
    )

    /** 和 `prepareTurn`/`stageSelfCheck` 一样：先领代次，再用这个代次登记。 */
    private fun stageAsCurrentTurn(state: InputAssistTurnState): Boolean =
        InputAssistRuntime.stageIfCurrent(InputAssistRuntime.beginTurn(state.conversationId), state)

    @Test
    fun `staged state is scoped to its conversation and cleared at turn end`() {
        val conversation = Uuid.random()
        val other = Uuid.random()
        assertTrue(stageAsCurrentTurn(state(conversationId = conversation)))

        assertTrue(InputAssistRuntime.stateFor(conversation) != null)
        assertNull(InputAssistRuntime.stateFor(other))

        InputAssistRuntime.clear(conversation)
        assertNull(InputAssistRuntime.stateFor(conversation))
    }

    @Test
    fun `a new turn replaces the previous staged text`() {
        val conversation = Uuid.random()
        val first = Uuid.random()
        val second = Uuid.random()
        stageAsCurrentTurn(state(conversationId = conversation, messageId = first, text = "第一条"))
        stageAsCurrentTurn(state(conversationId = conversation, messageId = second, text = "第二条"))

        val staged = InputAssistRuntime.stateFor(conversation)
        assertEquals(second, staged?.messageId)
        assertEquals("第二条", staged?.optimizedText)
    }

    @Test
    fun `a slow earlier turn cannot overwrite the turn that is now sending`() {
        // 第 1 轮的优化请求还在飞，用户已经发了第 2 轮：第 1 轮回来时只配得到 false，
        // 请求组装读到的必须还是第 2 轮的登记（否则就是把旧消息的优化版贴到新消息上）。
        val conversation = Uuid.random()
        val firstMessage = Uuid.random()
        val secondMessage = Uuid.random()
        val firstGeneration = InputAssistRuntime.beginTurn(conversation)
        val secondGeneration = InputAssistRuntime.beginTurn(conversation)
        assertTrue(
            InputAssistRuntime.stageIfCurrent(
                secondGeneration,
                state(conversationId = conversation, messageId = secondMessage, text = "第二轮的优化版"),
            ),
        )

        assertFalse(
            InputAssistRuntime.stageIfCurrent(
                firstGeneration,
                state(conversationId = conversation, messageId = firstMessage, text = "第一轮的优化版"),
            ),
        )
        val staged = InputAssistRuntime.stateFor(conversation)
        assertEquals(secondMessage, staged?.messageId)
        assertEquals("第二轮的优化版", staged?.optimizedText)
    }

    @Test
    fun `a late result cannot come back after the turn was torn down`() {
        // 会话被删除 / 换了助手 / 开关被关掉之后这一轮被收掉：优化请求这时才回来，
        // 不许它把登记重新写回去（否则界面和请求都可能再看到那个早该失效的优化版）。
        val conversation = Uuid.random()
        val generation = InputAssistRuntime.beginTurn(conversation)
        InputAssistRuntime.clear(conversation)

        assertFalse(
            InputAssistRuntime.stageIfCurrent(
                generation,
                state(conversationId = conversation, text = "迟到的优化版"),
            ),
        )
        assertNull(InputAssistRuntime.stateFor(conversation))
        // 也没有凭空多出一条要等下一轮才清的登记
        assertNull(InputAssistRuntime.clear(conversation))
    }

    @Test
    fun `a self check tail from a dead turn is refused the same way`() {
        val conversation = Uuid.random()
        val message = Uuid.random()
        val generation = InputAssistRuntime.beginTurn(conversation)
        InputAssistRuntime.clear(conversation)

        assertFalse(
            InputAssistRuntime.stageIfCurrent(
                generation,
                state(
                    conversationId = conversation,
                    messageId = message,
                    status = InputOptimizationStatus.NOT_REQUESTED,
                    text = null,
                    selfCheck = true,
                ),
            ),
        )
        assertNull(InputAssistRuntime.stateFor(conversation))
    }

    @Test
    fun `generations belong to one conversation only`() {
        // 代次是全程序单调的，但登记权只对本会话有效：拿 A 的代次去登记 B 必须失败，
        // 免得一个会话的迟到结果跑到另一个会话的请求里。
        val a = Uuid.random()
        val b = Uuid.random()
        val generationA = InputAssistRuntime.beginTurn(a)

        assertTrue(InputAssistRuntime.stageIfCurrent(generationA, state(conversationId = a)))
        assertFalse(InputAssistRuntime.stageIfCurrent(generationA, state(conversationId = b)))
        assertNull(InputAssistRuntime.stateFor(b))

        val generationB = InputAssistRuntime.beginTurn(b)
        assertTrue(InputAssistRuntime.stageIfCurrent(generationB, state(conversationId = b)))
        // A 的登记没有被 B 动手
        assertEquals(a, InputAssistRuntime.stateFor(a)?.conversationId)
        assertEquals(b, InputAssistRuntime.stateFor(b)?.conversationId)
    }

    @Test
    fun `clearAll drops every generation so a stale one can never stage again`() {
        // clearAll 是"所有会话一次性作废"的场合（登出、进程级重置、开关整体关掉）：
        // 登记表清空的同时代次也必须作废，否则还在飞的 prepareTurn 会拿旧代次把登记写回
        // 一个已经被放弃的会话；而且新开的轮次必须拿到新号，绝不能把旧号码再用一遍。
        val a = Uuid.random()
        val b = Uuid.random()
        val generationA = InputAssistRuntime.beginTurn(a)
        val generationB = InputAssistRuntime.beginTurn(b)
        assertTrue(InputAssistRuntime.stageIfCurrent(generationA, state(conversationId = a)))
        assertTrue(InputAssistRuntime.stageIfCurrent(generationB, state(conversationId = b)))
        assertEquals(2, InputAssistRuntime.size())

        InputAssistRuntime.clearAll()

        assertEquals(0, InputAssistRuntime.size())
        assertNull(InputAssistRuntime.stateFor(a))
        assertNull(InputAssistRuntime.stateFor(b))
        assertFalse(InputAssistRuntime.stageIfCurrent(generationA, state(conversationId = a)))
        assertFalse(InputAssistRuntime.stageIfCurrent(generationB, state(conversationId = b)))
        assertNull(InputAssistRuntime.stateFor(a))

        // 代次是全局单调的：clearAll 之后新领的号一定比旧的都大，不会和旧的撞号
        val next = InputAssistRuntime.beginTurn(a)
        assertTrue(next > generationB)
        assertTrue(InputAssistRuntime.stageIfCurrent(next, state(conversationId = a, text = "清空之后的新一轮")))
        assertEquals("清空之后的新一轮", InputAssistRuntime.stateFor(a)?.optimizedText)
        InputAssistRuntime.clearAll()
    }

    @Test
    fun `only optimized or cached text may change the request`() {
        assertEquals("优化后的要求", state().optimizedText)
        assertEquals(
            "缓存里的优化版",
            state(status = InputOptimizationStatus.CACHED, text = "缓存里的优化版").optimizedText,
        )
        assertNull(state(status = InputOptimizationStatus.FAILED, text = "半截结果").optimizedText)
        assertNull(state(status = InputOptimizationStatus.UNCHANGED, text = "原话").optimizedText)
        assertNull(state(status = InputOptimizationStatus.NOT_ELIGIBLE, text = null).optimizedText)
        assertNull(state(status = InputOptimizationStatus.NOT_REQUESTED, text = null).optimizedText)
    }

    @Test
    fun `disabled chat does not change the request at all`() {
        val idle = InputAssistTurnState(
            conversationId = Uuid.random(),
            messageId = Uuid.random(),
            assistantId = Uuid.random(),
        )
        assertFalse(idle.changesRequest)
        assertFalse(state(status = InputOptimizationStatus.FAILED, text = null).changesRequest)
    }

    @Test
    fun `self check alone changes the request even without a rewrite`() {
        val selfCheck = state(status = InputOptimizationStatus.NOT_REQUESTED, text = null, selfCheck = true)
        assertTrue(selfCheck.changesRequest)
        assertNull(selfCheck.optimizedText)
    }

    @Test
    fun `a cached failure is still a failure`() {
        // 缓存命中的失败/取消不是成功：不许因为"这条来自缓存"就把它当可替换的优化版
        val cachedFailure = InputAssistTurnState(
            conversationId = Uuid.random(),
            messageId = Uuid.random(),
            assistantId = Uuid.random(),
            optimization = InputOptimizationOutcome(
                status = InputOptimizationStatus.FAILED,
                optimizedText = "半截结果",
                failureReason = "timeout",
                cached = true,
            ),
        )
        assertNull(cachedFailure.optimizedText)
        assertFalse(cachedFailure.changesRequest)
    }

    @Test
    fun `a cached rewrite is still a rewrite`() {
        val cached = InputAssistTurnState(
            conversationId = Uuid.random(),
            messageId = Uuid.random(),
            assistantId = Uuid.random(),
            optimization = InputOptimizationOutcome(
                status = InputOptimizationStatus.CACHED,
                optimizedText = "缓存优化版",
                cached = true,
            ),
        )
        assertEquals("缓存优化版", cached.optimizedText)
        assertTrue(cached.changesRequest)
    }
    @Test
    fun `turning optimization off keeps self check but drops the rewrite immediately`() {
        val c = Uuid.random()
        stageAsCurrentTurn(state(conversationId = c, selfCheck = true))
        InputAssistRuntime.updateOptions(c, optimize = false, selfCheck = true)
        assertNull(InputAssistRuntime.stateFor(c)?.optimizedText)
        assertTrue(InputAssistRuntime.stateFor(c)?.selfCheck == true)
    }

    @Test
    fun `turning self check off keeps optimization without the self check tail`() {
        val c = Uuid.random()
        stageAsCurrentTurn(state(conversationId = c, selfCheck = true))
        InputAssistRuntime.updateOptions(c, optimize = true, selfCheck = false)
        assertEquals("优化后的要求", InputAssistRuntime.stateFor(c)?.optimizedText)
        assertFalse(InputAssistRuntime.stateFor(c)?.selfCheck == true)
    }

    @Test
    fun `switch changes invalidate an in flight preparation even without a staged result`() {
        val c = Uuid.random()
        val generation = InputAssistRuntime.beginTurn(c)
        InputAssistRuntime.updateOptions(c, optimize = false, selfCheck = true)
        assertFalse(InputAssistRuntime.stageIfCurrent(generation, state(conversationId = c)))
        assertNull(InputAssistRuntime.stateFor(c))
    }

}
