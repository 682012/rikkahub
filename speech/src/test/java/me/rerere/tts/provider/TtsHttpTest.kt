package me.rerere.tts.provider

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TtsHttpTest {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private val client = OkHttpClient()
    @After fun close() {
        server?.stop(0); executor?.shutdownNow()
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
    private fun request(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Request {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        s.executor = executor
        s.createContext("/") { exchange -> try { handler(exchange) } finally { exchange.close() } }
        s.start(); server = s
        return Request.Builder().url("http://127.0.0.1:${s.address.port}/").build()
    }
    @Test fun responseBytesAreComplete() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val request = request { it.sendResponseHeaders(200, 4); it.responseBody.write(bytes) }
        assertArrayEquals(bytes, client.executeTtsBytes(request))
    }
    @Test fun httpStatusIsPreservedForRetryPolicy() = runBlocking {
        val request = request { it.sendResponseHeaders(429, -1) }
        try { client.executeTtsBytes(request); fail("expected HTTP error") }
        catch (e: TTSProviderException) { assertEquals(429, e.statusCode); assertTrue(e.isRetryable) }
    }
    @Test fun stopCancelsPendingBodyRead() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val request = request {
            it.sendResponseHeaders(200, 1000)
            it.responseBody.write(byteArrayOf(1)); it.responseBody.flush()
            arrived.complete(Unit); Thread.sleep(10000)
        }
        val job = async { client.executeTtsBytes(request) }
        withTimeout(3000) { arrived.await() }
        job.cancelAndJoin()
        withTimeout(3000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
        assertTrue(job.isCancelled)
    }
    @Test fun oversizedResponseFailsBeforeAllocatingIt() = runBlocking {
        val request = request { it.sendResponseHeaders(200, 33L * 1024 * 1024) }
        try { client.executeTtsBytes(request); fail("expected bounded response") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("32 MiB")) }
    }
}
