package me.rerere.tts.controller

import kotlinx.serialization.Serializable
import java.util.Locale

/** Shared playback controls. Language is a synthesis hint, never a translation request. */
@Serializable
data class TtsPlaybackSettings(
    val languageTag: String = "",
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val chunkLength: Int = 160,
    val prefetchCount: Int = 2,
) {
    fun sanitized(): TtsPlaybackSettings = copy(
        languageTag = normalizeLanguage(languageTag),
        speed = finite(speed, 1f).coerceIn(0.25f, 3f),
        pitch = finite(pitch, 1f).coerceIn(0.5f, 2f),
        volume = finite(volume, 1f).coerceIn(0f, 1f),
        chunkLength = chunkLength.coerceIn(40, 400),
        prefetchCount = prefetchCount.coerceIn(0, 4),
    )

    companion object {
        private fun finite(value: Float, fallback: Float) = if (value.isFinite()) value else fallback
        fun normalizeLanguage(raw: String): String {
            val tag = raw.trim().replace('_', '-')
            if (tag.isBlank() || tag.equals("auto", true)) return ""
            if (!tag.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*"))) return ""
            return Locale.forLanguageTag(tag).toLanguageTag().takeUnless { it == "und" } ?: ""
        }
    }
}
