package me.rerere.ai.agent

import kotlinx.serialization.json.Json
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.sum
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r10 计时口径的对抗测试。
 *
 * 与其他计时测试的区别：这里所有「一步」的记录都是拿真实 [GenerationTimer] 走一遍时间轴造出来的，
 * 不手写字段组合，因此断言的是生产上真正会出现的形状；手写形状只用于旧记录（r9 JSON）和损坏输入。
 *
 * 核心不变量（本文件反复断言，改实现时不能破）：
 * 1. 分子 [GenerationTiming.measuredTokens] 只能来自被测到的那几步，恒有 measuredTokens <= outputTokens；
 * 2. speed 只能由 measuredTokens / （被测到的那几步的窗口和）算出来，不能由 outputTokens 或全部步骤的窗口算；
 * 3. 未测步骤数与 skippedReasons 的计数总和相等，且不出现负计数；
 * 4. 测不到时返回 null，而不是猜一个数（旧的部分计时记录没有分子依据）。
 */
class GenerationTimingAdversarialR10Test {
    private class AdvClock(var nanos: Long = 0L) {
        fun nanoTime(): Long = nanos
        fun advanceMs(ms: Long) {
            nanos += ms * 1_000_000L
        }

        fun advanceNs(ns: Long) {
            nanos += ns
        }
    }

    /**
     * 造「一步」的记录：先 50ms TTFT，再连发 [packets] 个内容包，首个内容包到最后一个是 [windowMs]。
     * [tokens] 为 null 模拟上游没报 usage。
     */
    private fun timingStep(
        windowMs: Long,
        tokens: Int?,
        packets: Int = 2,
        serverTool: Boolean = false,
        streaming: Boolean = true,
    ): GenerationTiming {
        val clock = AdvClock()
        val timer = GenerationTimer(clock::nanoTime)
        clock.advanceMs(50)
        if (streaming && packets > 0) {
            timer.onChunk(StreamChunk.TextDelta("t", "a"))
            for (index in 1 until packets) {
                if (index == 1) clock.advanceMs(windowMs)
                timer.onChunk(StreamChunk.TextDelta("t", "b"))
            }
        }
        if (serverTool) timer.onChunk(StreamChunk.ServerToolStart(id = "srv-1", toolName = "search"))
        return timer.finish(tokens, streaming)
    }

    /** 纳秒级窗口，用来卡 1_000_000 整除截断的边界。 */
    private fun timingStepNs(windowNs: Long, tokens: Int): GenerationTiming {
        val clock = AdvClock()
        val timer = GenerationTimer(clock::nanoTime)
        timer.onChunk(StreamChunk.TextDelta("t", "a"))
        clock.advanceNs(windowNs)
        timer.onChunk(StreamChunk.TextDelta("t", "b"))
        return timer.finish(tokens)
    }

    private fun assertConsistent(timing: GenerationTiming) {
        assertTrue(
            "measuredSteps ${timing.measuredSteps} 超过 totalSteps ${timing.totalSteps}: $timing",
            timing.measuredSteps <= timing.totalSteps,
        )
        assertTrue(
            "分子 ${timing.measuredTokens} 超过总 token ${timing.outputTokens}，未测步骤的 token 混进分子了: $timing",
            timing.measuredTokens <= timing.outputTokens,
        )
        assertTrue("skippedSteps 不能为负: $timing", timing.skippedSteps >= 0)
        assertTrue("原因计数不能为负: $timing", timing.skippedReasons.values.all { it > 0 })
        assertEquals(
            "未测步骤数必须等于原因计数总和: $timing",
            timing.skippedSteps,
            timing.skippedReasons.values.sum(),
        )
        val speed = timing.speedTokensPerSecond
        if (speed != null) {
            assertTrue("有速度就至少要有一整个测到的步骤: $timing", timing.measuredSteps > 0)
            assertTrue("有速度就必须有可信窗口: $timing", (timing.generationMs ?: 0L) >= GenerationTiming.MIN_WINDOW_MS)
            assertTrue("有速度就必须有分子: $timing", timing.measuredTokens > 0L)
            // 唯一合法算法：分子与分母同源，按窗口加权
            val expected = timing.measuredTokens * 1000.0 / timing.generationMs!!
            assertEquals("速度必须等于 measuredTokens/window: $timing", expected, speed, 1e-6)
        }
    }

