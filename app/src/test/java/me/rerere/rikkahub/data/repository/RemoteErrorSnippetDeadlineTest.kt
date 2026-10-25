package me.rerere.rikkahub.data.repository

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.RemoteExecutionSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteErrorSnippetDeadlineTest {
    private fun stalledFailure(prefix: String): Pair<String, Long> {
        val releaseBody = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(429, 8_192)
                exchange.responseBody.write(prefix.toByteArray())
                exchange.responseBody.flush()
                // The caller has received the error status, but the body is intentionally unfinished.
                releaseBody.await(7, TimeUnit.SECONDS)
            } finally {
                runCatching { exchange.close() }
            }
        }
        server.start()
        try {
            val settings = RemoteExecutionSettings(
                enabled = true,
                baseUrl = "http://127.0.0.1:${server.address.port}",
                token = "test-snippet-secret",
                requestTimeoutSec = 0,
            )
            val started = System.nanoTime()
            val error = runCatching {
                runBlocking { RemoteExecutionClient().startJob(settings, "echo hi", "~/rk-work") }
            }.exceptionOrNull()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Expected an HTTP error, got $error", error is IllegalStateException)
            return error!!.message.orEmpty() to elapsed
        } finally {
            releaseBody.countDown()
            server.stop(0)
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 15_000)
    fun `unlimited requests do not hang on an unfinished error body`() {
        val (message, elapsed) = stalledFailure("")
        assertTrue(message, message.contains("HTTP 429"))
        assertTrue("Waited $elapsed ms for optional error details", elapsed < 5_500)
    }

    @Test(timeout = 15_000)
    fun `deadline keeps a received reason and redacts the reflected secret`() {
        val (message, elapsed) = stalledFailure(
            """{"error":"execution slots full for test-snippet-secret"}"""
        )
        assertTrue(message, message.contains("HTTP 429"))
        assertTrue(message, message.contains("execution slots full"))
        assertFalse(message, message.contains("test-snippet-secret"))
        assertTrue("Waited $elapsed ms for optional error details", elapsed < 5_500)
    }
}
