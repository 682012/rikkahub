package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶部信息栏的展开状态是用户选择，要跟着设置一起落盘：
 * 默认收起、老配置不炸、展开过的选择重启后仍然是展开。
 */
class ChatHeaderExpandedSettingsTest {

    @Test
    fun defaultIsCollapsed() {
        assertFalse(AppearanceSettings().chatHeaderExpanded)
        assertFalse(DisplaySetting().appearance.chatHeaderExpanded)
    }

    @Test
    fun oldAppearanceJsonWithoutTheFieldReadsAsCollapsed() {
        val legacy = """{"chatUiStyle":"focus","surfaceOpacity":0.9}"""

        val decoded = JsonInstant.decodeFromString(AppearanceSettings.serializer(), legacy)

        assertFalse(decoded.chatHeaderExpanded)
        assertEquals(ChatUiStyle.FOCUS, decoded.chatUiStyle)
    }

    @Test
    fun expandedChoiceSurvivesEncodedRoundTrip() {
        val settings = DisplaySetting(appearance = AppearanceSettings(chatHeaderExpanded = true))

        val json = JsonInstant.encodeToString(DisplaySetting.serializer(), settings)
        val restored = JsonInstant.decodeFromString(DisplaySetting.serializer(), json)

        assertTrue(restored.appearance.chatHeaderExpanded)
    }

    @Test
    fun normalizationDoesNotResetTheChoice() {
        assertTrue(AppearanceSettings(chatHeaderExpanded = true).normalized().chatHeaderExpanded)
        assertFalse(AppearanceSettings(chatHeaderExpanded = false).normalized().chatHeaderExpanded)
    }
}