    @Test
    fun `unmeasured step tokens never enter the numerator`() {
        val measured = timingStep(windowMs = 400, tokens = 40)
        val bufferedAllAtOnce = timingStep(windowMs = 0, tokens = 1000, packets = 1)

        assertEquals(listOf(GenerationSkipReason.SINGLE_PACKET to 1), bufferedAllAtOnce.skippedReasons.toList())
        val merged = measured.add(bufferedAllAtOnce)

        // 若拿 outputTokens 当分子，这里会是 1040/0.4s = 2600 t/s；只用已测步骤才是 100 t/s
        assertEquals(40L, merged.measuredTokens)
        assertEquals(1040L, merged.outputTokens)
        assertEquals(400L, merged.generationMs)
        assertEquals(100.0, merged.speedTokensPerSecond!!, 1e-6)
        assertEquals(1, merged.measuredSteps)
        assertEquals(2, merged.totalSteps)
        assertTrue(merged.isPartial)
        assertConsistent(merged)
    }

    @Test
    fun `rate across steps is window weighted not step averaged and not total token over total window`() {
        val fast = timingStep(windowMs = 300, tokens = 60) // 200 t/s
        val slow = timingStep(windowMs = 1_000, tokens = 100) // 100 t/s
        val unmeasured = timingStep(windowMs = 0, tokens = 70, packets = 1)

        val merged = fast.add(slow).add(unmeasured)

        // 窗口加权：160 tok / 1300ms。既不是步数平均 (200+100)/2 = 150，
        // 也不是把未测步骤 token 混进分子的 230/1.3s = 176.9
        assertEquals(1300L, merged.generationMs)
        assertEquals(160L, merged.measuredTokens)
        assertEquals(160_000.0 / 1300.0, merged.speedTokensPerSecond!!, 1e-9)
        assertEquals(230L, merged.outputTokens)
        assertConsistent(merged)
    }

    @Test
    fun `merging is order independent for the reported rate and coverage`() {
        val steps = listOf(
            timingStep(windowMs = 300, tokens = 60),
            timingStep(windowMs = 0, tokens = 500, packets = 1),
            timingStep(windowMs = 1_000, tokens = 100),
            // r9 那种「一步测到、一步没测到」的旧累计记录：没有分子依据
            GenerationTiming(generationMs = 2_000, requestMs = 2_600, outputTokens = 900, measuredSteps = 1, totalSteps = 2),
        )
        val orders = listOf(
            listOf(0, 1, 2, 3),
            listOf(3, 2, 1, 0),
            listOf(1, 3, 0, 2),
            listOf(2, 0, 3, 1),
            listOf(0, 3, 1, 2),
            listOf(3, 1, 2, 0),
        )

        val merged = orders.map { order -> order.map { steps[it] }.reduce(GenerationTiming::add) }
        merged.forEach { assertConsistent(it) }
        val first = merged.first()
        assertEquals(2, first.measuredSteps)
        assertEquals(5, first.totalSteps)
        assertEquals(1_300L, first.generationMs)
        assertEquals(160L, first.measuredOutputTokens)
        assertEquals(mapOf(GenerationSkipReason.SINGLE_PACKET to 1, GenerationSkipReason.LEGACY_UNKNOWN to 2), first.skippedReasons)
        assertEquals(1_560L, first.outputTokens)

        merged.drop(1).forEach {
            assertEquals(first.speedTokensPerSecond!!, it.speedTokensPerSecond!!, 1e-9)
            assertEquals(first.generationMs, it.generationMs)
            assertEquals(first.measuredOutputTokens, it.measuredOutputTokens)
            assertEquals(first.measuredSteps, it.measuredSteps)
            assertEquals(first.totalSteps, it.totalSteps)
            assertEquals(first.skippedReasons, it.skippedReasons)
        }
    }

