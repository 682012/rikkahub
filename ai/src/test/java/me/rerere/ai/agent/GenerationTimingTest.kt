package me.rerere.ai.agent

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationTimingTest {
    private class FakeClock(var currentNanos: Long = 0L) {
        fun nanoTime(): Long = currentNanos
        fun advanceMs(ms: Long) {
            currentNanos += ms * 1_000_000L
        }
    }

    @Test
    fun testMetadataDelayedFirstToken() {
        val clock = FakeClock(1_000_000_000L)
        val timer = GenerationTimer(clock::nanoTime)

        clock.advanceMs(100L)
        timer.onChunk(StreamChunk.TextStart(id = "msg_1"))

        clock.advanceMs(200L) // t = 300ms from start
        timer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "Hello"))

        clock.advanceMs(300L) // t = 600ms from start
        timer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = " world"))

        clock.advanceMs(100L) // t = 700ms from start
        val timing = timer.finish(outputTokens = 10, streaming = true)

        assertEquals(300L, timing.firstContentMs)
        assertEquals(300L, timing.generationMs)
        assertEquals(700L, timing.requestMs)
        assertEquals(10L, timing.outputTokens)
        assertEquals(1, timing.measuredSteps)
        assertEquals(1, timing.totalSteps)
        assertNotNull(timing.speedTokensPerSecond)
        assertEquals((10.0 * 1000.0) / 300.0, timing.speedTokensPerSecond!!, 1e-6)
    }

    @Test
    fun testTrailingUsageDelayExcluded() {
        val clock = FakeClock(0L)
        val timer = GenerationTimer(clock::nanoTime)

        clock.advanceMs(50L)
        timer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "Start"))

        clock.advanceMs(250L) // t = 300ms from start
        timer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = " End"))

        clock.advanceMs(500L) // t = 800ms from start, trailing usage arrives
        timer.onChunk(StreamChunk.Usage(TokenUsage(promptTokens = 10, completionTokens = 10)))

        clock.advanceMs(100L) // t = 900ms request close
        val timing = timer.finish(outputTokens = 10, streaming = true)

        assertEquals(50L, timing.firstContentMs)
        assertEquals(250L, timing.generationMs)
        assertEquals(900L, timing.requestMs)
        assertEquals(10L, timing.outputTokens)
        assertEquals(40.0, timing.speedTokensPerSecond!!, 1e-6)
    }

    @Test
    fun testEmptyTextDoesNotCountAsContent() {
        val clock = FakeClock(0L)
        val timer = GenerationTimer(clock::nanoTime)

        clock.advanceMs(100L)
        timer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = ""))
        clock.advanceMs(100L)
        timer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = ""))
        clock.advanceMs(100L)
        val timing = timer.finish(outputTokens = 5, streaming = true)

        assertNull(timing.firstContentMs)
        assertNull(timing.generationMs)
        assertEquals(0, timing.measuredSteps)
        assertEquals(1, timing.totalSteps)
        assertNull(timing.speedTokensPerSecond)
    }

    @Test
    fun testSingleBufferedResponse() {
        val clock = FakeClock(0L)
        val timer = GenerationTimer(clock::nanoTime)

        clock.advanceMs(100L)
        timer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "All response in one chunk"))
        clock.advanceMs(200L)
        val timing = timer.finish(outputTokens = 20, streaming = true)

        assertEquals(100L, timing.firstContentMs)
        assertNull(timing.generationMs)
        assertEquals(0, timing.measuredSteps)
        assertEquals(1, timing.totalSteps)
        assertNull(timing.speedTokensPerSecond)
    }

    @Test
    fun testNonStreamResponse() {
        val clock = FakeClock(0L)
        val timer = GenerationTimer(clock::nanoTime)

        clock.advanceMs(150L)
        val timing = timer.finish(outputTokens = 30, streaming = false)

        assertNull(timing.firstContentMs)
        assertNull(timing.generationMs)
        assertEquals(150L, timing.requestMs)
        assertEquals(30L, timing.outputTokens)
        assertEquals(0, timing.measuredSteps)
        assertEquals(1, timing.totalSteps)
        assertNull(timing.speedTokensPerSecond)
    }

    @Test
    fun testNoUsageOrZeroUsage() {
        val clock1 = FakeClock(0L)
        val timer1 = GenerationTimer(clock1::nanoTime)
        clock1.advanceMs(100L)
        timer1.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "a"))
        clock1.advanceMs(100L)
        timer1.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "b"))
        val timingNoUsage = timer1.finish(outputTokens = null, streaming = true)

        assertNull(timingNoUsage.generationMs)
        assertEquals(0L, timingNoUsage.outputTokens)
        assertEquals(0, timingNoUsage.measuredSteps)
        assertNull(timingNoUsage.speedTokensPerSecond)

        val clock2 = FakeClock(0L)
        val timer2 = GenerationTimer(clock2::nanoTime)
        clock2.advanceMs(100L)
        timer2.onChunk(StreamChunk.TextDelta(id = "msg_2", text = "a"))
        clock2.advanceMs(100L)
        timer2.onChunk(StreamChunk.TextDelta(id = "msg_2", text = "b"))
        val timingZeroUsage = timer2.finish(outputTokens = 0, streaming = true)

        assertNull(timingZeroUsage.generationMs)
        assertEquals(0L, timingZeroUsage.outputTokens)
        assertEquals(0, timingZeroUsage.measuredSteps)
        assertNull(timingZeroUsage.speedTokensPerSecond)
    }

    @Test
    fun testTwoStepsWeightedSum() {
        val clock1 = FakeClock(0L)
        val timer1 = GenerationTimer(clock1::nanoTime)
        clock1.advanceMs(100L)
        timer1.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "step1 a"))
        clock1.advanceMs(500L)
        timer1.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "step1 b"))
        val step1 = timer1.finish(outputTokens = 10, streaming = true)

        val clock2 = FakeClock(0L)
        val timer2 = GenerationTimer(clock2::nanoTime)
        clock2.advanceMs(50L)
        timer2.onChunk(StreamChunk.TextDelta(id = "msg_2", text = "step2 a"))
        clock2.advanceMs(1000L)
        timer2.onChunk(StreamChunk.TextDelta(id = "msg_2", text = "step2 b"))
        val step2 = timer2.finish(outputTokens = 20, streaming = true)

        val merged = step1.add(step2)
        assertEquals(50L, merged.firstContentMs)
        assertEquals(1500L, merged.generationMs)
        assertEquals(30L, merged.outputTokens)
        assertEquals(2, merged.measuredSteps)
        assertEquals(2, merged.totalSteps)
        assertEquals(20.0, merged.speedTokensPerSecond!!, 1e-6)
    }

    @Test
    fun partialMeasurementKeepsWeightedSpeedAndCoverage() {
        val clock1 = FakeClock(0L)
        val timer1 = GenerationTimer(clock1::nanoTime)
        clock1.advanceMs(100L)
        timer1.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "a"))
        clock1.advanceMs(200L)
        timer1.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "b"))
        val measuredStep = timer1.finish(outputTokens = 15, streaming = true)

        val clock2 = FakeClock(0L)
        val timer2 = GenerationTimer(clock2::nanoTime)
        clock2.advanceMs(50L)
        timer2.onChunk(StreamChunk.TextDelta(id = "msg_2", text = "single chunk"))
        val unmeasuredStep = timer2.finish(outputTokens = 20, streaming = true)

        val merged = measuredStep.add(unmeasuredStep)
        // 一步测不到不再把整条消息的速度藏起来，但要标出覆盖范围
        assertEquals(1, merged.measuredSteps)
        assertEquals(2, merged.totalSteps)
        assertEquals(1, merged.skippedSteps)
        assertTrue(merged.isPartial)
        // 全部 token 仍然计入消耗，但只有可测步骤的 token 进分子，未测步骤不会把速度抬高
        assertEquals(35L, merged.outputTokens)
        assertEquals(15L, merged.measuredTokens)
        assertEquals(75.0, merged.speedTokensPerSecond!!, 1e-6)
        assertEquals(mapOf(GenerationSkipReason.SINGLE_PACKET to 1), merged.skippedReasons)
    }

    @Test
    fun allStepsUnmeasuredKeepReasonsInsteadOfSpeed() {
        val serverWaitClock = FakeClock(0L)
        val serverWaitTimer = GenerationTimer(serverWaitClock::nanoTime)
        serverWaitClock.advanceMs(100L)
        serverWaitTimer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "Calling tool..."))
        serverWaitClock.advanceMs(4000L)
        serverWaitTimer.onChunk(StreamChunk.ServerToolStart(id = "call_1", toolName = "search"))
        serverWaitClock.advanceMs(100L)
        serverWaitTimer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "Result"))
        val serverWait = serverWaitTimer.finish(outputTokens = 30, streaming = true)

        val burstClock = FakeClock(0L)
        val burstTimer = GenerationTimer(burstClock::nanoTime)
        burstTimer.onChunk(StreamChunk.TextDelta(id = "msg_2", text = "one packet only"))
        val burst = burstTimer.finish(outputTokens = 40, streaming = true)

        val merged = serverWait.add(burst)
        assertNull(merged.speedTokensPerSecond)
        assertEquals(0, merged.measuredSteps)
        assertEquals(2, merged.totalSteps)
        assertEquals(2, merged.skippedSteps)
        // 全部缺测时也要能说清原因，按原因分组
        assertEquals(
            mapOf(GenerationSkipReason.SERVER_WAIT to 1, GenerationSkipReason.SINGLE_PACKET to 1),
            merged.skippedReasons,
        )
    }

    @Test
    fun finishExplainsUnmeasurableSteps() {
        val nonStreamClock = FakeClock(0L)
        nonStreamClock.advanceMs(300L)
        val nonStream = GenerationTimer(nonStreamClock::nanoTime).finish(outputTokens = 20, streaming = false)
        assertEquals(mapOf(GenerationSkipReason.NON_STREAM to 1), nonStream.skippedReasons)
        assertEquals(0, nonStream.measuredSteps)

        val usageOnlyClock = FakeClock(0L)
        val usageOnlyTimer = GenerationTimer(usageOnlyClock::nanoTime)
        usageOnlyTimer.onChunk(StreamChunk.Usage(TokenUsage(promptTokens = 10, completionTokens = 5)))
        val usageOnly = usageOnlyTimer.finish(outputTokens = null, streaming = true)
        assertEquals(mapOf(GenerationSkipReason.NO_OUTPUT_TOKENS to 1), usageOnly.skippedReasons)
        assertEquals(0L, usageOnly.measuredOutputTokens)

        val shortClock = FakeClock(0L)
        val shortTimer = GenerationTimer(shortClock::nanoTime)
        shortTimer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "a"))
        shortClock.advanceMs(20L)
        shortTimer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "b"))
        val shortWindow = shortTimer.finish(outputTokens = 5, streaming = true)
        assertEquals(mapOf(GenerationSkipReason.SHORT_WINDOW to 1), shortWindow.skippedReasons)
    }

    @Test
    fun legacyAllMeasuredRecordStillHasSpeed() {
        // r9 及更早的记录没有 measuredOutputTokens：全部步骤都测到时分子就是 outputTokens
        val legacy = GenerationTiming(generationMs = 2000, outputTokens = 100, measuredSteps = 1, totalSteps = 1)
        assertEquals(100L, legacy.measuredTokens)
        assertEquals(50.0, legacy.speedTokensPerSecond!!, 1e-6)
    }

    @Test
    fun legacyPartiallyMeasuredRecordIsNotGuessed() {
        // 部分测到的老记录无法判断分子里有多少 token 属于已测步骤，宁可没有数
        val legacy = GenerationTiming(generationMs = 2000, outputTokens = 100, measuredSteps = 1, totalSteps = 2)
        assertEquals(0L, legacy.measuredTokens)
        assertNull(legacy.speedTokensPerSecond)
    }

    @Test
    fun mergingLegacyAllMeasuredKeepsTokensInNumerator() {
        val legacy = GenerationTiming(generationMs = 1000, outputTokens = 50, measuredSteps = 1, totalSteps = 1)
        val fresh = GenerationTiming(
            firstContentMs = 50,
            generationMs = 500,
            requestMs = 600,
            outputTokens = 10,
            measuredSteps = 1,
            totalSteps = 1,
            measuredOutputTokens = 10,
        )
        val merged = legacy.add(fresh)
        assertEquals(60L, merged.measuredTokens)
        assertEquals(2, merged.totalSteps)
        assertEquals(2, merged.measuredSteps)
        assertEquals(40.0, merged.speedTokensPerSecond!!, 1e-6)
        assertTrue(merged.skippedReasons.isEmpty())
    }

    @Test
    fun testServerToolTimeNoFalseTps() {
        val clock = FakeClock(0L)
        val timer = GenerationTimer(clock::nanoTime)

        clock.advanceMs(100L)
        timer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "Calling tool..."))
        clock.advanceMs(100L)
        timer.onChunk(StreamChunk.ServerToolStart(id = "call_1", toolName = "search"))
        clock.advanceMs(5000L) // server tool executed for 5s
        timer.onChunk(StreamChunk.ServerToolInputEnd(id = "call_1"))
        clock.advanceMs(100L)
        timer.onChunk(StreamChunk.TextDelta(id = "msg_1", text = "Result received"))

        val timing = timer.finish(outputTokens = 50, streaming = true)
        assertNull(timing.generationMs)
        assertEquals(0, timing.measuredSteps)
        assertEquals(1, timing.totalSteps)
        assertNull(timing.speedTokensPerSecond)
    }

    @Test
    fun testReasoningAndToolCallDeltasCountAsContent() {
        val clock = FakeClock(0L)
        val timer = GenerationTimer(clock::nanoTime)

        clock.advanceMs(100L)
        timer.onChunk(StreamChunk.ReasoningDelta(id = "1", text = "Thinking deep"))
        clock.advanceMs(300L)
        timer.onChunk(StreamChunk.ToolCallDelta(id = "2", toolNameDelta = "calc"))
        clock.advanceMs(200L)
        timer.onChunk(StreamChunk.ToolCallDelta(id = "2", inputDelta = "1+1"))

        val timing = timer.finish(outputTokens = 25, streaming = true)
        assertEquals(100L, timing.firstContentMs)
        assertEquals(500L, timing.generationMs)
        assertEquals(1, timing.measuredSteps)
        assertEquals(50.0, timing.speedTokensPerSecond!!, 1e-6)
    }

    @Test
    fun usageBeforeContentDoesNotStartGenerationClock() {
        val clock = FakeClock()
        val timer = GenerationTimer(clock::nanoTime)
        clock.advanceMs(20)
        timer.onChunk(StreamChunk.Usage(TokenUsage(promptTokens = 100)))
        clock.advanceMs(980)
        timer.onChunk(StreamChunk.TextDelta("a", "first"))
        clock.advanceMs(500)
        timer.onChunk(StreamChunk.TextDelta("a", "last"))
        clock.advanceMs(1500)
        val result = timer.finish(50)
        assertEquals(1000L, result.firstContentMs)
        assertEquals(500L, result.generationMs)
        assertEquals(3000L, result.requestMs)
        assertEquals(100.0, result.speedTokensPerSecond!!, 0.001)
    }

    @Test
    fun nonStreamingLastStepClearsPreviousFirstContentDelay() {
        val previous = GenerationTiming(firstContentMs = 500, generationMs = 100, requestMs = 600, outputTokens = 50, measuredSteps = 1, totalSteps = 1)
        val current = GenerationTiming(requestMs = 300, outputTokens = 20, totalSteps = 1)
        val result = previous.add(current)
        assertNull(result.firstContentMs)
        assertNull(result.speedTokensPerSecond)
    }

    @Test
    fun internalContinuationDoesNotIncludeItsHiddenWaitInTps() {
        val clock = FakeClock()
        val timer = GenerationTimer(clock::nanoTime)
        timer.onChunk(StreamChunk.TextDelta("a", "before"))
        clock.advanceMs(9000)
        timer.onChunk(StreamChunk.TextDelta("b", "after"))
        timer.onChunk(StreamChunk.Usage(TokenUsage(promptTokens = 300, completionTokens = 50), completeSnapshot = true,
            lastRequestUsage = TokenUsage(promptTokens = 200, completionTokens = 30)))
        assertNull(timer.finish(50).speedTokensPerSecond)
    }

    @Test
    fun tinyBufferedBurstIsNotAnEngineBenchmark() {
        val clock = FakeClock()
        val timer = GenerationTimer(clock::nanoTime)
        timer.onChunk(StreamChunk.TextDelta("a", "first"))
        clock.advanceMs(5)
        timer.onChunk(StreamChunk.TextDelta("a", "last"))
        assertNull(timer.finish(5000).speedTokensPerSecond)
    }

    @Test
    fun testMonotonicNegativeCoercion() {
        var time = 100_000_000L
        val timer = GenerationTimer { time }
        timer.onChunk(StreamChunk.TextDelta(id = "1", text = "chunk 1"))
        time = 50_000_000L // time anomaly going backwards
        timer.onChunk(StreamChunk.TextDelta(id = "1", text = "chunk 2"))
        val timing = timer.finish(outputTokens = 10, streaming = true)

        assertEquals(0L, timing.requestMs)
        assertNull(timing.generationMs)
        assertEquals(0, timing.measuredSteps)
        assertNull(timing.speedTokensPerSecond)
    }
}
