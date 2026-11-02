package me.rerere.rikkahub.data.ai.transformers

import java.time.LocalDate
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class CacheStableRawHistoryTest {
    @Test fun `building from original stored messages preserves prior injected user prefix across turns`() {
        var day = LocalDate.of(2026, 11, 2)
        var battery = "80"
        val renderer = CacheStablePlaceholders(today = { day }, formatDate = { it.toString() })
        val system = UIMessage.system("Current date {cur_date}, battery {battery_level}")
        val first = UIMessage.user("hello")
        val original = listOf(system, first)
        val a = renderer.render(original, batteryLevel = { battery })
        day = day.plusDays(1); battery = "20"
        // ChatRequestFactory reconstructs from raw DB messages, NOT from the previous transformed list.
        val b = renderer.render(original + UIMessage.user("second turn"), batteryLevel = { battery })
        assertEquals(a[0].toText(), b[0].toText())
        assertEquals(a[1].toText(), b[1].toText())
        assertTrue(b.last().toText().contains("battery_level: 20"))
        assertFalse(b[1].toText().contains("__injected"))
    }
}
