package me.rerere.tts.provider.providers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reuse a single lazily initialized engine; system engines synthesize one utterance at a time. */
class SystemTTSProvider : TTSProvider<TTSProviderSetting.SystemTTS> {
    private val lock = Mutex()
    private var engine: TextToSpeech? = null

    override fun generateSpeech(context: Context, providerSetting: TTSProviderSetting.SystemTTS,
        request: TTSRequest): Flow<AudioChunk> = flow {
        val data = lock.withLock {
            val tts = engine ?: createEngine(context.applicationContext).also { engine = it }
            val tag = providerSetting.languageTag.ifBlank { request.languageTag }
            val locale = if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag)
            require(tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) {
                "System TTS language $locale is not installed or supported"
            }
            if (providerSetting.voiceName.isNotBlank()) {
                val voice = tts.voices?.firstOrNull { it.name == providerSetting.voiceName }
                require(voice != null) { "Selected system TTS voice is not installed" }
                require(tag.isBlank() || voice.locale.language == locale.language) { "Voice does not match the selected language" }
                require(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "System TTS voice could not be selected" }
            }
            tts.setSpeechRate(providerSetting.speechRate)
            tts.setPitch(providerSetting.pitch)
            val id = UUID.randomUUID().toString()
            val file = File(context.appTempFolder, "tts_$id.wav")
            try {
                synthesize(tts, request.text, file, id)
            } finally {
                file.delete()
            }
        }
        emit(AudioChunk(data = data, format = AudioFormat.WAV, isLast = true))
    }

    private suspend fun createEngine(context: Context): TextToSpeech = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var instance: TextToSpeech? = null
            instance = TextToSpeech(context) { status ->
                Handler(Looper.getMainLooper()).post {
                    val tts = instance
                    if (status == TextToSpeech.SUCCESS && tts != null && cont.isActive) cont.resume(tts)
                    else {
                        tts?.shutdown()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("System TTS initialization failed"))
                    }
                }
            }
            cont.invokeOnCancellation { instance?.shutdown() }
        }
    }

    private suspend fun synthesize(tts: TextToSpeech, text: String, file: File, id: String): ByteArray =
        suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId != id || !cont.isActive) return
                    try { cont.resume(file.readBytes()) }
                    catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
                }
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resumeWithException(IllegalStateException("System TTS synthesis failed"))
                }
            })
            cont.invokeOnCancellation { tts.stop(); file.delete() }
            if (tts.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS && cont.isActive)
                cont.resumeWithException(IllegalStateException("System TTS synthesis could not start"))
        }
}
