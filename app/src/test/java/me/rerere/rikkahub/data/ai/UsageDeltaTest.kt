package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageDeltaTest {
    @Test fun `approval continuation records only the new run`() {
        val delta = usageDelta(TokenUsage(300, 30, 20, 330), TokenUsage(100, 10, 5, 110))!!
        assertEquals(200, delta.promptTokens)
        assertEquals(20, delta.completionTokens)
        assertEquals(15, delta.cachedTokens)
        assertEquals(220, delta.totalTokens)
    }
}
