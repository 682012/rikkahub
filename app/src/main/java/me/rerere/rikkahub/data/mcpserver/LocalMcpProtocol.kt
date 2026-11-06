package me.rerere.rikkahub.data.mcpserver

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal, transport-agnostic MCP server core for the on-device endpoint.
 *
 * Scope, stated honestly: this speaks the MCP **Streamable HTTP JSON-response mode** of the
 * 2025-11-25 specification, i.e. one JSON-RPC message per `POST`, answered synchronously with
 * `application/json`. There is no SSE stream, no server-initiated request, no session resumption and
 * no batch support (batches were removed from the specification and are rejected here with
 * `-32600`). `GET` is not supported by this class at all - the HTTP layer answers `405`.
 *
 * Implemented methods: `initialize`, `ping`, `tools/list` (cursor paginated), `tools/call`.
 * `resources/...`, `prompts/...`, `completion/...`, `logging/...`, `sampling/...` and `roots/...` are **not**
 * implemented and not advertised in `capabilities`; calling them yields `-32601`.
 *
 * The class holds no mutable state: [tools] and [invoke] are called per request, nothing is cached,
 * so one instance can serve concurrent requests safely (verified by a concurrency test).
 *
 * Error discipline: `CancellationException` and `Error` are rethrown untouched; only ordinary
 * `Exception`s become a protocol error or an `isError` tool result, with a short redacted message and
 * never a stack trace.
 */
