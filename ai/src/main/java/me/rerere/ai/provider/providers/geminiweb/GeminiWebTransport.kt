package me.rerere.ai.provider.providers.geminiweb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionCredential
import me.rerere.ai.provider.WebSessionException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** One streamed update from the web endpoint. [delta] is already the difference, never the full text. */
internal data class GeminiWebUpdate(
    val delta: String,
    val isFirstDelta: Boolean,
    val conversationId: String?,
    val responseId: String?,
)

/**
 * Transport seam. Production uses [OkHttpGeminiWebTransport] with the fixed
 * `https://gemini.google.com` origin; tests inject a stub, so no endpoint is ever configurable from
 * the UI or from provider settings.
 */
internal interface GeminiWebTransport {
    /** Loads `/app` and returns its bootstrap tokens. Never follows redirects to Google accounts. */
    suspend fun bootstrap(credential: WebSessionCredential): GeminiWebBootstrap

    /**
     * Posts one standalone prompt and reports every decoded frame in order. Returns after the body has
     * been fully consumed. Cancelling the caller cancels the underlying HTTP call, including a blocked
     * body read.
     */
    suspend fun streamGenerate(
        credential: WebSessionCredential,
        bootstrap: GeminiWebBootstrap,
        prompt: String,
        requestId: Int,
        onFrame: suspend (GeminiWebFrame) -> Unit,
    )
}

/**
 * Real transport. Only two fixed endpoints on [origin] are ever touched: `/app` (bootstrap tokens) and
 * the StreamGenerate RPC. Google account cookie refresh is deliberately *not* implemented: refreshing
 * would invalidate the user's browser session, so an expired cookie set surfaces as
 * [WebSessionException] and the user has to sign in again.
 */
internal class OkHttpGeminiWebTransport(
    private val client: OkHttpClient,
    /** Only overridden by tests; provider settings can never change it. */
    private val origin: String = GEMINI_WEB_ORIGIN,
) : GeminiWebTransport {

    override suspend fun bootstrap(credential: WebSessionCredential): GeminiWebBootstrap =
        runCall(GeminiWebHttp.bootstrap(origin, credential)) { response ->
            if (response.code !in 200..299) throw classifyHttpFailure(response.code, response)
            val body = response.body ?: throw WebProtocolException("Gemini Web bootstrap 没有正文")
            val bytes = readBounded(body, GEMINI_WEB_MAX_BOOTSTRAP_BYTES)
            val html = String(bytes, StandardCharsets.UTF_8)
            // /app is HTML by design; missing bootstrap tokens, not HTML itself, means login/challenge.
            GeminiWebBootstrap.parse(html)
        }

    override suspend fun streamGenerate(
        credential: WebSessionCredential,
        bootstrap: GeminiWebBootstrap,
        prompt: String,
        requestId: Int,
        onFrame: suspend (GeminiWebFrame) -> Unit,
    ) = coroutineScope {
        val request = GeminiWebHttp.streamGenerate(origin, credential, bootstrap, prompt, requestId)
        val call = client.newCall(request)
        val channel = Channel<GeminiWebFrame>(Channel.BUFFERED)
        val finished = AtomicBoolean(false)

        val producer = launch(Dispatchers.IO) {
            try {
                val response = call.awaitCancellable()
                response.use { resp ->
                    if (resp.code !in 200..299) throw classifyHttpFailure(resp.code, resp)
                    val body = resp.body ?: throw WebProtocolException("Gemini Web 响应没有正文")
                    val stream = body.byteStream()
                    val decoder = GeminiWebFrameDecoder()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    var firstChunk = true
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > GEMINI_WEB_MAX_RESPONSE_BYTES) {
                            throw WebProtocolException("Gemini Web 响应超过 8MB 上限，已中止")
                        }
                        if (firstChunk) {
                            firstChunk = false
                            if (looksLikeHtml(String(buffer, 0, read, StandardCharsets.UTF_8))) {
                                throw WebSessionException(GEMINI_WEB_RELOGIN_MESSAGE)
                            }
                        }
                        for (payload in decoder.push(buffer, read)) {
                            channel.send(GeminiWebFrameParser.parse(payload))
                        }
                    }
                    for (payload in decoder.finish()) {
                        channel.send(GeminiWebFrameParser.parse(payload))
                    }
                }
                channel.close()
            } catch (t: Throwable) {
                channel.close(t)
            }
        }

        // Parent cancellation cancels the OkHttp call, which unblocks a read that is already in flight.
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                if (!finished.get()) call.cancel()
                throw e
            }
        }
        try {
            for (frame in channel) onFrame(frame)
        } finally {
            finished.set(true)
            // Closes a read that is still blocked when the collector went away.
            call.cancel()
            watchdog.cancel()
            producer.cancel()
        }
    }

    /** Runs a blocking response read under a cancellable call; cancellation closes the socket. */
    private suspend fun <T> runCall(request: Request, block: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val finished = AtomicBoolean(false)
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                if (!finished.get()) call.cancel()
                throw e
            }
        }
        try {
            val response = call.awaitCancellable()
            withContext(Dispatchers.IO) { response.use(block) }
        } finally {
            finished.set(true)
            watchdog.cancel()
        }
    }

    private fun classifyHttpFailure(code: Int, response: Response): Exception {
        if (code in 300..399 || code == 401 || code == 403) {
            return WebSessionException(GEMINI_WEB_RELOGIN_MESSAGE)
        }
        if (code == 429) return WebSessionException(GEMINI_WEB_RATE_LIMIT_MESSAGE)
        val challenge = runCatching {
            response.body?.let { looksLikeHtml(String(readBounded(it, 64 * 1024), StandardCharsets.UTF_8)) }
                ?: false
        }.getOrDefault(false)
        // Response bodies are never echoed: they can contain account hints or page cookies.
        return if (challenge) {
            WebSessionException(GEMINI_WEB_RELOGIN_MESSAGE)
        } else {
            WebProtocolException("Gemini Web 返回 HTTP $code")
        }
    }

    private fun readBounded(body: ResponseBody, limit: Long): ByteArray {
        val stream = body.byteStream()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                throw WebProtocolException("Gemini Web 响应超过上限，已中止")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun looksLikeHtml(text: String): Boolean {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("<")) return true
        val lower = text.take(4096).lowercase()
        return lower.contains("accounts.google.com") || lower.contains("servicelogin")
    }
}

