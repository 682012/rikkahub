package me.rerere.ai.provider.providers.geminiweb

import me.rerere.ai.provider.WebSessionCredential
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * The two — and only two — requests this provider can build. Everything stays on the fixed
 * `gemini.google.com` origin; there is no endpoint, host or path that provider settings can influence,
 * and no model header is sent (the account's own default model answers).
 */
internal object GeminiWebHttp {

    fun bootstrap(origin: String, credential: WebSessionCredential): Request =
        Request.Builder()
            .url(origin + GEMINI_WEB_BOOTSTRAP_PATH)
            .get()
            .header("User-Agent", GEMINI_WEB_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { cookieHeader(origin, credential)?.let { header("Cookie", it) } }
            .build()

    fun streamGenerate(
        origin: String,
        credential: WebSessionCredential,
        bootstrap: GeminiWebBootstrap,
        prompt: String,
        requestId: Int,
        language: String = "en",
    ): Request {
        val envelope = GeminiWebRequestBuilder.envelope(prompt, language)
        val form = FormBody.Builder()
            .add("f.req", envelope.freq)
            .add("at", bootstrap.snlm0e)
            .build()
        return Request.Builder()
            .url(GeminiWebRequestBuilder.streamUrl(origin, bootstrap, requestId))
            .post(form)
            .header("User-Agent", GEMINI_WEB_USER_AGENT)
            .header("Origin", origin)
            .header("Referer", "$origin/app")
            .header("X-Same-Domain", "1")
            .header(GEMINI_WEB_JSPB_HEADER, envelope.jspbHeader)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { cookieHeader(origin, credential)?.let { header("Cookie", it) } }
            .build()
    }

    /**
     * Session cookies are attached only when the request host is exactly the service host. A foreign host
     * (redirect, proxy, injected test origin) gets no credential at all, so nothing can leak the cookie.
     */
    fun cookieHeader(origin: String, credential: WebSessionCredential): String? {
        val host = origin.toHttpUrl().host
        return if (host.equals(credential.service.host, ignoreCase = true)) {
            credential.headerFor(host)
        } else {
            null
        }
    }
}