class LocalMcpProtocol(
    private val tools: suspend () -> List<LocalMcpTool>,
    private val invoke: suspend (String, JsonObject) -> JsonObject,
) {

    /**
     * Handles one parsed JSON-RPC payload.
     *
     * @return the response object to serialize, or `null` when the payload was a notification and
     * the transport must answer `202 Accepted` with an empty body.
     */
    suspend fun handle(request: JsonElement): JsonObject? {
        if (request is JsonArray) {
            return errorResponse(JsonNull, INVALID_REQUEST, "invalid request: JSON-RPC batches are not supported")
        }
        val message = request as? JsonObject
            ?: return errorResponse(JsonNull, INVALID_REQUEST, "invalid request: expected a single JSON-RPC object")

        val version = message["jsonrpc"]
        if (version !is JsonPrimitive || !version.isString || version.content != JSONRPC_VERSION) {
            return errorResponse(
                validIdOrNull(message["id"]),
                INVALID_REQUEST,
                "invalid request: jsonrpc must be \"$JSONRPC_VERSION\"",
            )
        }

        val methodElement = message["method"]
        if (methodElement !is JsonPrimitive || !methodElement.isString) {
            return errorResponse(validIdOrNull(message["id"]), INVALID_REQUEST, "invalid request: method must be a string")
        }
        val method = methodElement.content

        val idElement = message["id"]
        if (idElement != null && !isValidId(idElement)) {
            // An explicit null or an object/array/bool id cannot be echoed back reliably.
            return errorResponse(JsonNull, INVALID_REQUEST, "invalid request: id must be a string or a number")
        }
        val id: JsonElement? = idElement

        val rawParams = message["params"]
        val params = when (rawParams) {
            null, is JsonNull -> null
            is JsonObject -> rawParams
            else -> {
                if (id == null) return null // notification: never answer, never execute
                return errorResponse(id, INVALID_PARAMS, "invalid params: params must be an object")
            }
        }

        if (id == null) {
            // Notifications (`notifications/initialized`, `notifications/cancelled`, progress, ...)
            // are accepted for compatibility and never execute a tool. A tools/call without an id is
            // still a notification: it must not run, and it gets no response.
            return null
        }

        return when (method) {
            "initialize" -> initialize(id, params)
            "ping" -> resultResponse(id, buildJsonObject {})
            "tools/list" -> listTools(id, params)
            "tools/call" -> callTool(id, params)
            in NOT_IMPLEMENTED_METHODS -> errorResponse(
                id,
                METHOD_NOT_FOUND,
                "method not found: $method is not provided by this server",
            )
            else -> errorResponse(id, METHOD_NOT_FOUND, "method not found: $method")
        }
    }

    /**
     * Handles one raw request body. A body that is not valid JSON yields `-32700` (parse error).
     *
     * Size enforcement is the HTTP layer's job ([MAX_MCP_REQUEST_BYTES], answer `413` before this
     * point); this function only parses what it is given.
     */
    suspend fun handleText(text: String): JsonObject? {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return errorResponse(JsonNull, PARSE_ERROR, "parse error: request body is not valid JSON")
        }
        return handle(element)
    }

    private fun initialize(id: JsonElement, params: JsonObject?): JsonObject {
        val requestedElement = params?.get("protocolVersion")
        if (requestedElement != null && requestedElement !is JsonNull &&
            (requestedElement !is JsonPrimitive || !requestedElement.isString)
        ) {
            return errorResponse(id, INVALID_PARAMS, "invalid params: protocolVersion must be a string")
        }
        val requested = (requestedElement as? JsonPrimitive)?.takeIf { it.isString }?.content
        val negotiated = if (requested != null && requested in SUPPORTED_PROTOCOL_VERSIONS) {
            requested
        } else {
            DEFAULT_PROTOCOL_VERSION
        }

        return resultResponse(
            id,
            buildJsonObject {
                put("protocolVersion", negotiated)
                putJsonObject("capabilities") {
                    // Only tools are implemented; nothing else is advertised.
                    putJsonObject("tools") { put("listChanged", false) }
                }
                putJsonObject("serverInfo") {
                    put("name", SERVER_NAME)
                    put("version", SERVER_VERSION)
                }
            },
        )
    }

    private suspend fun listTools(id: JsonElement, params: JsonObject?): JsonObject {
        val cursorElement = params?.get("cursor")
        val cursor = when (cursorElement) {
            null, is JsonNull -> null
            is JsonPrimitive -> if (cursorElement.isString) {
                cursorElement.content
            } else {
                return errorResponse(id, INVALID_PARAMS, "invalid params: cursor must be a string")
            }
            else -> return errorResponse(id, INVALID_PARAMS, "invalid params: cursor must be a string")
        }

        val all = try {
            tools()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return internalError(id, e)
        }

        val start = if (cursor == null) {
            0
        } else {
            parseCursor(cursor) ?: return errorResponse(id, INVALID_PARAMS, "invalid params: unknown cursor")
        }
        if (start > all.size) {
            return errorResponse(id, INVALID_PARAMS, "invalid params: unknown cursor")
        }

        val end = minOf(start + TOOLS_PAGE_SIZE, all.size)
        return resultResponse(
            id,
            buildJsonObject {
                putJsonArray("tools") {
                    for (index in start until end) add(toolJson(all[index]))
                }
                if (end < all.size) put("nextCursor", CURSOR_PREFIX + end)
            },
        )
    }

    private suspend fun callTool(id: JsonElement, params: JsonObject?): JsonObject {
        if (params == null) {
            return errorResponse(id, INVALID_PARAMS, "invalid params: tools/call requires a params object")
        }

        val nameElement = params["name"]
        val requestedName = (nameElement as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return errorResponse(id, INVALID_PARAMS, "invalid params: tool name must be a string")

        val argumentsElement = params["arguments"]
        val arguments = when (argumentsElement) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> argumentsElement
            else -> return errorResponse(id, INVALID_PARAMS, "invalid params: arguments must be an object")
        }

        val all = try {
            tools()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return internalError(id, e)
        }

        if (all.none { it.name == requestedName }) {
            // Tool-level problem, not a protocol problem: report it as an isError tool result so the
            // model sees it, while keeping structuredContent machine readable.
            val shown = shortName(requestedName)
            return toolResult(
                id = id,
                text = "Unknown tool: $shown",
                structured = buildJsonObject {
                    put("ok", false)
                    put("error", "unknown_tool")
                    put("message", "Unknown tool: $shown")
                },
                isError = true,
            )
        }

        val output = try {
            invoke(requestedName, arguments)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val message = redact(e.message?.takeIf { it.isNotBlank() } ?: "tool failed")
            return toolResult(
                id = id,
                text = "Tool failed: $message",
                structured = buildJsonObject {
                    put("ok", false)
                    put("error", "tool_failed")
                    put("message", message)
                    put("tool", shortName(requestedName))
                },
                isError = true,
            )
        }

        val failureMessage = failureMessageOf(output)
        if (failureMessage != null) {
            return toolResult(
                id = id,
                text = "Tool failed: $failureMessage",
                structured = output,
                isError = true,
            )
        }

        return toolResult(
            id = id,
            text = truncate(output.toString(), MAX_TOOL_TEXT_CHARS),
            structured = output,
            isError = false,
        )
    }

    private fun toolJson(tool: LocalMcpTool): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("inputSchema", tool.inputSchema)
        putJsonObject("annotations") { put("readOnlyHint", tool.readOnly) }
    }

    private fun toolResult(
        id: JsonElement,
        text: String,
        structured: JsonObject,
        isError: Boolean,
    ): JsonObject = resultResponse(
        id,
        buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            put("structuredContent", structured)
            put("isError", isError)
        },
    )

    private fun resultResponse(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", JSONRPC_VERSION)
        put("id", id)
        put("result", result)
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", JSONRPC_VERSION)
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun internalError(id: JsonElement, cause: Exception): JsonObject {
        val message = redact(cause.message?.takeIf { it.isNotBlank() } ?: "unexpected failure")
        return errorResponse(id, INTERNAL_ERROR, "internal error: $message")
    }

    private companion object {
        const val JSONRPC_VERSION = "2.0"

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        const val DEFAULT_PROTOCOL_VERSION = "2025-11-25"
        val SUPPORTED_PROTOCOL_VERSIONS = setOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        const val SERVER_NAME = "Local App MCP"
        const val SERVER_VERSION = "1.0"

        /** Page size for `tools/list`; keeps one response small even if many tools get registered. */
        const val TOOLS_PAGE_SIZE = 64
        const val CURSOR_PREFIX = "o:"

        /** Tool text content cap; one tool result must never dump a multi-megabyte blob into a chat. */
        const val MAX_TOOL_TEXT_CHARS = 60_000
        const val MAX_ERROR_MESSAGE_CHARS = 300
        const val MAX_TOOL_NAME_CHARS = 96

        val NOT_IMPLEMENTED_METHODS = setOf(
            "resources/list",
            "resources/templates/list",
            "resources/read",
            "resources/subscribe",
            "resources/unsubscribe",
            "prompts/list",
            "prompts/get",
            "completion/complete",
            "logging/setLevel",
            "sampling/createMessage",
            "roots/list",
        )

        fun isValidId(id: JsonElement): Boolean {
            val primitive = id as? JsonPrimitive ?: return false
            if (primitive is JsonNull) return false
            if (primitive.isString) return true
            return primitive.content.toDoubleOrNull() != null
        }

        fun validIdOrNull(id: JsonElement?): JsonElement =
            if (id != null && isValidId(id)) id else JsonNull

        fun parseCursor(cursor: String): Int? {
            if (!cursor.startsWith(CURSOR_PREFIX)) return null
            val digits = cursor.substring(CURSOR_PREFIX.length)
            if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
            if (digits.length > 9) return null
            return digits.toIntOrNull()
        }

        /** `{"ok":false,...}` from a tool implementation becomes an isError result with a readable text. */
        fun failureMessageOf(output: JsonObject): String? {
            val ok = output["ok"] as? JsonPrimitive ?: return null
            if (ok is JsonNull || ok.isString) return null
            if (ok.booleanOrNull != false) return null
            val message = (output["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: (output["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: "tool reported a failure"
            return redact(message)
        }

        fun shortName(name: String): String =
            if (name.length <= MAX_TOOL_NAME_CHARS) name else name.take(MAX_TOOL_NAME_CHARS) + "..."

        fun truncate(text: String, limit: Int): String =
            if (text.length <= limit) text else text.take(limit) + "\n...[truncated " + (text.length - limit) + " chars]"

        /**
         * Collapses a throwable message to one short line and removes the obvious secret shapes
         * (bearer tokens, `token=`/`api_key=`/`password=` style pairs). Stack traces are never included
         * because only `message` is used.
         */
        fun redact(message: String): String {
            var text = message.replace(Regex("[\\r\\n\\t]+"), " ").trim()
            // Drop stack-frame fragments that some libraries put into the message itself.
            text = text.replace(Regex("\\s+at\\s+[\\w$.]+\\([^)]*\\)"), "")
            text = text.replace(Regex("(?i)(bearer\\s+)\\S+")) { it.groupValues[1] + "<redacted>" }
            text = text.replace(
                Regex("(?i)((?:access_?token|refresh_?token|auth_?token|token|api[_-]?key|apikey|secret|password|authorization)\\s*[=:]\\s*)\\S+"),
            ) { it.groupValues[1] + "<redacted>" }
            text = text.replace(Regex("\\s+"), " ")
            return truncate(text, MAX_ERROR_MESSAGE_CHARS)
        }
    }
}
