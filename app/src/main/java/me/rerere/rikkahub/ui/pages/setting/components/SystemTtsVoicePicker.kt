package me.rerere.rikkahub.ui.pages.setting.components

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.ui.components.SelectTextField
import java.util.Locale

@Composable
internal fun SystemTtsVoicePicker(value: String, languageTag: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    DisposableEffect(context) {
        var disposed = false
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            Handler(Looper.getMainLooper()).post {
                if (!disposed && status == TextToSpeech.SUCCESS) {
                    voices = engine?.voices.orEmpty().filter { "notInstalled" !in it.features.orEmpty() }
                        .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
                }
            }
        }
        onDispose { disposed = true; engine?.shutdown() }
    }
    val automatic = stringResource(R.string.r19_tts_auto_voice)
    val language = Locale.forLanguageTag(languageTag).language
    val available = voices.filter { languageTag.isBlank() || it.locale.language == language }
    val names = listOf("") + available.map { it.name }
    SelectTextField(value = value.ifBlank { automatic }, options = names,
        onValueChange = onChange, onOptionSelected = onChange,
        optionToString = { name -> if (name.isBlank()) automatic else {
            val voice = available.firstOrNull { it.name == name }
            "${voice?.locale?.displayName.orEmpty()} · $name"
        } }, modifier = modifier)
}
