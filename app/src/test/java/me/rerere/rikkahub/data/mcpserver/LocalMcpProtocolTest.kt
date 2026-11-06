package me.rerere.rikkahub.data.mcpserver

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Counts calls so "notification must not execute a tool" is observable, not assumed. */
private class Recorder {
    val calls = AtomicInteger(0)
    val names = CopyOnWriteArrayList<String>()
    val arguments = CopyOnWriteArrayList<JsonObject>()
}

private fun echoTool() = LocalMcpTool(
    name = "echo",
    description = "Echo the given text back",
    inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("text") { put("type", "string") } }
        putJsonArray("required") { add("text") }
    },
)

private fun simpleTool(name: String, readOnly: Boolean = true) = LocalMcpTool(
    name = name,
    description = "test tool $name",
    inputSchema = buildJsonObject { put("type", "object") },
    readOnly = readOnly,
)

private fun baseTools(): List<LocalMcpTool> = listOf(echoTool(), simpleTool("boom"), simpleTool("denied", false), simpleTool("huge"))

private fun generatedTools(count: Int): List<LocalMcpTool> = (1..count).map { index ->
    LocalMcpTool(
        name = "tool_$index",
        description = "generated tool $index",
        inputSchema = buildJsonObject { put("type", "object") },
        readOnly = index % 2 == 0,
    )
}

private fun newProtocol(
    recorder: Recorder,
    extraTools: List<LocalMcpTool> = emptyList(),
): LocalMcpProtocol = LocalMcpProtocol(
    tools = { baseTools() + extraTools },
    invoke = { name, arguments ->
        recorder.calls.incrementAndGet()
        recorder.names.add(name)
        recorder.arguments.add(arguments)
        when (name) {
            "echo" -> buildJsonObject {
                put("ok", true)
                put("echo", arguments["text"]?.jsonPrimitive?.content ?: "")
            }
            "boom" -> throw IllegalStateException(
                "token=SUPERSECRET123 api_key=abc123def456 at com.example.Internal.fail(Source.kt:42)",
            )
            "denied" -> buildJsonObject {
                put("ok", false)
                put("error", "not_authorized")
                put("message", "device access is disabled")
            }
            "huge" -> buildJsonObject {
                put("ok", true)
                put("blob", "x".repeat(200_000))
            }
            else -> buildJsonObject { put("ok", true) }
        }
    },
)

private fun request(
    method: String,
    id: JsonElement? = JsonPrimitive(1),
    params: JsonObject? = null,
): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("method", method)
    if (id != null) put("id", id)
    if (params != null) put("params", params)
}

