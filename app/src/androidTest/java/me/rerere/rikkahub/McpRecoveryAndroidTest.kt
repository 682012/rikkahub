package me.rerere.rikkahub

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.common.http.NetworkHandover
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class McpRecoveryAndroidTest {
    @Test(timeout = 90_000)
    fun lostToolResponseReconnectsButNeverRepeatsTheSentCall() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "mcp-recovery-${System.nanoTime()}.preferences_pb")
        val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val appScope = AppScope()
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = storageScope) { file }, storageScope)
        val server = DroppingMcpServer()
        val route = NetworkHandover().apply { updateRoute("wifi", true) }
        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "recovery-test"),
            url = "http://127.0.0.1:${server.port}/mcp",
        )
        val manager = McpManager(store, appScope, GlobalContext.get().get<FilesManager>(), route)
        try {
            store.awaitLoaded()
            store.update { it.copy(mcpServers = listOf(config)) }
            withTimeout(20_000) { manager.getStatus(config).first { it == McpStatus.Connected } }
            val result = withTimeout(25_000) { manager.callTool(config.id, "write_once", buildJsonObject {}) }
            val message = result.joinToString()
            assertTrue("Expected explicit unknown outcome, got $message", message.contains("UNKNOWN"))
            assertTrue(message.contains("NOT retried"))
            withTimeout(20_000) {
                while (server.initializations.get() < 2 || manager.getClient(config) == null) delay(50)
            }
            assertEquals("The response was lost after execution: it must not be replayed", 1, server.executions.get())
            val second = withTimeout(15_000) { manager.callTool(config.id, "write_once", buildJsonObject {}) }
            assertTrue(second.joinToString().contains("executed-2"))
            assertEquals(2, server.executions.get())
            assertTrue(server.errors.isEmpty())
        } finally {
            store.update { it.copy(mcpServers = emptyList()) }
            manager.removeClient(config)
            appScope.cancel(); storageScope.cancel()
            server.close(); file.delete()
        }
    }
}

/** Real HTTP JSON-RPC, no injected fake client; the first tool response disappears after execution. */
private class DroppingMcpServer : Closeable {
    private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    val port: Int get() = socket.localPort
    val initializations = AtomicInteger()
    val executions = AtomicInteger()
    val errors = CopyOnWriteArrayList<Throwable>()
    @Volatile private var closed = false
    init {
        executor.submit {
            while (!closed) {
                try { val client = socket.accept(); executor.submit { handle(client) } }
                catch (e: Exception) { if (!closed) errors += e }
            }
        }
    }
    private fun handle(client: Socket) {
        try {
            client.use { s ->
                s.soTimeout = 15_000
                val input = s.getInputStream().buffered()
                val first = line(input)
                if (first.isBlank()) return
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val h = line(input)
                    if (h.isEmpty()) break
                    headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim()
                }
                if (!first.startsWith("POST ")) { respond(s, 405, ""); return }
                val bytes = if (headers["transfer-encoding"]?.contains("chunked") == true) {
                    val out = java.io.ByteArrayOutputStream()
                    while (true) {
                        val size = line(input).substringBefore(';').toInt(16)
                        if (size == 0) { line(input); break }
                        out.write(input.readNBytes(size)); line(input)
                    }
                    out.toByteArray()
                } else input.readNBytes(headers["content-length"]?.toInt() ?: 0)
                val body = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                val id = body["id"]
                val method = body["method"]?.jsonPrimitive?.content
                if (id == null) { respond(s, 202, ""); return }
                val result = when (method) {
                    "initialize" -> {
                        initializations.incrementAndGet()
                        buildJsonObject {
                            put("protocolVersion", body["params"]?.jsonObject?.get("protocolVersion") ?: JsonPrimitive("2025-03-26"))
                            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                            put("serverInfo", buildJsonObject { put("name", "fixture"); put("version", "1") })
                        }
                    }
                    "tools/list" -> buildJsonObject {
                        putJsonArray("tools") { add(buildJsonObject {
                            put("name", "write_once"); put("description", "Test side effect")
                            put("inputSchema", buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) })
                        }) }
                    }
                    "tools/call" -> {
                        val n = executions.incrementAndGet()
                        if (n == 1) { s.setSoLinger(true, 0); return }
                        buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "executed-$n") }) } }
                    }
                    else -> buildJsonObject {}
                }
                respond(s, 200, buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString())
            }
        } catch (e: Exception) { if (!closed) errors += e }
    }
    private fun respond(s: Socket, code: Int, text: String) {
        val bytes = text.toByteArray()
        s.getOutputStream().apply {
            write("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }
    private fun line(input: InputStream): String {
        val text = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == 10) return text.toString().trimEnd('\r')
            require(text.length < 16384)
            text.append(c.toChar())
        }
    }
    override fun close() { closed = true; socket.close(); executor.shutdownNow() }
}
