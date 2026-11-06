package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.event.CompressionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 压缩实时进度：Unicode 字数、阶段、节流 + 尾刷新、结束态。
 *
 * 这些都是纯逻辑，不连 provider、不发请求；数字全部来自上报的分块，没有一处按时间估算。
 */
class CompressionProgressTest {
    private fun state(taskId: Uuid = Uuid.random(), startedAt: Long = 1_000L) = CompressionState(
        taskId = taskId,
        outcome = null,
        startedAt = startedAt,
    )

    // ---- Unicode 计数 ----

    @Test
    fun `astral characters count as one character each`() {
        val counter = UnicodeCharCounter()
        assertEquals(2, counter.append("😀😁"))
        assertEquals(2, counter.value)
    }

    @Test
    fun `surrogate pair split across chunks still counts once`() {
        val counter = UnicodeCharCounter()
        // 高代理项先到、低代理项下一块才到，不能算成两个字
        assertEquals(1, counter.append("\uD83D"))
        assertEquals(1, counter.append("\uDE00"))
        assertEquals(1, counter.value)
    }

    @Test
    fun `combining marks and zwj sequences count code points not grapheme clusters`() {
        val counter = UnicodeCharCounter()
        // e + 组合重音 = 两个 code point；家庭 emoji 是多个 code point，都按实际数量算
        counter.append("e\u0301")
        assertEquals(2, counter.value)
        counter.append("👨‍👩‍👧")
        assertEquals(7, counter.value)
    }

    // ---- 状态转换 ----

    @Test
    fun `phase moves preparing to waiting then generating and writing`() {
        val published = mutableListOf<CompressionState>()
        var now = 10_000L
        val tracker = CompressionProgressTracker(state(), minIntervalMs = 150L, now = { now }) {
            published += it
        }
        assertEquals(CompressionPhase.Preparing, tracker.snapshot().phase)

        tracker.phase(CompressionPhase.WaitingModel)
        assertEquals(CompressionPhase.WaitingModel, tracker.snapshot().phase)
        assertTrue(tracker.snapshot().isRunning)
        assertEquals(0, tracker.snapshot().generatedChars)

        tracker.onChunk("你好")
        assertEquals(CompressionPhase.Generating, tracker.snapshot().phase)
        assertEquals(2, tracker.snapshot().generatedChars)
        assertEquals(1, tracker.snapshot().chunks)

        tracker.phase(CompressionPhase.Writing)
        assertEquals(CompressionPhase.Writing, tracker.snapshot().phase)
        // 阶段切换一定会上报，不被节流吞掉
        assertEquals(CompressionPhase.Writing, published.last().phase)
    }

    @Test
    fun `repeating the same phase does not publish again`() {
        val published = mutableListOf<CompressionState>()
        val tracker = CompressionProgressTracker(state(), now = { 5_000L }) { published += it }
        tracker.phase(CompressionPhase.WaitingModel)
        val afterFirst = published.size
        tracker.phase(CompressionPhase.WaitingModel)
        assertEquals(afterFirst, published.size)
    }

    // ---- 节流与尾刷新 ----

    @Test
    fun `chunks inside the throttle window are coalesced into one trailing update`() {
        val published = mutableListOf<CompressionState>()
        var now = 1_000L
        val tracker = CompressionProgressTracker(state(), minIntervalMs = 150L, now = { now }) {
            published += it
        }
        tracker.phase(CompressionPhase.WaitingModel)
        val publishedAfterPhase = published.size

        // 同一时刻连来 20 块：一块都不额外发，不是每块重发
        repeat(20) { tracker.onChunk("ab") }
        assertEquals(publishedAfterPhase, published.size)

        // 节流窗口里再来的块继续压住
        now += 10
        tracker.onChunk("cd")
        assertEquals(publishedAfterPhase, published.size)

        // 尾刷新补发最近一次真实值：字数不能停在旧值
        tracker.flush()
        assertEquals(publishedAfterPhase + 1, published.size)
        assertEquals(44, published.last().generatedChars)
        assertEquals(22, published.last().chunks)
        assertEquals(CompressionPhase.Generating, published.last().phase)
    }

