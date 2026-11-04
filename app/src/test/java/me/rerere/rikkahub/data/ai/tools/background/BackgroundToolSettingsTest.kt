package me.rerere.rikkahub.data.ai.tools.background

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.BackgroundToolSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置的默认值与归一化。
 *
 * 这里守的是两件很容易被 UI 或旧落盘破坏的事：默认开启且等 30 秒；用户把秒数设成 0、
 * 负数或一年时，读出来必须落回 1..3600，否则「前台等待 0 秒」等于把每个调用都立刻甩到后台。
 */
class BackgroundToolSettingsTest {
    @Test
    fun defaultsAndNormalization() {
        val defaults = BackgroundToolSettings()
        assertTrue(defaults.enabled)
        assertEquals(30, defaults.foregroundTimeoutSeconds)
        assertEquals(30_000L, defaults.foregroundWaitMillis)

        assertEquals(1, BackgroundToolSettings(foregroundTimeoutSeconds = 0).normalized().foregroundTimeoutSeconds)
        assertEquals(1, BackgroundToolSettings(foregroundTimeoutSeconds = -5).normalized().foregroundTimeoutSeconds)
        assertEquals(
            BackgroundToolSettings.MAX_FOREGROUND_TIMEOUT_SECONDS,
            BackgroundToolSettings(foregroundTimeoutSeconds = 999_999).normalized().foregroundTimeoutSeconds,
        )
        assertEquals(600_000L, BackgroundToolSettings(foregroundTimeoutSeconds = 600).foregroundWaitMillis)
        assertFalse(BackgroundToolSettings().copy(enabled = false).enabled)
    }

    @Test
    fun serializationRoundTrip() {
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(BackgroundToolSettings.serializer(), BackgroundToolSettings())
        val decoded = json.decodeFromString(BackgroundToolSettings.serializer(), encoded)
        assertEquals(BackgroundToolSettings(), decoded)
        // 老版本 / 手改的落盘值也要能读，读出来再归一化
        val legacy = json.decodeFromString(
            BackgroundToolSettings.serializer(),
            """{"enabled":false,"foregroundTimeoutSeconds":0}""",
        )
        assertFalse(legacy.enabled)
        assertEquals(1, legacy.normalized().foregroundTimeoutSeconds)
    }
}
