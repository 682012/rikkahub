package me.rerere.ai.provider.providers.geminiweb

import me.rerere.ai.provider.WebProtocolException
import me.rerere.ai.provider.WebSessionException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Incremental decoder for the StreamGenerate body.
 *
 * The body is `)]}'\n` followed by repeated `<decimal length>\n<json array>` records. The length prefix
 * is handled as a byte count first (that is what a body framing field means); if a frame only parses
 * when the prefix is read as a character count, that interpretation is remembered for the rest of the
 * stream. Working on bytes keeps UTF-8 sequences that are split across two chunks intact.
 *
 * Bare JSON-lines frames (a line starting with `[`) are accepted too, because footer/error records of
 * this endpoint are not always length framed.
 *
 * This decoder only splits the stream; it never decides that an answer is complete. A frame boundary
 * at EOF is a normal end of framing, not a finished answer.
 */
internal class GeminiWebFrameDecoder {

    private enum class LengthUnit { BYTES, CHARS }

    private var pending: ByteArray = ByteArray(0)
    private var sentinelHandled = false
    private var lengthUnit: LengthUnit? = null

    /** Appends [chunk] and returns every complete frame payload that became available. */
    fun push(chunk: ByteArray, length: Int = chunk.size): List<String> {
        if (length <= 0) return emptyList()
        pending = if (pending.isEmpty()) chunk.copyOf(length) else pending + chunk.copyOf(length)
        val frames = mutableListOf<String>()
        var consumed = 0
        while (true) {
            val step = tryExtract(consumed, frames) ?: break
            if (step <= consumed) break
            consumed = step
        }
        if (consumed > 0) {
            pending = pending.copyOfRange(consumed, pending.size)
        }
        return frames
    }

    /**
     * Signals end of body. A leftover partial frame means the stream was cut in half; that must never be
     * presented as a finished answer. A clean boundary is fine here: completion is checked from the
     * terminal candidate marker, not from framing.
     */
    fun finish(): List<String> {
        val frames = mutableListOf<String>()
        var consumed = 0
        while (true) {
            val step = tryExtract(consumed, frames) ?: break
            if (step <= consumed) break
            consumed = step
        }
        val rest = pending.copyOfRange(consumed, pending.size)
        pending = ByteArray(0)
        if (rest.any { !it.isWhitespaceByte() }) {
            val trailing = decodeLenient(rest, 0, rest.size).trim()
            // A last frame without a trailing newline is legitimate; only a *partial* frame is fatal.
            if (isJsonArray(trailing)) {
                frames.add(trailing)
                return frames
            }
            throw WebProtocolException(
                "Gemini Web 响应在帧中途结束（剩余 ${rest.size} 字节），已中止而不是拼出半截答案"
            )
        }
        return frames
    }