    @Test
    fun `every unmeasurable shape keeps a reason and contributes no time`() {
        // 覆盖全部原因码，全部由真实计时器产生
        val measured = timingStep(windowMs = 300, tokens = 60)
        val singlePacket = timingStep(windowMs = 0, tokens = 500, packets = 1)
        val shortWindow = timingStep(windowMs = 199, tokens = 10)
        val serverWait = timingStep(windowMs = 300, tokens = 30, serverTool = true)
        val noTokens = timingStep(windowMs = 300, tokens = null)
        val nonStream = timingStep(windowMs = 0, tokens = 40, streaming = false)

        assertEquals(mapOf(GenerationSkipReason.SINGLE_PACKET to 1), singlePacket.skippedReasons)
        assertEquals(mapOf(GenerationSkipReason.SHORT_WINDOW to 1), shortWindow.skippedReasons)
        assertEquals(mapOf(GenerationSkipReason.SERVER_WAIT to 1), serverWait.skippedReasons)
        assertEquals(mapOf(GenerationSkipReason.NO_OUTPUT_TOKENS to 1), noTokens.skippedReasons)
        assertEquals(mapOf(GenerationSkipReason.NON_STREAM to 1), nonStream.skippedReasons)

        var merged = measured
        listOf(singlePacket, shortWindow, serverWait, noTokens, nonStream).forEach {
            val before = merged.speedTokensPerSecond
            merged = merged.add(it)
            // 加一步测不到的不许改变已经测到的速率
            assertEquals("加入 $it 后速率被改变了", before!!, merged.speedTokensPerSecond!!, 1e-9)
            assertConsistent(merged)
        }

        assertEquals(300L, merged.generationMs)
        assertEquals(60L, merged.measuredTokens)
        assertEquals(640L, merged.outputTokens)
        assertEquals(1, merged.measuredSteps)
        assertEquals(6, merged.totalSteps)
        assertEquals(5, merged.skippedSteps)
        assertEquals(200.0, merged.speedTokensPerSecond!!, 1e-6)
        assertEquals(
            mapOf(
                GenerationSkipReason.SINGLE_PACKET to 1,
                GenerationSkipReason.SHORT_WINDOW to 1,
                GenerationSkipReason.SERVER_WAIT to 1,
                GenerationSkipReason.NO_OUTPUT_TOKENS to 1,
                GenerationSkipReason.NON_STREAM to 1,
            ),
            merged.skippedReasons,
        )
    }

    @Test
    fun `short bursts do not leak into a measured continuation and do not add up on their own`() {
        // 199ms 的窗口太短，单独不给数
        val burst = timingStepNs(windowNs = 199_999_999L, tokens = 10)
        assertNull(burst.generationMs)
        assertEquals(mapOf(GenerationSkipReason.SHORT_WINDOW to 1), burst.skippedReasons)

        // 连续两个短突发合并后仍然不给数：宁可没有，也不拿噪声当速率
        val twoBursts = burst.add(timingStepNs(windowNs = 199_000_000L, tokens = 10))
        assertNull(twoBursts.speedTokensPerSecond)
        assertEquals(0L, twoBursts.measuredTokens)
        assertEquals(mapOf(GenerationSkipReason.SHORT_WINDOW to 2), twoBursts.skippedReasons)
        assertConsistent(twoBursts)

        // 短突发贴在可测步骤后面时，只降低不了那一步的速率，也不会被它的 token 抬高
        val measured = timingStep(windowMs = 400, tokens = 40)
        val merged = measured.add(burst)
        assertEquals(400L, merged.generationMs)
        assertEquals(40L, merged.measuredTokens)
        assertEquals(100.0, merged.speedTokensPerSecond!!, 1e-6)
        assertConsistent(merged)
    }

