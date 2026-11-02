package me.rerere.ai.provider

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class WebSessionCredentialTest {
    private val json = Json { encodeDefaults = true }
    @Test fun `web settings roundtrip without serializing session credentials`() {
        val types = listOf<ProviderSetting>(ProviderSetting.GeminiWeb(), ProviderSetting.ClaudeWeb())
        for (setting in types) {
            val serialized = json.encodeToString<ProviderSetting>(setting)
            assertEquals(setting.id, json.decodeFromString<ProviderSetting>(serialized).id)
            assertFalse(serialized.contains("sessionKey"))
            assertFalse(serialized.contains("1PSID"))
            assertFalse((setting as ProviderSetting.WebAccount).toolBridgeEnabled)
            assertFalse(setting.supportsQuota)
            assertFalse(setting.supportsBuiltInSearch)
        }
    }
    @Test fun `credentials stay on exact official host and toString contains no partial secret`() {
        val session = WebSessionCredential(WebSessionService.CLAUDE, mapOf("sessionKey" to "a-private-value"))
        assertEquals("sessionKey=a-private-value", session.headerFor("claude.ai"))
        for (host in listOf("api.claude.ai", "evilclaude.ai", "claude.ai.evil", "localhost", "gemini.google.com")) {
            assertThrows(IllegalArgumentException::class.java) { session.headerFor(host) }
        }
        assertFalse(session.toString().contains("a-private"))
    }
    @Test fun `private client disables every logging interceptor redirect retry and ambient cookie jar`() {
        val original = OkHttpClient.Builder().addInterceptor { it.proceed(it.request()) }
            .addNetworkInterceptor { it.proceed(it.request()) }.build()
        val secured = original.forPrivateWebSession()
        assertTrue(secured.interceptors.isEmpty())
        assertTrue(secured.networkInterceptors.isEmpty())
        assertFalse(secured.followRedirects)
        assertFalse(secured.followSslRedirects)
        assertFalse(secured.retryOnConnectionFailure)
        assertNull(secured.cache)
        assertSame(CookieJar.NO_COOKIES, secured.cookieJar)
        assertSame(original.proxySelector, secured.proxySelector)
    }
}
