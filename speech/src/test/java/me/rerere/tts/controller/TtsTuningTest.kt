package me.rerere.tts.controller

import kotlinx.serialization.json.Json
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class TtsTuningTest {
    @Test fun defaultsPreserveOldPlayback() {
        assertEquals(TtsPlaybackSettings(), Json.decodeFromString<TtsPlaybackSettings>("{}"))
        assertEquals("", Json.decodeFromString<TTSRequest>("{\"text\":\"hello\"}").languageTag)
    }
    @Test fun nonfiniteAndOutOfRangeValuesAreSafe() {
        val settings = TtsPlaybackSettings(speed = Float.NaN, pitch = Float.POSITIVE_INFINITY,
            volume = -1f, chunkLength = 99999, prefetchCount = -1).sanitized()
        assertEquals(1f, settings.speed, 0f); assertEquals(1f, settings.pitch, 0f)
        assertEquals(0f, settings.volume, 0f); assertEquals(400, settings.chunkLength)
        assertEquals(0, settings.prefetchCount)
    }
    @Test fun tuningSurvivesSerialization() {
        val settings = TtsPlaybackSettings("ja-JP", 0.25f, 0.5f, 0.3f, 40, 4)
        assertEquals(settings, Json.decodeFromString<TtsPlaybackSettings>(Json.encodeToString(settings)))
    }
    @Test fun languageNormalization() {
        assertEquals("zh-CN", TtsPlaybackSettings.normalizeLanguage(" zh_cn "))
        assertEquals("", TtsPlaybackSettings.normalizeLanguage("auto"))
        assertEquals("", TtsPlaybackSettings.normalizeLanguage("../../xx"))
    }
    @Test fun oldProviderConfigurationsKeepDefaults() {
        val system = Json.decodeFromString<TTSProviderSetting>("{\"type\":\"system\"}") as TTSProviderSetting.SystemTTS
        assertEquals("", system.languageTag); assertEquals("", system.voiceName)
        val openai = Json.decodeFromString<TTSProviderSetting>("{\"type\":\"openai\"}") as TTSProviderSetting.OpenAI
        assertEquals("", openai.instructions)
    }
    @Test fun firstChunkIsShortAndAllChunksAreBounded() {
        val text = "这是一段很长的中文文本".repeat(100)
        val chunks = TextChunker(160, 80).split(text)
        assertTrue(chunks.first().text.length <= 80)
        assertTrue(chunks.all { it.text.length <= 160 })
        assertEquals(text, chunks.joinToString("") { it.text })
    }
    @Test fun englishWordSeparatorsSurvive() {
        val text = "A short sentence. This is another sentence with several words. ".repeat(10).trim()
        val read = TextChunker(80, 40).split(text).joinToString(" ") { it.text }
        assertEquals(text.replace(Regex("\\s+"), " "), read.replace(Regex("\\s+"), " "))
    }
    @Test fun unicodePairsAreNeverSplit() {
        val text = "🐳".repeat(53)
        val chunks = TextChunker(17, 9).split(text)
        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { !it.text.last().isHighSurrogate() && !it.text.first().isLowSurrogate() })
    }
    @Test fun emptyTextDoesNotIssueARequest() { assertTrue(TextChunker().split(" \n ").isEmpty()) }
}