    @Test
    fun `window boundary at 200ms is exact and truncation never inflates a rate`() {
        val justUnder = timingStepNs(windowNs = 200_000_000L - 1L, tokens = 20)
        assertNull(justUnder.speedTokensPerSecond)
        assertEquals(mapOf(GenerationSkipReason.SHORT_WINDOW to 1), justUnder.skippedReasons)

        val exactly = timingStepNs(windowNs = 200_000_000L, tokens = 20)
        assertEquals(200L, exactly.generationMs)
        assertEquals(100.0, exactly.speedTokensPerSecond!!, 1e-6)

        // 两个包落在同一毫秒：窗口截断成 0，不能除以 0，也不能当 0ms 无限大
        val sameMillisecond = timingStepNs(windowNs = 900_000L, tokens = 20)
        assertEquals(0L, sameMillisecond.generationMs ?: 0L)
        assertNull(sameMillisecond.speedTokensPerSecond)
        assertEquals(mapOf(GenerationSkipReason.SHORT_WINDOW to 1), sameMillisecond.skippedReasons)
    }

    @Test
    fun `joined provider requests are excluded instead of turning a pause into slow generation`() {
        // 复刻 ClaudeProvider 的 pause_turn 合并：每次 pass 结束都发一条累计 usage + 本次请求 usage
        val passOne = TokenUsage(promptTokens = 100, completionTokens = 20)
        val passTwo = TokenUsage(promptTokens = 140, completionTokens = 30)
        val clock = AdvClock()
        val timer = GenerationTimer(clock::nanoTime)
        clock.advanceMs(50)
        timer.onChunk(StreamChunk.TextDelta("t", "a"))
        clock.advanceMs(300)
        timer.onChunk(StreamChunk.TextDelta("t", "b"))
        timer.onChunk(StreamChunk.Usage(passOne, completeSnapshot = true, lastRequestUsage = passOne))
        // 服务端工具/续写期间只过去了时间，没有内容包
        clock.advanceMs(4_000)
        timer.onChunk(StreamChunk.TextDelta("t", "c"))
        clock.advanceMs(300)
        timer.onChunk(StreamChunk.TextDelta("t", "d"))
        timer.onChunk(
            StreamChunk.Usage(passOne.sum(passTwo)!!, completeSnapshot = true, lastRequestUsage = passTwo)
        )
        val joined = timer.finish(outputTokens = 50, streaming = true)

        assertEquals(mapOf(GenerationSkipReason.SERVER_WAIT to 1), joined.skippedReasons)
        assertNull(joined.generationMs)
        assertEquals(0L, joined.measuredOutputTokens)
        assertEquals(50L, joined.outputTokens)
        assertNull(joined.speedTokensPerSecond)
        assertConsistent(joined)

        // 4.6s 的窗口里夹着 4s 服务端等待：如果把这步混进去，速率会被算成 50/4.6s
        val measured = timingStep(windowMs = 500, tokens = 50)
        val merged = measured.add(joined)
        assertEquals(500L, merged.generationMs)
        assertEquals(50L, merged.measuredTokens)
        assertEquals(100.0, merged.speedTokensPerSecond!!, 1e-6)
        assertEquals(100L, merged.outputTokens)
        assertConsistent(merged)
    }

    @Test
    fun `repeated usage snapshots inside one request do not look like a joined request`() {
        // 反例保护：单次请求里连发多条 usage 快照（lastRequestUsage 与累计值相同）不能被误判成服务端等待
        val usage = TokenUsage(promptTokens = 10, completionTokens = 20)
        val clock = AdvClock()
        val timer = GenerationTimer(clock::nanoTime)
        clock.advanceMs(50)
        timer.onChunk(StreamChunk.TextDelta("t", "a"))
        clock.advanceMs(300)
        timer.onChunk(StreamChunk.TextDelta("t", "b"))
        timer.onChunk(StreamChunk.Usage(usage))
        timer.onChunk(StreamChunk.Usage(usage, completeSnapshot = true, lastRequestUsage = usage))
        val timing = timer.finish(outputTokens = 20, streaming = true)

        assertTrue("单次请求的快照不该被当成服务端等待: $timing", timing.skippedReasons.isEmpty())
        assertEquals(300L, timing.generationMs)
        assertEquals(20L, timing.measuredOutputTokens)
        assertEquals(200.0 / 3.0, timing.speedTokensPerSecond!!, 1e-9)
        assertConsistent(timing)
    }

