package me.rerere.ai.provider.providers.geminiweb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionException
import kotlin.uuid.Uuid

/** The only origin this provider is allowed to talk to. */
internal const val GEMINI_WEB_ORIGIN: String = "https://gemini.google.com"

/**
 * Model id of the "whatever the web page uses" mapping.
 *
 * The web StreamGenerate endpoint takes no model id at all, so a model entry with this id means
 * "let the account's own default run" instead of pretending we chose a model server-side.
 */
internal const val GEMINI_WEB_DEFAULT_MODEL_ID: String = "web-default"

/**
 * Client-side capacity estimate only (used for local compression), never an upstream claim:
 * the web endpoint exposes no context window and no token accounting.
 */
internal const val GEMINI_WEB_DEFAULT_CONTEXT_LENGTH: Int = 32_768

internal const val GEMINI_WEB_BOOTSTRAP_PATH: String = "/app"

internal const val GEMINI_WEB_STREAM_PATH: String =
    "/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate"

/**
 * Honest client identity. We are not a Chrome build, we do not spoof a TLS fingerprint and we do not
 * attempt to defeat bot protection; if Google refuses this client the user is told to sign in again
 * in a real browser.
 */
internal const val GEMINI_WEB_USER_AGENT: String = "RikkaHub-WebSession/experimental"

/** Per-request JSPB header that must carry the same UUID as inner request slot 59. */
internal const val GEMINI_WEB_JSPB_HEADER: String = "x-goog-ext-525005358-jspb"

/** Response bounds. Oversized or truncated answers fail loudly instead of being half-delivered. */
internal const val GEMINI_WEB_MAX_BOOTSTRAP_BYTES: Long = 2L * 1024 * 1024
internal const val GEMINI_WEB_MAX_RESPONSE_BYTES: Long = 8L * 1024 * 1024
internal const val GEMINI_WEB_MAX_FRAME_BYTES: Int = 1024 * 1024
internal const val GEMINI_WEB_MAX_TEXT_BYTES: Int = 1024 * 1024

/** Message shown when Google answers with a login wall, challenge or an expired cookie set. */
internal const val GEMINI_WEB_RELOGIN_MESSAGE: String =
    "Gemini 网页会话不可用：服务端拒绝或要求重新验证。请回到浏览器打开 gemini.google.com/app 重新登录，" +
        "然后重新导入 Cookie（本通道不会自动刷新 Google 账号 Cookie，也不会绕过人机校验）。"

internal const val GEMINI_WEB_RATE_LIMIT_MESSAGE: String =
    "Gemini 网页会话被限流（429）。请稍后再试，或回到 gemini.google.com 确认账号状态。"

/**
 * Values scraped from the `/app` HTML bootstrap blob (`window.WIZ_global_data`).
 *
 * [snlm0e] is the CSRF token sent as the `at` form field, [buildLabel] is the `bl` query value and
 * [sessionId] is the `f.sid` query value. They are per-page-load values, not account credentials.
 */
