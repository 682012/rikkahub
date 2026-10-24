package me.rerere.rikkahub.data.repository

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 远程执行里"单次 HTTP 请求超时"的语义（对应 fork.34 的 P3：
 * requestTimeoutSec 说明写 0 = 不限，实现却把 0 改成 30s）。
 *
 * 用反射读 OkHttp 的超时字段，避免依赖 5.x 里属性/函数签名的差异。
 */
class RemoteCallTimeoutTest {
    private fun intProperty(client: OkHttpClient, name: String): Int =
        OkHttpClient::class.java.getMethod(name).invoke(client) as Int

    @Test
    fun `zero stays zero so the call is unlimited`() {
        assertEquals(0L, resolveRemoteCallTimeoutSec(0))
    }

    @Test
    fun `positive values are used as is`() {
        assertEquals(45L, resolveRemoteCallTimeoutSec(45))
        assertEquals(1L, resolveRemoteCallTimeoutSec(1))
    }

    @Test
    fun `large values do not overflow into a negative timeout`() {
        assertEquals(Int.MAX_VALUE.toLong(), resolveRemoteCallTimeoutSec(Int.MAX_VALUE))
    }

    @Test
    fun `negative values are rejected instead of silently becoming unlimited`() {
        val error = runCatching { resolveRemoteCallTimeoutSec(-1) }.exceptionOrNull()
        assertTrue("负值必须抛 IllegalArgumentException，实际：$error", error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("requestTimeoutSec"))
    }

    @Test
    fun `unbounded client drops call and socket timeouts but keeps connect timeout`() {
        val base = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        assertEquals(30_000, intProperty(base, "callTimeoutMillis"))
        assertEquals(30_000, intProperty(base, "readTimeoutMillis"))

        val unbounded = buildUnboundedClient(base)
        assertEquals(0, intProperty(unbounded, "callTimeoutMillis"))
        assertEquals(0, intProperty(unbounded, "readTimeoutMillis"))
        assertEquals(0, intProperty(unbounded, "writeTimeoutMillis"))
        assertEquals(15_000, intProperty(unbounded, "connectTimeoutMillis"))

        // 原客户端不受影响，命令被掐断后 killJob 仍走有界的那个
        assertEquals(30_000, intProperty(base, "readTimeoutMillis"))
    }
}