    @Test
    fun `flush without pending changes publishes nothing`() {
        val published = mutableListOf<CompressionState>()
        val tracker = CompressionProgressTracker(state(), now = { 2_000L }) { published += it }
        tracker.onChunk("x")
        val size = published.size
        tracker.flush()
        assertEquals(size, published.size)
    }

    @Test
    fun `published counters only grow and never invent progress before the first chunk`() {
        val published = mutableListOf<CompressionState>()
        var now = 1_000L
        val tracker = CompressionProgressTracker(state(), minIntervalMs = 0L, now = { now }) {
            published += it
        }
        tracker.phase(CompressionPhase.WaitingModel)
        assertFalse(published.last().hasGeneratedText)
        assertEquals(0, published.last().generatedChars)

        tracker.onChunk("a")
        now += 100
        tracker.onChunk("bc")
        assertEquals(3, published.last().generatedChars)
        assertEquals(2, published.last().chunks)
        assertTrue(published.all { it.generatedChars <= 3 })
    }

    @Test
    fun `preview is bounded and never a full copy of the summary`() {
        val published = mutableListOf<CompressionState>()
        val tracker = CompressionProgressTracker(state(), minIntervalMs = 0L, now = { 1_000L }) {
            published += it
        }
        repeat(50) { tracker.onChunk("0123456789") }
        assertEquals(COMPRESSION_PREVIEW_LIMIT, tracker.snapshot().summaryPreview.length)
        assertEquals(500, tracker.snapshot().generatedChars)
    }

    // ---- 结束态 ----

    @Test
    fun `finishing keeps the reported phase and counters`() {
        val taskId = Uuid.random()
        val conversationId = Uuid.random()
        val running = state(taskId = taskId).copy(
            phase = CompressionPhase.Writing,
            generatedChars = 1234,
            chunks = 56,
            summaryPreview = "摘要开头",
        )
        val finished = mapOf(conversationId to running).finishCompression(
            conversationId = conversationId,
            taskId = taskId,
            outcome = CompressionOutcome.Succeeded,
            message = null,
            now = 9_000L,
        )
        val state = finished.getValue(conversationId)
        assertEquals(CompressionOutcome.Succeeded, state.outcome)
        assertEquals(CompressionPhase.Writing, state.phase)
        assertEquals(1234, state.generatedChars)
        assertEquals(56, state.chunks)
        assertEquals("摘要开头", state.summaryPreview)
        assertEquals(1_000L, state.startedAt)
        assertEquals(9_000L, state.finishedAt)
        assertFalse(state.isRunning)
    }

    @Test
    fun `a late task cannot finish a newer task state`() {
        val conversationId = Uuid.random()
        val newTask = Uuid.random()
        val oldTask = Uuid.random()
        val current = mapOf(conversationId to state(taskId = newTask).copy(
            phase = CompressionPhase.Generating,
            generatedChars = 88,
        ))
        val afterLateFinish = current.finishCompression(
            conversationId = conversationId,
            taskId = oldTask,
            outcome = CompressionOutcome.Cancelled,
            message = null,
            now = 5_000L,
        )
        assertSame(current, afterLateFinish)
        assertTrue(afterLateFinish.getValue(conversationId).isRunning)
        assertEquals(88, afterLateFinish.getValue(conversationId).generatedChars)
    }

    @Test
    fun `cancelled outcome is terminal and keeps elapsed start`() {
        val conversationId = Uuid.random()
        val taskId = Uuid.random()
        val finished = mapOf(conversationId to state(taskId = taskId)).finishCompression(
            conversationId = conversationId,
            taskId = taskId,
            outcome = CompressionOutcome.Cancelled,
            message = null,
            now = 4_000L,
        )
        val state = finished.getValue(conversationId)
        assertEquals(CompressionOutcome.Cancelled, state.outcome)
        assertFalse(state.isRunning)
        assertEquals(1_000L, state.startedAt)
        assertEquals(4_000L, state.finishedAt)
    }

    @Test
    fun `automatic compression state is not cancellable`() {
        val auto = state().copy(cancellable = false)
        val manual = state()
        assertFalse(auto.cancellable)
        assertTrue(manual.cancellable)
        // 自动压缩同样有真实阶段/字数
        assertTrue(auto.copy(phase = CompressionPhase.Generating, generatedChars = 10).hasGeneratedText)
    }
}