    @Test
    fun `finish during streaming is read only so previews cannot inflate the window`() {
        // AgentLoop 每收到一个包就调一次 finish() 画预览：finish 不能改内部状态，也不能把墙钟算进窗口
        val clock = AdvClock()
        val timer = GenerationTimer(clock::nanoTime)
        clock.advanceMs(50)
        timer.onChunk(StreamChunk.TextDelta("t", "a"))
        clock.advanceMs(300)
        timer.onChunk(StreamChunk.TextDelta("t", "b"))

        val first = timer.finish(20)
        clock.advanceMs(5_000)
        val later = timer.finish(20)

        assertEquals(300L, first.generationMs)
        assertEquals(300L, later.generationMs)
        assertEquals(first.measuredOutputTokens, later.measuredOutputTokens)
        assertEquals(50L, first.firstContentMs)
        assertEquals(50L, later.firstContentMs)
        // requestMs 反映墙钟（预览期间本来就会涨），但不能混进生成窗口
        assertTrue(later.requestMs > first.requestMs)
    }

    @Test
    fun `r9 json records keep their old rate and partial ones are never guessed`() {
        val json = Json { ignoreUnknownKeys = true }

        // r9 写下的记录没有 measuredOutputTokens / skippedReasons
        val full = json.decodeFromString(
            GenerationTiming.serializer(),
            """{"firstContentMs":120,"generationMs":2000,"requestMs":2600,"outputTokens":100,"measuredSteps":1,"totalSteps":1}""",
        )
        assertEquals(0L, full.measuredOutputTokens)
        assertTrue(full.skippedReasons.isEmpty())
        assertEquals(100L, full.measuredTokens)
        assertEquals(50.0, full.speedTokensPerSecond!!, 1e-6)
        assertConsistent(full)

        // r9 的部分覆盖记录：既算不出分子，也不能拿 outputTokens 去配 generationMs
        val partial = json.decodeFromString(
            GenerationTiming.serializer(),
            """{"generationMs":2000,"requestMs":2600,"outputTokens":900,"measuredSteps":1,"totalSteps":2}""",
        )
        assertEquals(0L, partial.measuredTokens)
        assertNull(partial.speedTokensPerSecond)

        // 续写一步后，旧的 2000ms/900tok 不能进分母或分子
        val merged = partial.add(timingStep(windowMs = 500, tokens = 25))
        assertEquals(500L, merged.generationMs)
        assertEquals(25L, merged.measuredOutputTokens)
        assertEquals(50.0, merged.speedTokensPerSecond!!, 1e-6)
        assertEquals(3, merged.totalSteps)
        assertEquals(1, merged.measuredSteps)
        assertEquals(mapOf(GenerationSkipReason.LEGACY_UNKNOWN to 2), merged.skippedReasons)
        assertEquals(925L, merged.outputTokens)
        // 旧记录没首包时间，已知的首包时间不能被它抹掉
        assertEquals(50L, merged.firstContentMs)
        assertConsistent(merged)
    }

