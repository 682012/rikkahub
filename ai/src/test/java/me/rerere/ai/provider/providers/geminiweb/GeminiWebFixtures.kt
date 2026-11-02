package me.rerere.ai.provider.providers.geminiweb

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * Sanitized fixtures shaped like the real StreamGenerate body: `)]}'\n`, then repeated
 * `<decimal length>\n<json array>\n` records, each wrapping a `wrb.fr` tuple whose third element is the
 * inner JSPB array rendered as a string.
 *
 * The inner array mirrors the 81-slot request/response layout verified against gemini_webapi
 * (commit 1ad0d4a): ids at `[1][0]`/`[1][1]`, candidates at `[4]`, `candidate[0]` rcid,
 * `candidate[1][0]` cumulative text, `candidate[8][0] == 2` terminal marker, `[25]` string
 * means "stored in history" only. No captured production traffic is stored here; ids are fabricated.
 */
internal object GeminiWebFixtures {

    fun inner(
        text: String?,
        conversationId: String? = "c_sanitized",
        responseId: String? = "rc_sanitized",
        rcid: String = "rcid_sanitized",
        complete: Boolean = true,
        historyStored: Boolean = false,
    ): String {
        val body = MutableList<JsonElement>(81) { JsonNull }
        body[1] = buildJsonArray {
            if (conversationId != null) add(conversationId) else add(JsonNull)
            if (responseId != null) add(responseId) else add(JsonNull)
        }
        body[4] = buildJsonArray {
            add(
                buildJsonArray {
                    add(rcid)
                    add(buildJsonArray { if (text != null) add(text) else add(JsonNull) })
                    add(JsonNull)
                    add(JsonNull)
                    add(JsonNull)
                    add(JsonNull)
                    add(JsonNull)
                    add(JsonNull)
                    // [8][0] == 2 is the only terminal marker; anything else is still in progress.
                    add(buildJsonArray { add(if (complete) 2 else 1) })
                }
            )
        }
        if (historyStored) body[25] = kotlinx.serialization.json.JsonPrimitive("stored-in-history-metadata")
        return buildJsonArray { body.forEach { add(it) } }.toString()
    }

    /** One complete frame payload (the JSON array that follows the length prefix). */
    fun frame(
        text: String?,
        conversationId: String? = "c_sanitized",
        responseId: String? = "rc_sanitized",
        rcid: String = "rcid_sanitized",
        complete: Boolean = true,
        historyStored: Boolean = false,
    ): String = buildJsonArray {
        add(buildJsonArray {
            add("wrb.fr")
            add(JsonNull)
            add(inner(text, conversationId, responseId, rcid, complete, historyStored))
            add(JsonNull)
            add(JsonNull)
            add(JsonNull)
            add("generic")
        })
    }.toString()

    /** Frame carrying a fatal `part[5][2][0][1][0]` error code. */
    fun errorFrame(code: Int): String = buildJsonArray {
        add(buildJsonArray {
            add("wrb.fr")
            add(JsonNull)
            add(inner(null))
            add(JsonNull)
            add(JsonNull)
            add(buildJsonArray {
                add(JsonNull)
                add(JsonNull)
                add(buildJsonArray {
                    add(buildJsonArray {
                        add(JsonNull)
                        add(buildJsonArray { add(code) })
                    })
                })
            })
            add("generic")
        })
    }.toString()

    /** Full body with byte-count framing (what the endpoint uses for ASCII and UTF-8 alike). */
    fun body(frames: List<String>, sentinel: Boolean = true): String = buildString {
        if (sentinel) append(")]}'\n\n")
        frames.forEach { payload ->
            append(payload.toByteArray(Charsets.UTF_8).size).append('\n').append(payload).append('\n')
        }
    }

    /** Body where the prefix counts UTF-16 chars, not bytes (defensive variant). */
    fun charFramedBody(frames: List<String>): String = buildString {
        append(")]}'\n")
        frames.forEach { payload -> append(payload.length).append('\n').append(payload).append('\n') }
    }
}
