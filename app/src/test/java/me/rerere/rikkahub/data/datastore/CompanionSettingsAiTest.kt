package me.rerere.rikkahub.data.datastore

import kotlin.uuid.Uuid
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * r10 新增的小助手设置字段：默认不发请求、旧存档能读、手改 JSON 不会越界。
 */
class CompanionSettingsAiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `default settings never spend a request`() {
        val settings = FloatingCompanionSettings()
        assertEquals(CompanionModelMode.OFF, settings.aiModelMode)
        assertFalse(settings.aiEnabled)
        assertFalse(settings.autoEventsEnabled)
        assertEquals("", settings.aiPersonaPrompt)
        assertNull(settings.aiModelId)
        assertFalse(settings.companionStatusExpanded)
    }

    @Test
    fun `turning ai on without touching the switch keeps auto events off`() {
        val on = FloatingCompanionSettings(aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION)
        assertTrue(on.aiEnabled)
        assertFalse("auto events must be switched on explicitly", on.autoEventsEnabled)
    }

    @Test
    fun `persona is trimmed and capped`() {
        val long = "人".repeat(FloatingCompanionLimits.MAX_PERSONA_LENGTH + 40)
        val normalized = FloatingCompanionSettings(aiPersonaPrompt = "  $long  ").normalized()
        assertEquals(FloatingCompanionLimits.MAX_PERSONA_LENGTH, normalized.aiPersonaPrompt.length)
        assertEquals(
            "说话短一点",
            FloatingCompanionSettings(aiPersonaPrompt = "  说话短一点  ").normalized().aiPersonaPrompt,
        )
    }

    @Test
    fun `auto events cannot stay on while ai responses are off`() {
        val stale = FloatingCompanionSettings(
            aiModelMode = CompanionModelMode.OFF,
            autoEventsEnabled = true,
        ).normalized()
        assertFalse(stale.autoEventsEnabled)

        val enabled = FloatingCompanionSettings(
            aiModelMode = CompanionModelMode.FIXED,
            aiModelId = Uuid.random(),
            autoEventsEnabled = true,
        ).normalized()
        assertTrue(enabled.autoEventsEnabled)
    }

    @Test
    fun `old settings json still loads and keeps ai off`() {
        val old = """
            {"preset":"whale_maid","customImagePath":null,"customTapImagePath":null,"name":"miao",
             "tapLines":["zai ne"],"sizeDp":80,"animationEnabled":true,"minimized":true,
             "panelPreviewExpanded":false}
        """.trimIndent()
        val decoded = json.decodeFromString<FloatingCompanionSettings>(old)
        assertEquals(CompanionModelMode.OFF, decoded.aiModelMode)
        assertFalse(decoded.aiEnabled)
        assertEquals("miao", decoded.name)
        assertEquals(80, decoded.sizeDp)
    }

    @Test
    fun `ai fields survive a json round trip`() {
        val modelId = Uuid.random()
        val original = FloatingCompanionSettings(
            aiModelMode = CompanionModelMode.FIXED,
            aiModelId = modelId,
            aiPersonaPrompt = "be shorter",
            autoEventsEnabled = true,
            companionStatusExpanded = true,
        )
        val decoded = json.decodeFromString<FloatingCompanionSettings>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `follow conversation mode does not require a model id`() {
        val follow = FloatingCompanionSettings(aiModelMode = CompanionModelMode.FOLLOW_CONVERSATION)
        assertTrue(follow.aiEnabled)
        assertNull(follow.aiModelId)
    }
}