/**
 * `enqueue` + `suspendCancellableCoroutine`: cancelling the coroutine cancels the OkHttp call, so both
 * waiting for the response and the body read that follows are tied to the coroutine lifetime.
 */
private suspend fun Call.awaitCancellable(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/**
 * One standalone prompt against a web session: bootstrap tokens, one StreamGenerate call, cumulative
 * snapshots buffered into one answer.
 *
 * Every call starts a fresh chat inside the web backend, so the caller has to hand over the complete
 * local context in the prompt; the user's existing remote chats are never read, continued or deleted.
 *
 * The answer is only accepted when the selected candidate carries the terminal marker
 * (`candidate[8][0] == 2`). A clean EOF, a stored-history frame (`body[25]` string) or a partial frame
 * are all failures: a half answer is never returned.
 */
internal class GeminiWebChatSession(
    private val transport: GeminiWebTransport,
    private val credential: WebSessionCredential,
    private val requestIdFactory: () -> Int = { Random.nextInt(100_000, 1_000_000) },
) {
    @Volatile
    var lastText: String = ""
        private set

    fun stream(prompt: String): Flow<GeminiWebUpdate> = channelFlow {
        val bootstrap = transport.bootstrap(credential)
        val accumulator = GeminiWebTextAccumulator()
        var conversationId: String? = null
        var responseId: String? = null
        var deltas = 0
        transport.streamGenerate(
            credential = credential,
            bootstrap = bootstrap,
            prompt = prompt,
            requestId = requestIdFactory(),
        ) { frame ->
            frame.responseId?.let { responseId = it }
            frame.conversationId?.let { conversationId = it }
            val delta = accumulator.update(frame)
            if (accumulator.text.toByteArray(StandardCharsets.UTF_8).size > GEMINI_WEB_MAX_TEXT_BYTES) {
                throw WebProtocolException("Gemini Web 最终文本超过 1MB 上限，已中止")
            }
            if (delta.isNotEmpty()) {
                deltas++
                send(
                    GeminiWebUpdate(
                        delta = delta,
                        isFirstDelta = deltas == 1,
                        conversationId = conversationId,
                        responseId = responseId,
                    )
                )
            }
        }
        lastText = accumulator.text
        if (!accumulator.complete) {
            throw WebProtocolException(
                "Gemini Web 流已结束但没有终态（candidate[8][0] != 2）；history stored 或 EOF 都不算完成"
            )
        }
        if (accumulator.text.isBlank()) {
            throw WebProtocolException("Gemini Web 返回了空文本")
        }
    }

    /** Collects the whole answer and its response id. Used by non-streaming calls and tool bridging. */
    suspend fun completeText(prompt: String): Pair<String, String?> {
        var responseId: String? = null
        stream(prompt).collect { update ->
            update.responseId?.let { responseId = it }
        }
        // Authoritative answer is the last buffered snapshot, never a concatenation of snapshots.
        return lastText to responseId
    }
}
