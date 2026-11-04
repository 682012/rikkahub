package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.datastore.FloatingCompanionPreset
import me.rerere.rikkahub.ui.floating.portraitAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitCompanionSelectorTest {

    @Test
    fun `tiles expose stable test tags`() {
        assertEquals("portrait-selector", PortraitSelectorTestTags.ROOT)
        assertEquals(
            "portrait-selector-whale_maid",
            PortraitSelectorTestTags.tile(FloatingCompanionPreset.WHALE_MAID),
        )
        assertEquals(
            "portrait-selector-dragon_girl",
            PortraitSelectorTestTags.tile(FloatingCompanionPreset.DRAGON_GIRL),
        )
        assertEquals(
            "portrait-selector-sun_reader",
            PortraitSelectorTestTags.tile(FloatingCompanionPreset.SUN_READER),
        )
        assertEquals(
            "portrait-selector-custom",
            PortraitSelectorTestTags.tile(FloatingCompanionPreset.CUSTOM),
        )
        assertEquals(
            "portrait-selector-logo",
            PortraitSelectorTestTags.tile(FloatingCompanionPreset.LOGO),
        )
    }

    @Test
    fun `legacy presets highlight the portrait they now render`() {
        assertEquals(
            FloatingCompanionPreset.WHALE_MAID,
            highlightPreset(FloatingCompanionPreset.CAT),
        )
        assertEquals(
            FloatingCompanionPreset.DRAGON_GIRL,
            highlightPreset(FloatingCompanionPreset.DRAGON),
        )
        assertEquals(
            FloatingCompanionPreset.SUN_READER,
            highlightPreset(FloatingCompanionPreset.SUN_READER),
        )
        assertEquals(FloatingCompanionPreset.LOGO, highlightPreset(FloatingCompanionPreset.LOGO))
        assertEquals(
            FloatingCompanionPreset.CUSTOM,
            highlightPreset(FloatingCompanionPreset.CUSTOM),
        )
    }

    @Test
    fun `portrait tiles point at the bundled transparent assets`() {
        val expected = mapOf(
            FloatingCompanionPreset.WHALE_MAID to "file:///android_asset/companions/six/deepseek/idle.png",
            FloatingCompanionPreset.DRAGON_GIRL to "file:///android_asset/companions/six/gpt/idle.png",
            FloatingCompanionPreset.SUN_READER to "file:///android_asset/companions/six/claude/idle.png",
            FloatingCompanionPreset.GEMINI_CAT to "file:///android_asset/companions/six/gemini/idle.png",
            FloatingCompanionPreset.GLM_FOX to "file:///android_asset/companions/six/glm/idle.png",
            FloatingCompanionPreset.QWEN_GIRL to "file:///android_asset/companions/six/qwen/idle.png",
        )
        expected.forEach { (preset, asset) ->
            assertEquals(asset, portraitAsset(preset))
            assertTrue(portraitAsset(preset).endsWith(".png"))
        }
    }
}