private fun JsonObject.result(): JsonObject = this["result"]?.jsonObject ?: error("no result in $this")
private fun JsonObject.error(): JsonObject = this["error"]?.jsonObject ?: error("no error in $this")
private fun JsonObject.errorCode(): Int = error()["code"]!!.jsonPrimitive.content.toInt()
private fun JsonObject.id(): JsonElement = this["id"] ?: error("no id in $this")
private fun JsonObject.firstText(): String =
    result()["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

class LocalMcpProtocolTest {

    // ---------------------------------------------------------------- initialize

    @Test
    fun `initialize returns default version server info and tools capability only`() {
        val recorder = Recorder()
        val proto = newProtocol(recorder)
        val response = runBlocking { proto.handle(request("initialize")) }!!
        val result = response.result()

        assertEquals("2.0", response["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), response.id())
        assertEquals("2025-11-25", result["protocolVersion"]!!.jsonPrimitive.content)

        val capabilities = result["capabilities"]!!.jsonObject
        assertEquals(setOf("tools"), capabilities.keys)
        assertFalse(capabilities["tools"]!!.jsonObject["listChanged"]!!.jsonPrimitive.content.toBoolean())
        // resources / prompts are not implemented, so they must not be advertised.
        assertNull(capabilities["resources"])
        assertNull(capabilities["prompts"])
        assertNull(capabilities["logging"])

        val serverInfo = result["serverInfo"]!!.jsonObject
        assertEquals("Local App MCP", serverInfo["name"]!!.jsonPrimitive.content)
        assertEquals("1.0", serverInfo["version"]!!.jsonPrimitive.content)

        assertEquals(0, recorder.calls.get())
    }

    @Test
    fun `initialize echoes a supported requested version and falls back otherwise`() {
        val proto = newProtocol(Recorder())

        suspend fun negotiate(version: String): String {
            val response = proto.handle(
                request(
                    "initialize",
                    params = buildJsonObject { put("protocolVersion", version) },
                ),
            )!!
            return response.result()["protocolVersion"]!!.jsonPrimitive.content
        }

        runBlocking {
            assertEquals("2025-06-18", negotiate("2025-06-18"))
            assertEquals("2025-03-26", negotiate("2025-03-26"))
            assertEquals("2024-11-05", negotiate("2024-11-05"))
            assertEquals("2025-11-25", negotiate("1999-01-01"))
            assertEquals("2025-11-25", negotiate(""))
        }
    }

    @Test
    fun `initialize rejects a non string protocol version`() {
        val proto = newProtocol(Recorder())
        val response = runBlocking {
            proto.handle(
                request("initialize", params = buildJsonObject { put("protocolVersion", 123) }),
            )
        }!!
        assertEquals(-32602, response.errorCode())
        assertEquals(JsonPrimitive(1), response.id())
    }

    // ---------------------------------------------------------------------- ping

    @Test
    fun `ping returns an empty result`() {
        val proto = newProtocol(Recorder())
        val response = runBlocking { proto.handle(request("ping", id = JsonPrimitive("p-1"))) }!!
        assertTrue(response.result().isEmpty())
        assertEquals(JsonPrimitive("p-1"), response.id())
    }

    // ---------------------------------------------------------------- tools/list

    @Test
    fun `tools list advertises schema and read only hint`() {
        val proto = newProtocol(Recorder())
        val response = runBlocking { proto.handle(request("tools/list")) }!!
        val tools = response.result()["tools"]!!.jsonArray
        assertEquals(4, tools.size)
        val tool = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "echo" }.jsonObject

        // readOnly=false is surfaced as readOnlyHint=false, still just a hint.
        val denied = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "denied" }.jsonObject
        assertFalse(denied["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("echo", tool["name"]!!.jsonPrimitive.content)
        assertEquals("Echo the given text back", tool["description"]!!.jsonPrimitive.content)
        assertEquals("object", tool["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(tool["annotations"]!!.jsonObject["readOnlyHint"]!!.jsonPrimitive.content.toBoolean())
        assertNull(response.result()["nextCursor"])
    }

    @Test
    fun `tools list paginates with an opaque cursor`() {
        val proto = newProtocol(Recorder(), generatedTools(67)) // 71 tools -> 64 + 7

        val first = runBlocking { proto.handle(request("tools/list", id = JsonPrimitive(1))) }!!
        val firstPage = first.result()["tools"]!!.jsonArray
        assertEquals(64, firstPage.size)
        val cursor = first.result()["nextCursor"]!!.jsonPrimitive.content
        assertTrue(cursor.isNotEmpty())
        assertEquals("echo", firstPage[0].jsonObject["name"]!!.jsonPrimitive.content)

        val second = runBlocking {
            proto.handle(
                request("tools/list", id = JsonPrimitive(2), params = buildJsonObject { put("cursor", cursor) }),
            )
        }!!
        val secondPage = second.result()["tools"]!!.jsonArray
        assertEquals(7, secondPage.size)
        assertNull(second.result()["nextCursor"])
        assertEquals("tool_67", secondPage.last().jsonObject["name"]!!.jsonPrimitive.content)
        // No tool is served twice across pages.
        val names = (firstPage + secondPage).map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `tools list rejects broken cursors`() {
        val proto = newProtocol(Recorder(), generatedTools(3))
        for (cursor in listOf("o:999", "o:-1", "o:", "abc", "o:1x", "o:999999999999")) {
            val response = runBlocking {
                proto.handle(
                    request("tools/list", params = buildJsonObject { put("cursor", cursor) }),
                )
            }!!
            assertEquals("cursor=$cursor", -32602, response.errorCode())
        }
        val numeric = runBlocking {
            proto.handle(request("tools/list", params = buildJsonObject { put("cursor", 5) }))
        }!!
        assertEquals(-32602, numeric.errorCode())
    }

    // ---------------------------------------------------------------- tools/call

    @Test
    fun `tools call returns text content structured content and isError false`() {
        val recorder = Recorder()
        val proto = newProtocol(recorder)
        val response = runBlocking {
            proto.handle(
                request(
                    "tools/call",
                    params = buildJsonObject {
                        put("name", "echo")
                        putJsonObject("arguments") { put("text", "hello mcp") }
                    },
                ),
            )
        }!!

        assertEquals(JsonPrimitive(1), response.id())
        assertNull(response["error"])
        val result = response.result()
        val content = result["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hello mcp", result["structuredContent"]!!.jsonObject["echo"]!!.jsonPrimitive.content)
        assertFalse(result["isError"]!!.jsonPrimitive.content.toBoolean())

        assertEquals(1, recorder.calls.get())
        assertEquals("hello mcp", recorder.arguments[0]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools call accepts missing arguments as an empty object`() {
        val recorder = Recorder()
        val proto = newProtocol(recorder)
        val response = runBlocking {
            proto.handle(request("tools/call", params = buildJsonObject { put("name", "echo") }))
        }!!
        assertTrue(response.result()["structuredContent"]!!.jsonObject["echo"]!!.jsonPrimitive.content.isEmpty())
        assertTrue(recorder.arguments[0].isEmpty())
    }

    @Test
    fun `unknown tool is a business error not a protocol error`() {
        val recorder = Recorder()
        val proto = newProtocol(recorder)
        val response = runBlocking {
            proto.handle(
                request(
                    "tools/call",
                    params = buildJsonObject {
                        put("name", "does_not_exist")
                        putJsonObject("arguments") { put("a", 1) }
                    },
                ),
            )
        }!!

        assertNull(response["error"])
        val result = response.result()
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("unknown_tool", result["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertTrue(response.firstText().contains("does_not_exist"))
        assertEquals(0, recorder.calls.get()) // never invoked
    }

    @Test
    fun `tool exception becomes isError without stack trace or secrets`() {
        val proto = newProtocol(Recorder())
        val response = runBlocking {
            proto.handle(request("tools/call", params = buildJsonObject { put("name", "boom") }))
        }!!

        assertNull(response["error"])
        val result = response.result()
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
        val structured = result["structuredContent"]!!.jsonObject
        assertEquals("tool_failed", structured["error"]!!.jsonPrimitive.content)

        val text = response.firstText()
        assertFalse(text.contains("SUPERSECRET123"))
        assertFalse(text.contains("abc123def456"))
        assertFalse(text.contains("com.example.Internal"))
        assertFalse(text.contains("at com.example"))
        assertTrue(text.contains("<redacted>"))
        assertTrue(text.length < 400)
    }

    @Test
    fun `business ok false result is reported as isError`() {
        val proto = newProtocol(Recorder())
        val response = runBlocking {
            proto.handle(request("tools/call", params = buildJsonObject { put("name", "denied") }))
        }!!
        val result = response.result()
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
        // structuredContent stays exactly what the tool returned
        assertEquals(
            "not_authorized",
            result["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content,
        )
        assertTrue(response.firstText().contains("device access is disabled"))
    }

    @Test
    fun `tool text is truncated for huge payloads`() {
        val proto = newProtocol(Recorder())
        val response = runBlocking {
            proto.handle(request("tools/call", params = buildJsonObject { put("name", "huge") }))
        }!!
        val text = response.firstText()
        assertTrue(text.length <= 60_000 + 64)
        assertTrue(text.contains("[truncated"))
    }

    @Test
    fun `tools call invalid params are -32602`() {
        val proto = newProtocol(Recorder())
        val cases = listOf(
            buildJsonObject { }, // no name
            buildJsonObject { put("name", 42) },
            buildJsonObject { put("name", JsonNull) },
            buildJsonObject {
                put("name", "echo")
                put("arguments", JsonPrimitive("not-an-object"))
            },
            buildJsonObject {
                put("name", "echo")
                putJsonArray("arguments") { add("x") }
            },
        )
        for (params in cases) {
            val response = runBlocking { proto.handle(request("tools/call", params = params)) }!!
            assertEquals(params.toString(), -32602, response.errorCode())
            assertEquals(JsonPrimitive(1), response.id())
        }
        // params missing entirely
        val missing = runBlocking { proto.handle(request("tools/call")) }!!
        assertEquals(-32602, missing.errorCode())
    }

    // ------------------------------------------------------- protocol structure

    @Test
    fun `unknown methods are -32601 and unimplemented families are not advertised`() {
        val proto = newProtocol(Recorder())
        for (method in listOf(
            "tools/nope",
            "resources/list",
            "resources/templates/list",
            "resources/read",
            "prompts/list",
            "prompts/get",
            "completion/complete",
            "logging/setLevel",
            "sampling/createMessage",
            "roots/list",
        )) {
            val response = runBlocking { proto.handle(request(method, id = JsonPrimitive("m"))) }!!
            assertEquals(method, -32601, response.errorCode())
            assertEquals(JsonPrimitive("m"), response.id())
        }
    }

    @Test
    fun `notifications are accepted with no response and never run a tool`() {
        val recorder = Recorder()
        val proto = newProtocol(recorder)

        val initialized = runBlocking {
            proto.handle(request("notifications/initialized", id = null))
        }
        val cancelled = runBlocking {
            proto.handle(
                request(
                    "notifications/cancelled",
                    id = null,
                    params = buildJsonObject { put("requestId", 1) },
                ),
            )
        }
        val progress = runBlocking { proto.handle(request("notifications/progress", id = null)) }

        assertNull(initialized)
        assertNull(cancelled)
        assertNull(progress)
        assertEquals(0, recorder.calls.get())
    }

    @Test
    fun `tools call without an id is a notification and cannot execute`() {
        val recorder = Recorder()
        val proto = newProtocol(recorder)
        val response = runBlocking {
            proto.handle(
                request(
                    "tools/call",
                    id = null,
                    params = buildJsonObject {
                        put("name", "echo")
                        putJsonObject("arguments") { put("text", "must not run") }
                    },
                ),
            )
        }
        assertNull(response)
        assertEquals(0, recorder.calls.get())
    }

    @Test
    fun `notification without id for an unknown method stays silent`() {
        val proto = newProtocol(Recorder())
        assertNull(runBlocking { proto.handle(request("whatever/unknown", id = null)) })
    }

    @Test
    fun `batch arrays are rejected as invalid request`() {
        val proto = newProtocol(Recorder())
        val batch = JsonArray(
            listOf(
                request("ping", id = JsonPrimitive(1)),
                request("ping", id = JsonPrimitive(2)),
            ),
        )
        val response = runBlocking { proto.handle(batch) }!!
        assertEquals(-32600, response.errorCode())
        assertEquals(JsonNull, response.id())

        val emptyBatch = runBlocking { proto.handleText("[]") }!!
        assertEquals(-32600, emptyBatch.errorCode())
    }

    @Test
    fun `malformed envelopes are -32600`() {
        val proto = newProtocol(Recorder())
        val cases = listOf(
            buildJsonObject { put("id", 1); put("method", "ping") }, // no jsonrpc
            buildJsonObject { put("jsonrpc", "1.0"); put("id", 1); put("method", "ping") },
            buildJsonObject { put("jsonrpc", 2.0); put("id", 1); put("method", "ping") },
            buildJsonObject { put("jsonrpc", "2.0"); put("id", 1) }, // no method
            buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", 7) },
        )
        for (envelope in cases) {
            val response = runBlocking { proto.handle(envelope) }!!
            assertEquals(envelope.toString(), -32600, response.errorCode())
        }
        // Non-object payloads cannot be a JSON-RPC message at all.
        assertEquals(-32600, runBlocking { proto.handle(JsonPrimitive("hello")) }!!.errorCode())
        assertEquals(-32600, runBlocking { proto.handle(JsonNull) }!!.errorCode())
    }

    @Test
    fun `explicit null id is rejected but a normal request keeps its id`() {
        val proto = newProtocol(Recorder())
        val nullId = runBlocking {
            proto.handle(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", JsonNull)
                    put("method", "ping")
                },
            )
        }!!
        assertEquals(-32600, nullId.errorCode())
        assertEquals(JsonNull, nullId.id())

        val stringId = runBlocking { proto.handle(request("ping", id = JsonPrimitive("abc-123"))) }!!
        assertEquals(JsonPrimitive("abc-123"), stringId.id())

        val numberId = runBlocking { proto.handle(request("ping", id = JsonPrimitive(7))) }!!
        assertEquals("7", numberId.id().jsonPrimitive.content)
    }

    @Test
    fun `params must be an object when present`() {
        val proto = newProtocol(Recorder())
        val response = runBlocking {
            proto.handle(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 3)
                    put("method", "ping")
                    putJsonArray("params") { add(1) }
                },
            )
        }!!
        assertEquals(-32602, response.errorCode())
        assertEquals(JsonPrimitive(3), response.id())
    }

    @Test
    fun `parse errors are -32700 with null id`() {
        val proto = newProtocol(Recorder())
        for (body in listOf("", "   ", "not json", "{", "{\"jsonrpc\":}", "{} {}")) {
            val response = runBlocking { proto.handleText(body) }!!
            assertEquals(body, -32700, response.errorCode())
            assertEquals(JsonNull, response.id())
        }
        // Valid JSON that is not a JSON-RPC object is an invalid request, not a parse error.
        for (body in listOf("\"hello\"", "123", "true", "null")) {
            val response = runBlocking { proto.handleText(body) }!!
            assertEquals(body, -32600, response.errorCode())
        }
    }

    @Test
    fun `handleText works end to end for a real request body`() {
        val proto = newProtocol(Recorder())
        val body = """
            {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"echo","arguments":{"text":"via text"}}}
        """.trimIndent()
        val response = runBlocking { proto.handleText(body) }!!
        assertEquals(JsonPrimitive(9), response.id())
        assertEquals("via text", response.result()["structuredContent"]!!.jsonObject["echo"]!!.jsonPrimitive.content)
    }

    @Test
    fun `failing tool provider is an internal error without a stack trace`() {
        val proto = LocalMcpProtocol(
            tools = { throw IllegalStateException("open failed: password=hunter2") },
            invoke = { _, _ -> buildJsonObject { put("ok", true) } },
        )
        val response = runBlocking { proto.handle(request("tools/list", id = JsonPrimitive(4))) }!!
        assertEquals(-32603, response.errorCode())
        assertEquals(JsonPrimitive(4), response.id())
        val message = response.error()["message"]!!.jsonPrimitive.content
        assertFalse(message.contains("hunter2"))
        assertFalse(message.contains("at me.rerere"))
        assertTrue(message.contains("<redacted>"))
    }

    // -------------------------------------------------------------- robustness

    @Test
    fun `cancellation and errors are never swallowed`() {
        val cancelling = LocalMcpProtocol(
            tools = { listOf(echoTool()) },
            invoke = { _, _ -> throw CancellationException("client went away") },
        )
        try {
            runBlocking {
                cancelling.handle(
                    request("tools/call", params = buildJsonObject { put("name", "echo") }),
                )
            }
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            assertEquals("client went away", expected.message)
        }

        val broken = LocalMcpProtocol(
            tools = { listOf(echoTool()) },
            invoke = { _, _ -> throw AssertionError("fatal") },
        )
        try {
            runBlocking {
                broken.handle(
                    request("tools/call", params = buildJsonObject { put("name", "echo") }),
                )
            }
            fail("Error must propagate")
        } catch (expected: AssertionError) {
            assertEquals("fatal", expected.message)
        }
    }

    @Test
    fun `concurrent requests do not share mutable state`() = runBlocking {
        val recorder = Recorder()
        val proto = newProtocol(recorder)

        val calls = (1..48).map { index ->
            async(Dispatchers.Default) {
                val id = JsonPrimitive(index)
                val response = proto.handle(
                    request(
                        "tools/call",
                        id = id,
                        params = buildJsonObject {
                            put("name", "echo")
                            putJsonObject("arguments") { put("text", "value-$index") }
                        },
                    ),
                )!!
                // Every response must carry its own id and its own payload, never a neighbour's.
                assertEquals(id, response.id())
                assertEquals(
                    "value-$index",
                    response.result()["structuredContent"]!!.jsonObject["echo"]!!.jsonPrimitive.content,
                )
                assertEquals(
                    "value-$index",
                    response.firstText().substringAfter("\"echo\":\"").substringBefore("\""),
                )
            }
        } + (1..16).map {
            async(Dispatchers.Default) {
                val response = proto.handle(request("tools/list", id = JsonPrimitive(1000 + it)))!!
                assertEquals(4, response.result()["tools"]!!.jsonArray.size)
                assertEquals(JsonPrimitive(1000 + it), response.id())
            }
        } + (1..8).map {
            async(Dispatchers.Default) {
                val response = proto.handle(request("initialize", id = JsonPrimitive(2000 + it)))!!
                assertEquals("2025-11-25", response.result()["protocolVersion"]!!.jsonPrimitive.content)
            }
        }

        calls.awaitAll()
        assertEquals(48, recorder.calls.get())
    }

    @Test
    fun `one instance serves interleaved failures and successes without leaking`() = runBlocking {
        val recorder = Recorder()
        val proto = newProtocol(recorder)
        val results = listOf("echo", "boom", "does_not_exist", "denied").map { name ->
            async(Dispatchers.Default) {
                proto.handle(request("tools/call", params = buildJsonObject { put("name", name) }))!!
            }
        }.awaitAll()

        val isError = results.map { it.result()["isError"]!!.jsonPrimitive.content.toBoolean() }
        assertEquals(listOf(false, true, true, true), isError)
        results.forEach { assertNotNull(it["result"]) }
    }
}
