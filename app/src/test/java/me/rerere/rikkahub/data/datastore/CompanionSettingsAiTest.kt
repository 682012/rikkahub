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
    fun `persona is never trimmed or truncated while reading settings`() {
        // r16：normalized 只做语法清理，不再改用户已有的人设（旧版会 trim + take(800)）。
        val long = "  " + "人".repeat(FloatingCompanionLimits.MAX_PERSONA_LENGTH) + "  "
        val normalized = FloatingCompanionSettings(aiPersonaPrompt = long).normalized()
        assertEquals("user persona must be kept byte for byte", long, normalized.aiPersonaPrompt)
        assertEquals(
            "  说话短一点  ",
            FloatingCompanionSettings(aiPersonaPrompt = "  说话短一点  ").normalized().aiPersonaPrompt,
        )
    }

    @Test
    fun `with persona override refuses over-limit text instead of cutting it`() {
        val settings = FloatingCompanionSettings(preset = FloatingCompanionPreset.WHALE_MAID)
        val exact = "鲸".repeat(FloatingCompanionLimits.MAX_PERSONA_LENGTH)
        val saved = settings.withPersonaOverride("whale_maid", exact)
        assertEquals(exact, saved.aiPersonaPrompt)
        assertEquals(0, saved.characterOverrides.size)

        val tooLong = exact + "多"
        val failure = runCatching { settings.withPersonaOverride("whale_maid", tooLong) }.exceptionOrNull()
        assertTrue("over-limit save must fail loudly", failure is IllegalArgumentException)
        assertEquals("failed save must not touch settings", "", settings.aiPersonaPrompt)
        assertEquals(16000, FloatingCompanionLimits.MAX_PERSONA_LENGTH)
    }

    @Test
    fun `persona override is saved only to the owner it was opened for`() {
        val settings = FloatingCompanionSettings(preset = FloatingCompanionPreset.SUN_READER)
        val edited = settings.withPersonaOverride("whale_maid", "  只属于小鲸的一段  ")
        assertEquals("SUN_READER persona untouched", "", edited.aiPersonaPrompt)
        assertEquals("  只属于小鲸的一段  ", edited.personaOverrideOf("whale_maid"))
        assertEquals("", edited.personaOverrideOf("sun_reader"))
        // 切到小鲸时才用上这一格；名字、图片和其他角色不受影响
        val switched = edited.selectPreset(FloatingCompanionPreset.WHALE_MAID)
        assertEquals("  只属于小鲸的一段  ", switched.aiPersonaPrompt)
        // 空白覆盖等于恢复内置，不是保存一段空白
        assertTrue(edited.withPersonaOverride("whale_maid", "   ").personaOverrideOf("whale_maid").isEmpty())
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
