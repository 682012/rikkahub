package me.rerere.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class R10TimingIntegrationTest {
    @Test fun legacyPartialTimeCannotDiluteNewMeasuredContinuation() {
        val legacy = GenerationTiming(generationMs = 2_000, outputTokens = 900, measuredSteps = 1, totalSteps = 2)
        val current = GenerationTiming(generationMs = 1_000, outputTokens = 50,
            measuredOutputTokens = 50, measuredSteps = 1, totalSteps = 1)
        val combined = legacy + current
        assertEquals(50.0, combined.speedTokensPerSecond!!, 0.0001)
        assertEquals(1_000L, combined.generationMs)
        assertEquals(1, combined.measuredSteps)
        assertEquals(3, combined.totalSteps)
        assertEquals(2, combined.skippedReasons[GenerationSkipReason.LEGACY_UNKNOWN])
        assertEquals(950L, combined.outputTokens)
    }

    @Test fun invalidCoverageCannotProduceAReportedRate() {
        assertNull(GenerationTiming(generationMs = 1_000, measuredOutputTokens = 50).speedTokensPerSecond)
        assertNull(GenerationTiming(generationMs = 1_000, measuredOutputTokens = 50,
            measuredSteps = 2, totalSteps = 1).speedTokensPerSecond)
    }

    @Test fun legacyFullyMeasuredRecordsRemainCompatible() {
        val legacy = GenerationTiming(generationMs = 2_000, outputTokens = 100, measuredSteps = 2, totalSteps = 2)
        val unmeasured = GenerationTiming(outputTokens = 1_000, totalSteps = 1,
            skippedReasons = mapOf(GenerationSkipReason.SINGLE_PACKET to 1))
        assertEquals(50.0, (legacy + unmeasured).speedTokensPerSecond!!, 0.0001)
        assertEquals(2, (legacy + unmeasured).measuredSteps)
    }
}
