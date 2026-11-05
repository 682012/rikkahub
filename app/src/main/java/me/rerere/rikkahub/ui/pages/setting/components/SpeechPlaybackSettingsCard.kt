package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.tts.controller.TtsPlaybackSettings
import me.rerere.ui.components.OutlinedNumberInput

@Composable
internal fun SpeechPlaybackSettingsCard(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit) {
    val value = settings.ttsPlaybackSettings.copy(speed = settings.defaultTTSPlaybackSpeed).sanitized()
    var advanced by remember { mutableStateOf(false) }
    fun update(transform: (TtsPlaybackSettings) -> TtsPlaybackSettings) = onUpdate { latest ->
        val next = transform(latest.ttsPlaybackSettings.copy(speed = latest.defaultTTSPlaybackSpeed)).sanitized()
        latest.copy(ttsPlaybackSettings = next, defaultTTSPlaybackSpeed = next.speed)
    }
    CardGroup(title = { Text(stringResource(R.string.r19_tts_playback_title)) }) {
        formItem(headlineContent = { Text(stringResource(R.string.r19_tts_language)) },
            supportingContent = { Text(stringResource(R.string.r19_tts_language_hint)) }) {
            TtsLanguagePicker(value.languageTag, { language -> update { it.copy(languageTag = language) } }, Modifier.fillMaxWidth())
        }
        formItem(headlineContent = { Text(stringResource(R.string.setting_tts_page_default_playback_speed)) }) {
            PlaybackSlider(value.speed, 0.25f..3f, "%.2f×", { v -> update { it.copy(speed = v) } })
        }
        formItem(headlineContent = { Text(stringResource(R.string.setting_tts_page_pitch)) }) {
            PlaybackSlider(value.pitch, 0.5f..2f, "%.2f×", { v -> update { it.copy(pitch = v) } })
        }
        formItem(headlineContent = { Text(stringResource(R.string.r19_tts_volume)) }) {
            PlaybackSlider(value.volume, 0f..1f, "%.2f", { v -> update { it.copy(volume = v) } })
        }
        item {
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.r19_tts_advanced)) }
        }
        if (advanced) {
            formItem(headlineContent = { Text(stringResource(R.string.r19_tts_chunk_length)) },
                supportingContent = { Text(stringResource(R.string.r19_tts_chunk_hint)) }) {
                OutlinedNumberInput(value.chunkLength, { n -> if (n in 40..400) update { it.copy(chunkLength = n) } }, Modifier.fillMaxWidth(), "40–400")
            }
            formItem(headlineContent = { Text(stringResource(R.string.r19_tts_prefetch)) },
                supportingContent = { Text(stringResource(R.string.r19_tts_prefetch_hint)) }) {
                OutlinedNumberInput(value.prefetchCount, { n -> if (n in 0..4) update { it.copy(prefetchCount = n) } }, Modifier.fillMaxWidth(), "0–4")
            }
            item {
                TextButton(onClick = { update { TtsPlaybackSettings() } }) { Text(stringResource(R.string.r19_tts_reset)) }
            }
        }
    }
}

@Composable
private fun PlaybackSlider(value: Float, range: ClosedFloatingPointRange<Float>, format: String, onChange: (Float) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    CardGroupValue(format.format(draft))
    Slider(value = draft, onValueChange = { draft = it },
        onValueChangeFinished = { onChange(draft) }, valueRange = range, modifier = Modifier.fillMaxWidth())
}
