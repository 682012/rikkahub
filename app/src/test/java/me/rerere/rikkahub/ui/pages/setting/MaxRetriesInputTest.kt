package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 设置页"最大重试次数"输入框（对应 fork.34 的 P4：
 * 输入 999 时框里显示 999，实际存进去的是 100，界面和设置对不上）。
 */
class MaxRetriesInputTest {
    @Test
    fun `normal input keeps display and stored value identical`() {
        val input = sanitizeMaxRetriesInput("12")
        assertEquals("12", input.text)
        assertEquals(12, input.value)
    }

    @Test
    fun `value above the limit is clamped in both display and stored value`() {
        val input = sanitizeMaxRetriesInput("999")
        assertEquals("100", input.text)
        assertEquals(100, input.value)
    }

    @Test
    fun `the limit itself is kept`() {
        val input = sanitizeMaxRetriesInput("100")
        assertEquals("100", input.text)
        assertEquals(100, input.value)
    }

    @Test
    fun `pasted input longer than three digits is still clamped consistently`() {
        val input = sanitizeMaxRetriesInput("1234")
        assertEquals("100", input.text)
        assertEquals(100, input.value)
    }

    @Test
    fun `zero is an accepted value`() {
        val input = sanitizeMaxRetriesInput("0")
        assertEquals("0", input.text)
        assertEquals(0, input.value)
    }

    @Test
    fun `clearing the field does not write anything`() {
        val input = sanitizeMaxRetriesInput("")
        assertEquals("", input.text)
        assertNull(input.value)
    }

    @Test
    fun `non digit input is treated as a clear`() {
        val input = sanitizeMaxRetriesInput("abc")
        assertEquals("", input.text)
        assertNull(input.value)
    }

    @Test
    fun `leading zeros are normalised so the display matches the stored int`() {
        val input = sanitizeMaxRetriesInput("007")
        assertEquals("7", input.text)
        assertEquals(7, input.value)
    }

    @Test
    fun `sanitising is idempotent so the field never jumps on its own`() {
        listOf("", "0", "7", "12", "99", "100", "999", "abc", "1234", "0x10", "-5")
            .forEach { raw ->
                val once = sanitizeMaxRetriesInput(raw)
                val twice = sanitizeMaxRetriesInput(once.text)
                assertEquals("raw=$raw", once, twice)
            }
    }
}