internal data class GeminiWebBootstrap(
    val snlm0e: String,
    val buildLabel: String,
    val sessionId: String?,
) {
    override fun toString(): String =
        "GeminiWebBootstrap(buildLabel=$buildLabel, sessionId=$sessionId, snlm0e=[REDACTED])"

    companion object {
        /**
         * Parses the bootstrap object. A page without `SNlM0e` is a login/consent/challenge page, and that
         * is reported as a session problem (never retried, never worked around).
         */
        fun parse(html: String): GeminiWebBootstrap {
            val wiz = extractWizGlobalData(html)
            val snlm0e = wiz?.string("SNlM0e") ?: fallbackField(html, "SNlM0e")
            val buildLabel = wiz?.string("cfb2h") ?: fallbackField(html, "cfb2h")
            val sessionId = wiz?.string("FdrFJe") ?: fallbackField(html, "FdrFJe")

            if (snlm0e.isNullOrBlank()) {
                throw WebSessionException(GEMINI_WEB_RELOGIN_MESSAGE)
            }
            if (buildLabel.isNullOrBlank()) {
                // Without the build label every StreamGenerate request is rejected by the web backend.
                throw WebSessionException(GEMINI_WEB_RELOGIN_MESSAGE)
            }
            return GeminiWebBootstrap(
                snlm0e = snlm0e,
                buildLabel = buildLabel,
                sessionId = sessionId?.takeIf { it.isNotBlank() },
            )
        }

        /**
         * Locates `window.WIZ_global_data = {...};` and parses it as real JSON (balanced braces, string
         * aware) instead of regex-splitting the surrounding JavaScript.
         */
        private fun extractWizGlobalData(html: String): JsonObject? {
            val marker = html.indexOf("WIZ_global_data")
            if (marker < 0) return null
            val start = html.indexOf('{', marker)
            if (start < 0) return null
            val end = findObjectEnd(html, start) ?: return null
            return runCatching { Json.parseToJsonElement(html.substring(start, end + 1)).jsonObject }
                .getOrNull()
        }

        /** Balanced-brace scan that ignores braces inside JSON strings and escapes. */
        private fun findObjectEnd(text: String, start: Int): Int? {
            var depth = 0
            var inString = false
            var escaped = false
            var i = start
            while (i < text.length) {
                val c = text[i]
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
                i++
            }
            return null
        }

        /**
         * Narrow last resort when the bootstrap blob is not valid JSON (e.g. it is split across scripts).
         * Only used for plain string fields; the value still has to be a plausible bootstrap token.
         */
        private fun fallbackField(html: String, name: String): String? {
            val key = "\"$name\":\""
            var from = html.indexOf(key)
            if (from < 0) {
                val spaced = "\"$name\": \""
                from = html.indexOf(spaced)
                if (from < 0) return null
                val valueStart = from + spaced.length
                return readUntilQuote(html, valueStart)
            }
            return readUntilQuote(html, from + key.length)
        }

        private fun readUntilQuote(html: String, start: Int): String? {
            var i = start
            val sb = StringBuilder()
            while (i < html.length) {
                val c = html[i]
                if (c == '"') return sb.toString()
                if (c == '\n' || c == '<') return null
                sb.append(c)
                i++
            }
            return null
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
}

/**
 * One decoded `wrb.fr` frame of a StreamGenerate response.
 *
 * [text] is the full cumulative snapshot of the selected candidate, never a delta.
 * [complete] mirrors `candidate[8][0] == 2`; [historyStored] only mirrors a string at body `[25]`
 * ("saved to history") and is deliberately NOT treated as completion.
 */
internal data class GeminiWebFrame(
    val text: String? = null,
    val rcid: String? = null,
    val conversationId: String? = null,
    val responseId: String? = null,
    val complete: Boolean = false,
    val historyStored: Boolean = false,
)

/**
 * Parses one length-delimited StreamGenerate payload.
 *
 * Payload shape (verified against gemini_webapi 2026-10, commit 1ad0d4a):
 * ```
 * [["wrb.fr", null, "<inner JSPB array as string>", null, null, null, "generic"]]
 * ```
 * inside the JSPB array: `[1][0]` conversation id, `[1][1]` response id (`rc_...`),
 * `[4]` candidate list, candidate `[0]` rcid, candidate `[1][0]` cumulative answer text,
 * candidate `[8][0] == 2` terminal marker, and body `[25]` string means "stored in history".
 * A fatal error code lives at `part[5][2][0][1][0]` of a frame entry.
 */
internal object GeminiWebFrameParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): GeminiWebFrame {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull()
            ?: throw WebProtocolException("Gemini Web 返回了无法解析的帧")

        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> return parseInner(payload)
            else -> throw WebProtocolException("Gemini Web 返回了意外的帧结构")
        }

        var innerSeen = false
        var result: GeminiWebFrame? = null
        for (entry in entries) {
            val tuple = entry as? JsonArray ?: continue
            tuple.errorCode()?.let { code ->
                throw WebSessionException("Gemini Web returned service error $code; no automatic retry. Check the account on the official site.")
            }
            when (tuple.stringAt(0)) {
                "er", "e" -> throw WebSessionException(GEMINI_WEB_RELOGIN_MESSAGE)

                "wrb.fr" -> {
                    val inner = tuple.stringAt(2)
                        ?: throw WebProtocolException("Gemini Web 帧缺少响应体")
                    innerSeen = true
                    val parsed = parseInner(inner)
                    result = merge(result, parsed)
                }

                else -> Unit
            }
        }
        if (!innerSeen) {
            // Frames without a wrb.fr tuple are status chatter (e.g. "di" heartbeats).
            if (payload.isBlank()) throw WebProtocolException("Gemini Web 返回了空帧")
            return GeminiWebFrame()
        }
        return result ?: GeminiWebFrame()
    }

    /** Keeps one candidate: the first entry that actually carries text, never a blend of two rcids. */
    private fun merge(previous: GeminiWebFrame?, next: GeminiWebFrame): GeminiWebFrame {
        if (previous == null) return next
        val previousHasText = !previous.text.isNullOrEmpty()
        val nextHasText = !next.text.isNullOrEmpty()
        return if (!previousHasText && nextHasText) {
            next.copy(
                conversationId = previous.conversationId ?: next.conversationId,
                responseId = previous.responseId ?: next.responseId,
                historyStored = previous.historyStored || next.historyStored,
            )
        } else {
            previous.copy(
                conversationId = previous.conversationId ?: next.conversationId,
                responseId = previous.responseId ?: next.responseId,
                historyStored = previous.historyStored || next.historyStored,
            )
        }
    }

    private fun parseInner(payload: String): GeminiWebFrame {
        val body = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonArray
            ?: throw WebProtocolException("Gemini Web 响应体不是数组")

        val conversationId = body.stringAt(1, 0)
        val responseId = body.stringAt(1, 1)
        val historyStored = (body.getOrNull(25) as? JsonPrimitive)?.takeIf { it.isString } != null

        val candidates = body.arrayAt(4)
        var text: String? = null
        var rcid: String? = null
        var complete = false
        if (candidates != null) {
            val candidate = candidates
                .filterIsInstance<JsonArray>()
                .firstOrNull { !it.textAt().isNullOrEmpty() }
                ?: candidates.filterIsInstance<JsonArray>().firstOrNull()
            if (candidate != null) {
                rcid = candidate.stringAt(0)
                text = candidate.textAt()?.takeIf { it.isNotEmpty() }
                complete = candidate.arrayAt(8)?.intAt(0) == 2
            }
        }

        return GeminiWebFrame(
            text = text,
            rcid = rcid,
            conversationId = conversationId,
            responseId = responseId,
            complete = complete,
            historyStored = historyStored,
        )
    }

    /** Fatal error code path from the reference client: part[5][2][0][1][0], zero/absent means none. */
    private fun JsonArray.errorCode(): Int? {
        var current: JsonElement = this
        for (index in intArrayOf(5, 2, 0, 1, 0)) {
            current = (current as? JsonArray)?.getOrNull(index) ?: return null
        }
        return (current as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it != 0 }
    }

    private fun JsonArray.textAt(): String? = arrayAt(1)?.stringAt(0)

    private fun JsonArray.stringAt(index: Int): String? =
        (getOrNull(index) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonArray.arrayAt(index: Int): JsonArray? = getOrNull(index) as? JsonArray

    private fun JsonArray.intAt(index: Int): Int? =
        (getOrNull(index) as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonArray.stringAt(outer: Int, inner: Int): String? =
        arrayAt(outer)?.stringAt(inner)
}

/**
 * Buffers the answer from cumulative snapshots.
 *
 * The endpoint re-sends a whole snapshot for the selected candidate on every frame. We keep the last
 * snapshot verbatim as the authoritative answer; we never append two snapshots and never mix two
 * candidate ids (rcid) into one text. If the provider rewrites the answer, the buffer becomes the
 * new snapshot (the delta is empty, so already shown text is not spliced together with the rewrite).
 */
internal class GeminiWebTextAccumulator {
    var text: String = ""
        private set
    var rcid: String? = null
        private set
    var complete: Boolean = false
        private set
    var historyStored: Boolean = false
        private set

    fun update(frame: GeminiWebFrame): String {
        if (!accepts(frame.rcid)) return ""
        if (frame.complete) complete = true
        if (frame.historyStored) historyStored = true
        val snapshot = frame.text
        return if (snapshot.isNullOrEmpty()) "" else update(snapshot, frame.rcid)
    }

    fun update(snapshot: String, rcid: String? = null): String {
        if (!accepts(rcid)) return ""
        if (snapshot.isEmpty()) return ""
        val previous = text
        text = snapshot
        return if (snapshot.startsWith(previous)) snapshot.substring(previous.length) else ""
    }

    /** The first candidate that produces text wins; a different rcid is ignored, never merged. */
    private fun accepts(candidate: String?): Boolean {
        if (candidate == null) return true
        if (rcid == null) {
            rcid = candidate
            return true
        }
        return rcid == candidate
    }
}

/** Builds the fixed StreamGenerate request pieces. Kept pure so tests can pin the exact 81-slot shape. */
internal object GeminiWebRequestBuilder {

    /** `f.req` plus the JSPB header value; both must carry the same per-request UUID. */
    internal data class Envelope(val freq: String, val jspbHeader: String)

    fun envelope(
        prompt: String,
        language: String = "en",
        uuid: String = Uuid.random().toString().uppercase(),
    ): Envelope {
        val inner = MutableList<JsonElement>(81) { JsonNull }
        // [prompt, 0, null, null, null, null, 0] — req_file_data and the two media tails stay null.
        inner[0] = buildJsonArray {
            add(JsonPrimitive(prompt))
            add(0)
            add(JsonNull)
            add(JsonNull)
            add(JsonNull)
            add(JsonNull)
            add(0)
        }
        inner[1] = buildJsonArray { add(JsonPrimitive(language)) }
        // DEFAULT_METADATA exactly as the reference client sends it for a fresh chat.
        inner[2] = buildJsonArray {
            add("")
            add("")
            add("")
            add(JsonNull)
            add(JsonNull)
            add(JsonNull)
            add(JsonNull)
            add(JsonNull)
            add(JsonNull)
            add("")
        }
        inner[6] = buildJsonArray { add(1) }
        inner[7] = JsonPrimitive(1) // streaming
        inner[10] = JsonPrimitive(1)
        inner[11] = JsonPrimitive(0)
        inner[17] = buildJsonArray { add(buildJsonArray { add(0) }) }
        inner[18] = JsonPrimitive(0)
        inner[27] = JsonPrimitive(1)
        inner[30] = buildJsonArray { add(4) }
        inner[41] = buildJsonArray { add(1) }
        inner[53] = JsonPrimitive(0)
        inner[59] = JsonPrimitive(uuid)
        inner[61] = buildJsonArray { } // no extensions
        inner[68] = JsonPrimitive(1)
        inner[79] = JsonPrimitive(1) // default account model, no model header
        inner[80] = JsonPrimitive(1)
        // Slot 45 (temporary chat) stays null on purpose: the web flag changes history retention.
        val innerJson = buildJsonArray { inner.forEach { add(it) } }.toString()
        val freq = buildJsonArray {
            add(JsonNull)
            add(JsonPrimitive(innerJson))
        }.toString()
        val header = buildJsonArray {
            add(JsonPrimitive(uuid))
            add(1)
        }.toString()
        return Envelope(freq = freq, jspbHeader = header)
    }

    fun streamUrl(origin: String, bootstrap: GeminiWebBootstrap, requestId: Int): String =
        StringBuilder(origin)
            .append(GEMINI_WEB_STREAM_PATH)
            .append("?bl=").append(bootstrap.buildLabel.encodeUrl())
            .append("&f.sid=").append((bootstrap.sessionId ?: "").encodeUrl())
            .append("&hl=en&gl=us")
            .append("&_reqid=").append(requestId)
            .append("&rt=c")
            .toString()

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