    @Test
    fun `negative counts from storage degrade to no evidence instead of a negative rate`() {
        assertNull(GenerationTiming(generationMs = 1_000, outputTokens = -50, measuredSteps = 1, totalSteps = 1).speedTokensPerSecond)
        assertNull(
            GenerationTiming(generationMs = 1_000, measuredOutputTokens = 50, measuredSteps = -5, totalSteps = 1)
                .speedTokensPerSecond
        )
        val negativeTotals = GenerationTiming(
            generationMs = 1_000,
            outputTokens = 50,
            measuredOutputTokens = 50,
            measuredSteps = 1,
            totalSteps = -3,
        )
        assertNull(negativeTotals.speedTokensPerSecond)
        assertEquals(0, negativeTotals.skippedSteps)

        val good = timingStep(windowMs = 500, tokens = 25)
        val merged = good.add(negativeTotals)
        assertEquals(1, merged.totalSteps)
        assertEquals(1, merged.measuredSteps)
        assertEquals(0, merged.skippedSteps)
        assertEquals(50.0, merged.speedTokensPerSecond!!, 1e-6)
        assertTrue("负计数不能被当成分组原因传下去: ${merged.skippedReasons}", merged.skippedReasons.isEmpty())

        // 反向合并：坏记录在左边也不许污染
        val reversed = negativeTotals.add(good)
        assertEquals(50.0, reversed.speedTokensPerSecond!!, 1e-6)
        assertEquals(1, reversed.totalSteps)
        assertConsistent(reversed)
    }

    @Test
    fun `counter overflow fails safe to no rate instead of a bogus one`() {
        val huge = GenerationTiming(
            generationMs = 1_000,
            outputTokens = Long.MAX_VALUE,
            measuredOutputTokens = Long.MAX_VALUE,
            measuredSteps = 1,
            totalSteps = 1,
        )
        assertNotNull(huge.speedTokensPerSecond)
        val mergedTokens = huge.add(huge)
        // Long 溢出被 coerceAtLeast 压回 0：没有数，但也不会给出负数或 Infinity
        assertNull(mergedTokens.speedTokensPerSecond)
        assertEquals(0L, mergedTokens.outputTokens)
        assertTrue(mergedTokens.measuredTokens <= mergedTokens.outputTokens)

        val manySteps = GenerationTiming(
            generationMs = 1_000,
            outputTokens = 50,
            measuredOutputTokens = 50,
            measuredSteps = 1,
            totalSteps = Int.MAX_VALUE,
        )
        assertNotNull(manySteps.speedTokensPerSecond)
        val mergedSteps = manySteps.add(manySteps)
        assertNull("totalSteps 溢出后不能给出速度", mergedSteps.speedTokensPerSecond)
        assertTrue("totalSteps 溢出后 skippedSteps 不能为负", mergedSteps.skippedSteps >= 0)
        assertTrue("totalSteps 溢出后不能变成正数假象", mergedSteps.totalSteps <= 0)
    }

    @Test
    fun `unexplained missing steps are labelled instead of being counted as measured`() {
        // 老消息有 stepCount 但没有计时记录：这些步只能算「不知道」，不能算已测
        val legacyAggregate = GenerationTiming(
            totalSteps = 3,
            skippedReasons = mapOf(GenerationSkipReason.LEGACY_UNKNOWN to 3),
        )
        val timing = timingStep(windowMs = 400, tokens = 40)
        val merged = legacyAggregate.add(timing)

        assertEquals(4, merged.totalSteps)
        assertEquals(1, merged.measuredSteps)
        assertEquals(3, merged.skippedSteps)
        assertEquals(mapOf(GenerationSkipReason.LEGACY_UNKNOWN to 3), merged.skippedReasons)
        assertEquals(100.0, merged.speedTokensPerSecond!!, 1e-6)
        assertConsistent(merged)

        // 没有任何原因说明的缺测步骤，合并时补上 legacy_unknown，不能静默当作已测
        val unexplained = GenerationTiming(totalSteps = 2)
        val patched = unexplained.add(timing)
        assertEquals(3, patched.totalSteps)
        assertEquals(1, patched.measuredSteps)
        assertEquals(mapOf(GenerationSkipReason.LEGACY_UNKNOWN to 2), patched.skippedReasons)
        assertConsistent(patched)
    }
}
