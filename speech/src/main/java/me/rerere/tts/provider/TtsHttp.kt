package me.rerere.tts.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reuses each provider's connection pool; stop/flush cancels even an in-flight body read. */
internal suspend fun OkHttpClient.executeTtsBytes(request: Request): ByteArray =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (!it.isSuccessful) throw TTSProviderException(
                            "TTS request failed: ${it.code} ${it.message}", it.code)
                        val body = it.body
                        require(body.contentLength() <= MAX_AUDIO_BYTES) { "TTS audio exceeds 32 MiB" }
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (continuation.isActive) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size().toLong() + count <= MAX_AUDIO_BYTES) { "TTS audio exceeds 32 MiB" }
                                output.write(buffer, 0, count)
                            }
                        }
                        output.toByteArray()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

private const val MAX_AUDIO_BYTES = 32L * 1024 * 1024
