package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.ui.components.SelectTextField
import java.util.Locale

@Composable
internal fun TtsLanguagePicker(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val displayLocale = LocalConfiguration.current.locales[0]
    val automatic = stringResource(R.string.r19_tts_auto_language)
    val tags = listOf("", "zh-CN", "zh-TW", "zh-HK", "en-US", "en-GB", "ja-JP", "ko-KR",
        "fr-FR", "de-DE", "es-ES", "it-IT", "pt-BR", "ru-RU", "ar-SA", "hi-IN", "vi-VN", "th-TH", "id-ID")
    fun label(tag: String) = if (tag.isBlank()) automatic else
        "${Locale.forLanguageTag(tag).getDisplayName(displayLocale)} ($tag)"
    SelectTextField(value = label(value), options = tags, onOptionSelected = onChange,
        readOnly = true, optionToString = { label(it) }, modifier = modifier)
}
