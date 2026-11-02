package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.*
import org.junit.Test

class ModelRuntimeDraftTest {
    @Test fun `all malformed or non ASCII numeric inputs are rejected rather than silently changed`() {
        for (raw in listOf("", "0", "-1", "+1", "1.2", "1e3", "abc", " 1 ", "１２", "١٢", "2147483648")) {
            assertNull(raw, ModelRuntimeDraft.contextLengthOrNull(raw))
            assertNull(raw, ModelRuntimeDraft.thresholdPercentOrNull(raw))
            assertNull(raw, ModelRuntimeDraft.concurrencyOrNull(raw))
        }
    }

    @Test fun `all thresholds and request limits have exact inclusive boundaries`() {
        for (n in -1..102) {
            val raw = n.toString()
            assertEquals(raw, n.takeIf { it in 10..100 }, ModelRuntimeDraft.thresholdPercentOrNull(raw))
            assertEquals(raw, n.takeIf { it in 1..64 }, ModelRuntimeDraft.concurrencyOrNull(raw))
        }
        assertEquals(Int.MAX_VALUE, ModelRuntimeDraft.contextLengthOrNull(Int.MAX_VALUE.toString()))
    }

    @Test fun `display threshold rounds up exactly like the runtime comparison and cannot overflow`() {
        assertEquals(8000L, ModelRuntimeDraft.triggerTokens(10_000, 80))
        assertEquals(8001L, ModelRuntimeDraft.triggerTokens(10_001, 80))
        assertEquals(Int.MAX_VALUE.toLong(), ModelRuntimeDraft.triggerTokens(Int.MAX_VALUE, 100))
        for (length in listOf(1, 99, 100, 101, 32_768, 128_000, 200_000, Int.MAX_VALUE)) {
            for (percent in 10..100) {
                val trigger = ModelRuntimeDraft.triggerTokens(length, percent)
                assertTrue(trigger * 100 >= length.toLong() * percent)
                assertTrue((trigger - 1) * 100 < length.toLong() * percent)
            }
        }
    }
}
