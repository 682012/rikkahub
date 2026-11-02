package me.rerere.ai.provider

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException

/** Local-only web sessions are not OAuth tokens and are never serialized in provider settings. */
enum class WebSessionService(val host: String, val home: String) {
    GEMINI("gemini.google.com", "https://gemini.google.com/app"),
    CLAUDE("claude.ai", "https://claude.ai/new"),
}

class WebSessionCredential(val service: WebSessionService, cookies: Map<String, String>) {
    val cookies: Map<String, String> = cookies.toMap()
    fun headerFor(host: String): String {
        require(host.equals(service.host, ignoreCase = true)) { "Web session must stay on its official origin" }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
    override fun toString() = "WebSessionCredential(service=$service, cookies=[REDACTED])"
}

interface WebSessionCredentialProvider {
    suspend fun getSession(setting: ProviderSetting.WebAccount): WebSessionCredential
}

val ProviderSetting.WebAccount.webService: WebSessionService
    get() = when (this) {
        is ProviderSetting.GeminiWeb -> WebSessionService.GEMINI
        is ProviderSetting.ClaudeWeb -> WebSessionService.CLAUDE
    }

/** Authentication/challenge/rate-limit errors are not transient IO: never loop or disguise a browser. */
class WebSessionException(message: String) : IllegalStateException(message)
class WebProtocolException(message: String) : IOException(message)

/** Keep routing/proxy choices, remove every interceptor that could log Cookie or account content. */
fun OkHttpClient.forPrivateWebSession(): OkHttpClient = newBuilder().apply {
    interceptors().clear()
    networkInterceptors().clear()
    authenticator(Authenticator.NONE)
    cookieJar(CookieJar.NO_COOKIES)
    eventListener(EventListener.NONE)
    cache(null)
    followRedirects(false)
    followSslRedirects(false)
    retryOnConnectionFailure(false)
}.build()
