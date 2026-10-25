package me.rerere.rikkahub.ui.pages.setting

import kotlin.time.Duration.Companion.seconds
import me.rerere.ai.agent.RetryDelay
import me.rerere.rikkahub.data.ai.toRetryPolicy
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.RetryDelayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 设置页那四个预设和两个秒数输入框的纯逻辑：界面上选什么，存进去和真正等待的就是什么。
 */
class RetryDelayPresetTest {
    @Test
    fun `default setting shows the 60 second preset`() {
        assertEquals(RetryDelayPreset.FIXED_60, retryDelayPresetOf(NetworkSetting()))
        assertEquals(RetryDelayPreset.FIXED_60, retryDelayPresetOf(NetworkSetting(retryDelaySeconds = 60)))
    }

    @Test
    fun `presets map to the values shown on screen`() {
        assertEquals(
            RetryDelayPreset.FIXED_30,
            retryDelayPresetOf(NetworkSetting(retryDelayMode = RetryDelayMode.FIXED, retryDelaySeconds = 30)),
        )
        assertEquals(
            RetryDelayPreset.RANGE_30_60,
            retryDelayPresetOf(
                NetworkSetting(
                    retryDelayMode = RetryDelayMode.RANGE,
                    retryDelayMinSeconds = 30,
                    retryDelayMaxSeconds = 60,
                )
            ),
        )
        // 固定 45 秒不在预设里，界面要展开自定义输入框，不能悄悄显示成"60 秒"
        assertEquals(
            RetryDelayPreset.CUSTOM,
            retryDelayPresetOf(NetworkSetting(retryDelayMode = RetryDelayMode.FIXED, retryDelaySeconds = 45)),
        )
    }

    @Test
    fun `applying a preset writes exactly those seconds`() {
        val fixed30 = NetworkSetting().withRetryDelayPreset(RetryDelayPreset.FIXED_30)
        assertEquals(RetryDelayMode.FIXED, fixed30.retryDelayMode)
        assertEquals(30, fixed30.retryDelaySeconds)
        assertEquals(RetryDelay.Fixed(30.seconds), fixed30.toRetryPolicy().delayMode)

        val fixed60 = NetworkSetting().withRetryDelayPreset(RetryDelayPreset.FIXED_60)
        assertEquals(RetryDelay.Fixed(60.seconds), fixed60.toRetryPolicy().delayMode)

        val range = NetworkSetting().withRetryDelayPreset(RetryDelayPreset.RANGE_30_60)
        assertEquals(RetryDelayMode.RANGE, range.retryDelayMode)
        assertEquals(30, range.retryDelayMinSeconds)
        assertEquals(60, range.retryDelayMaxSeconds)
        val delay = range.toRetryPolicy().delayMode
        assertEquals(30.seconds, (delay as RetryDelay.RandomRange).min)
        assertEquals(60.seconds, delay.max)
    }

    @Test
    fun `selecting custom keeps the wait that is already in use`() {
        val fixed45 = NetworkSetting(
            retryDelayMode = RetryDelayMode.FIXED,
            retryDelaySeconds = 45,
            retryDelayMinSeconds = 30,
            retryDelayMaxSeconds = 60,
        )

        val custom = fixed45.withRetryDelayPreset(RetryDelayPreset.CUSTOM)

        assertEquals(RetryDelayMode.RANGE, custom.retryDelayMode)
        assertEquals(45..45, custom.editableRetryRange())
        // 行为不变：原来的固定 45 秒，现在等于 min == max 的区间
        assertEquals(RetryDelay.Fixed(45.seconds), fixed45.toRetryPolicy().delayMode)
        assertEquals(45.seconds, custom.toRetryPolicy().delayFor(1))
        assertEquals(45.seconds, custom.toRetryPolicy().delayFor(5))
    }

    @Test
    fun `every preset round trips`() {
        RetryDelayPreset.entries.forEach { preset ->
            val applied = NetworkSetting().withRetryDelayPreset(preset)
            assertEquals(preset, retryDelayPresetOf(applied))
        }
    }

    @Test
    fun `seconds input is clamped to the legal range`() {
        assertEquals(RetrySecondsInput("600", 600), sanitizeRetrySecondsInput("9999"))
        assertEquals(RetrySecondsInput("1", 1), sanitizeRetrySecondsInput("0"))
        assertEquals(RetrySecondsInput("7", 7), sanitizeRetrySecondsInput("007"))
        assertEquals(RetrySecondsInput("45", 45), sanitizeRetrySecondsInput(" 4a5s "))
        assertEquals("", sanitizeRetrySecondsInput("").text)
        assertNull(sanitizeRetrySecondsInput("").value)
        assertNull(sanitizeRetrySecondsInput("abc").value)
    }
}