    /** Returns the new consumed offset, or null when more bytes are required. */
    private fun tryExtract(offset: Int, out: MutableList<String>): Int? {
        var pos = offset
        if (!sentinelHandled) {
            pos = skipWhitespace(pending, pos)
            val remaining = pending.size - pos
            if (remaining == 0) return null
            val head = String(pending, pos, minOf(remaining, 4), StandardCharsets.ISO_8859_1)
            if (head.startsWith(SENTINEL)) {
                pos += SENTINEL.length
                sentinelHandled = true
                return pos // consume the sentinel even if the following length header is incomplete
            } else if (head[0] == '<') {
                // HTML login/consent/challenge page: non-retryable session failure, and the page body
                // (which can contain account hints) is never echoed back.
                throw WebSessionException(GEMINI_WEB_RELOGIN_MESSAGE)
            } else if (head.length >= SENTINEL.length && !head[0].isDigit() && head[0] != '[') {
                throw WebProtocolException("Gemini Web 返回了非协议内容")
            } else if (head[0].isDigit() || head[0] == '[') {
                sentinelHandled = true
            } else {
                return null
            }
        }

        pos = skipWhitespace(pending, pos)
        if (pos >= pending.size) return maxOf(pos, offset)
        val first = pending[pos]
        if (first == '['.code.toByte()) {
            return extractJsonLine(pos, out)
        }
        if (!first.isDigit()) {
            throw WebProtocolException("Gemini Web 帧缺少长度前缀")
        }

        var digitEnd = pos
        while (digitEnd < pending.size && pending[digitEnd].isDigit()) digitEnd++
        if (digitEnd >= pending.size) return null // length prefix itself is split
        val lengthText = String(pending, pos, digitEnd - pos, StandardCharsets.US_ASCII)
        val declared = lengthText.toIntOrNull()
            ?: throw WebProtocolException("Gemini Web 帧长度非法")
        if (declared > GEMINI_WEB_MAX_FRAME_BYTES) {
            throw WebProtocolException("Gemini Web 单帧超过 1MB 上限，已中止")
        }
        // The length is terminated by a newline; tolerate a bare CR.
        if (pending[digitEnd] != '\n'.code.toByte() && pending[digitEnd] != '\r'.code.toByte()) {
            throw WebProtocolException("Gemini Web 帧长度后缺少换行")
        }
        var payloadStart = digitEnd
        while (payloadStart < pending.size &&
            (pending[payloadStart] == '\n'.code.toByte() || pending[payloadStart] == '\r'.code.toByte())
        ) payloadStart++
        if (payloadStart >= pending.size) return null

        val available = pending.size - payloadStart
        val asBytes = if (available >= declared) {
            decodeStrict(pending, payloadStart, declared)
        } else {
            null
        }
        val asChars = run {
            val all = decodeLenient(pending, payloadStart, available)
            if (all.length >= declared) all.substring(0, declared) else null
        }

        // ASCII frames cannot establish a unit for later Unicode frames. Validate each complete
        // JSON frame independently, never emit a prefix before the declared payload has arrived.
        val byteFrame = asBytes?.takeIf { isJsonArray(it) }
        val charFrame = asChars?.takeIf { candidate ->
            isJsonArray(candidate) && candidate.toByteArray(StandardCharsets.UTF_8).let { encoded ->
                encoded.size <= available && encoded.indices.all { encoded[it] == pending[payloadStart + it] }
            }
        }
        val chosen = byteFrame ?: charFrame ?: run {
            if (asChars == null) return null
            throw WebProtocolException("Gemini Web 帧内容不是完整 JSON 数组")
        }
        val consumed = payloadStart + if (byteFrame != null) declared else chosen.toByteArray(StandardCharsets.UTF_8).size
        out.add(chosen)
        return consumed
    }

    private fun extractJsonLine(offset: Int, out: MutableList<String>): Int? {
        var end = offset
        while (end < pending.size && pending[end] != '\n'.code.toByte()) end++
        if (end >= pending.size) {
            if (pending.size - offset > GEMINI_WEB_MAX_FRAME_BYTES) {
                throw WebProtocolException("Gemini Web 单帧超过 1MB 上限，已中止")
            }
            return null
        }
        val line = decodeLenient(pending, offset, end - offset).trimEnd('\r')
        if (!line.startsWith("[")) {
            throw WebProtocolException("Gemini Web 帧内容不是 JSON 数组")
        }
        if (line.toByteArray(StandardCharsets.UTF_8).size > GEMINI_WEB_MAX_FRAME_BYTES) {
            throw WebProtocolException("Gemini Web 单帧超过 1MB 上限，已中止")
        }
        out.add(line)
        return end + 1
    }

    private fun skipWhitespace(buffer: ByteArray, from: Int): Int {
        var i = from
        while (i < buffer.size && buffer[i].isWhitespaceByte()) i++
        return i
    }

    private fun Byte.isWhitespaceByte(): Boolean =
        this == '\n'.code.toByte() || this == '\r'.code.toByte() || this == ' '.code.toByte() ||
            this == '\t'.code.toByte()

    private fun Byte.isDigit(): Boolean = this in '0'.code.toByte()..'9'.code.toByte()

    private fun isJsonArray(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return false
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            true
        }.getOrDefault(false)
    }

    private fun decodeStrict(buffer: ByteArray, offset: Int, length: Int): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(buffer, offset, length))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun decodeLenient(buffer: ByteArray, offset: Int, length: Int): String =
        String(buffer, offset, length, StandardCharsets.UTF_8)

    private companion object {
        const val SENTINEL = ")]}'"
    }
}
