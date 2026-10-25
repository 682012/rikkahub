package me.rerere.rikkahub.data.repository

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.datastore.RemoteExecutionSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteErrorSlowBodyTest {
    @Test(timeout = 10000)
    fun `unlimited successful requests still bound stalled error previews`() {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "slow-error-fixture").apply { isDaemon = true }
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(429, 1024)
                exchange.responseBody.write("{\"error\":\"execution slots full\"".toByteArray())
                exchange.responseBody.flush()
                Thread.sleep(8000)
            } catch (_: Exception) {
                // Teardown interrupts the deliberately stalled producer.
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val settings = RemoteExecutionSettings(
                enabled = true, baseUrl = "http://127.0.0.1:${server.address.port}",
                token = "test-only-secret", requestTimeoutSec = 0,
            )
            val failure = runCatching {
                runBlocking {
                    withTimeout(6000) { RemoteExecutionClient().startJob(settings, "true", "work") }
                }
            }.exceptionOrNull()
            assertTrue("must report HTTP failure rather than hang: $failure", failure is IllegalStateException)
            val message = failure?.message.orEmpty()
            assertTrue(message, message.contains("HTTP 429"))
            assertTrue(message, message.contains("execution slots full"))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
